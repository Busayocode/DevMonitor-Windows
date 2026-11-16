package com.aziitech.devmonitor.gateway.worker

import com.aziitech.devmonitor.gateway.GatewayWorkerControllerClient
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                            inbox.stageAccepted(
                                fingerprint = row.fingerprint,
                                jobId = decision.transaction.jobId,
                                attemptId = decision.transaction.attemptId,
                                normalizedPayload = decision.normalizedPayload
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

    /**
     * Submits only replies already durably correlated by processUnassigned().
     * A transport/HTTP exception is treated as an uncertain outcome: the channel
     * is quarantined and the row is never automatically replayed, preventing a
     * duplicate result submission when the server may have committed it.
     */
    suspend fun submitAcceptedPending(
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        limit: Int = 20
    ): Int {
        var submitted = 0
        inbox.loadPendingController(limit).forEach { row ->
            val jobId = row.matchedJobId
            val attemptId = row.matchedAttemptId
            if (jobId.isNullOrBlank() || attemptId.isNullOrBlank()) {
                inbox.markSubmissionUncertain(row.fingerprint, "Correlated inbox row is missing its durable job/attempt identity")
                gate.quarantine("3030 inbox correlation metadata is incomplete")
                return@forEach
            }
            val parsed = Gateway3030ResponseParser.parse(row.body)
            if (parsed !is Gateway3030ParseResult.Valid) {
                inbox.markSubmissionUncertain(row.fingerprint, "Stored 3030 body no longer passes strict parser validation")
                gate.quarantine("Previously correlated 3030 payload failed revalidation")
                return@forEach
            }
            val attempt = ledger.loadAttempts(jobId).firstOrNull { it.attemptId == attemptId }
            if (attempt == null || attempt.state != GatewayAttemptState.AWAITING_REPLY) {
                inbox.markSubmissionUncertain(row.fingerprint, "Matching durable attempt is missing or no longer awaiting a reply")
                gate.quarantine("3030 reply does not match an awaiting durable attempt")
                return@forEach
            }

            val receivedAt = Instant.ofEpochMilli(row.receivedAtMillis)
            val checkedAt = runCatching { Instant.parse(attempt.sendStartedAt) }.getOrNull()
                ?: runCatching { Instant.parse(attempt.sentAt) }.getOrNull()
                ?: receivedAt
            val result = parsed.value
            val values = JSONObject()
                .put("todaySimRegCount", result.todaySimRegCount)
                .put("mtdSimRegCount", result.mtdSimRegCount)
                .put("active", result.active)
                .put("bar48", result.bar48)
                .put("bar96", result.bar96)
                .put("duplicates", result.duplicates)
                .put("monthlyTarget", result.monthlyTarget)
            if (!result.reportDateTime.isNullOrBlank()) values.put("reportDateTime", result.reportDateTime)

            val businessDate = parseBusinessDate(result.reportDateTime)
                ?: receivedAt.atZone(ZoneId.of("Africa/Lagos")).toLocalDate().toString()
            try {
                controller.submitResult(
                    jobId = jobId,
                    attemptId = attemptId,
                    leaseTokenStore = leaseTokens,
                    ledger = ledger,
                    businessDate = businessDate,
                    checkedAt = checkedAt.toString(),
                    receivedAt = receivedAt.toString(),
                    values = values,
                    parserVersion = "3030-v1"
                )
                inbox.markDecision(row.fingerprint, "SUBMITTED_TO_CONTROLLER", "Controller accepted result for attempt $attemptId")
                submitted++
            } catch (failure: Exception) {
                inbox.markSubmissionUncertain(
                    row.fingerprint,
                    "Controller result submission outcome is uncertain (${failure.javaClass.simpleName}); automatic replay blocked"
                )
                gate.quarantine("3030 result submission outcome is uncertain; operator reconciliation required")
            }
        }
        return submitted
    }

    private fun parseBusinessDate(source: String?): String? {
        if (source.isNullOrBlank()) return null
        val datePart = source.trim().substringBefore(' ')
        val formats = listOf(
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ROOT),
            DateTimeFormatter.ISO_LOCAL_DATE
        )
        return formats.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDate.parse(datePart, formatter).toString() }.getOrNull()
        }
    }

}
