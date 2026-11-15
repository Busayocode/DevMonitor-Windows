package com.aziitech.devmonitor.gateway.worker

import java.time.Instant

data class GatewayInboxProcessingSummary(
    val scanned: Int,
    val acceptedPendingController: Int,
    val unassigned: Int,
    val duplicates: Int
)

/**
 * Explicit processing step for the durable 3030 inbox.
 *
 * This class is intentionally not invoked by the SMS BroadcastReceiver. The
 * worker must call it only after it has restored the durable per-phone channel
 * gate and configured a reviewed post-reply quiet period. Accepted results are
 * staged locally; this class never submits controller results or starts sends.
 */
class Gateway3030InboxProcessor(
    private val inbox: GatewayInboundSmsStore,
    private val gate: GatewaySmsTransactionGate
) {
    fun processUnassigned(limit: Int = 50): GatewayInboxProcessingSummary {
        var accepted = 0
        var unassigned = 0
        var duplicates = 0
        val rows = inbox.loadUnassigned(limit)
        rows.forEach { row ->
            val receivedAt = runCatching { Instant.ofEpochMilli(row.receivedAtMillis) }
                .getOrElse {
                    inbox.markDecision(row.fingerprint, "UNASSIGNED_INVALID", "Invalid receive timestamp")
                    unassigned++
                    return@forEach
                }

            when (val parsed = Gateway3030ResponseParser.parse(row.body)) {
                is Gateway3030ParseResult.Invalid -> {
                    // Record the fingerprint in the channel gate even for malformed
                    // replies, so a repeated delivery cannot be reconsidered later.
                    val decision = gate.on3030Message(row.fingerprint, receivedAt, "")
                    val state = when (decision) {
                        InboundSmsDecision.Duplicate -> "DUPLICATE"
                        is InboundSmsDecision.Unassigned -> "UNASSIGNED_INVALID"
                        is InboundSmsDecision.Accepted -> "UNASSIGNED_INVALID"
                    }
                    inbox.markDecision(row.fingerprint, state, parsed.reason)
                    if (state == "DUPLICATE") duplicates++ else unassigned++
                }
                is Gateway3030ParseResult.Valid -> {
                    when (val decision = gate.on3030Message(
                        row.fingerprint, receivedAt, parsed.value.toCanonicalPayload()
                    )) {
                        is InboundSmsDecision.Accepted -> {
                            inbox.markDecision(
                                row.fingerprint,
                                "ACCEPTED_PENDING_CONTROLLER",
                                "Matched locally to transaction ${decision.transaction.transactionId}; controller submission not yet enabled"
                            )
                            accepted++
                        }
                        is InboundSmsDecision.Unassigned -> {
                            val state = when {
                                decision.reason.contains("quarantin", ignoreCase = true) ||
                                    gate.state(receivedAt) == SmsChannelState.QUARANTINED -> "QUARANTINED"
                                decision.reason.contains("drain", ignoreCase = true) -> "UNASSIGNED_DRAINING"
                                decision.reason.contains("deadline", ignoreCase = true) -> "UNASSIGNED_LATE"
                                else -> "UNASSIGNED_NO_TRANSACTION"
                            }
                            inbox.markDecision(row.fingerprint, state, decision.reason)
                            unassigned++
                        }
                        InboundSmsDecision.Duplicate -> {
                            inbox.markDecision(row.fingerprint, "DUPLICATE", "Fingerprint already seen by transaction gate")
                            duplicates++
                        }
                    }
                }
            }
        }
        return GatewayInboxProcessingSummary(rows.size, accepted, unassigned, duplicates)
    }
}
