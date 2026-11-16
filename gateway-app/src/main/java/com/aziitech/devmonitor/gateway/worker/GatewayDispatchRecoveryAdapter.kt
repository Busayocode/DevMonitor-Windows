package com.aziitech.devmonitor.gateway.worker

import com.aziitech.devmonitor.gateway.GatewayWorkerControllerClient
import java.time.Instant

enum class GatewayRecoveryStatus {
    NO_ACTIVE_WORK,
    ELIGIBLE_LEASED_WORK,
    WAITING_FOR_DRAIN_WINDOW,
    WAITING_FOR_CANCELLATION_COOLDOWN,
    RESUME_WAITING_FOR_REPLY,
    QUARANTINED
}

data class GatewayRecoveryResult(
    val status: GatewayRecoveryStatus,
    val reason: String,
    val jobId: String? = null,
    val attemptId: String? = null,
    val cooldownUntil: Instant? = null
)

/**
 * Production Android adapter around the pure recovery policy.
 *
 * This performs reconciliation only: it never claims a job, sends an SMS,
 * advances the queue, or accepts a reply as a result. It must run before any
 * future worker loop is allowed to claim or dispatch work.
 */
class GatewayDispatchRecoveryAdapter {
    suspend fun reconcile(
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        gate: GatewaySmsTransactionGate,
        now: Instant = Instant.now()
    ): GatewayRecoveryResult {
        val server = controller.fetchActiveAttempt(leaseTokens)
        val unresolved = ledger.loadUnresolvedDispatches()

        if (server == null) {
            val channel = ledger.load()
            val effectiveState = gate.state(now)
            if (unresolved.isEmpty() && effectiveState == SmsChannelState.IDLE) {
                return GatewayRecoveryResult(
                    GatewayRecoveryStatus.NO_ACTIVE_WORK,
                    "Controller, local ledger and elapsed channel interval report no unresolved dispatch."
                )
            }
            if (unresolved.isEmpty() && effectiveState == SmsChannelState.CANCELLATION_COOLDOWN &&
                channel.cancellationCooldownUntil?.let { now.isBefore(it) } == true
            ) {
                return GatewayRecoveryResult(
                    GatewayRecoveryStatus.WAITING_FOR_CANCELLATION_COOLDOWN,
                    "Cancellation cooldown is active; wait until its persisted deadline before another 3030 query.",
                    cooldownUntil = channel.cancellationCooldownUntil
                )
            }
            if (unresolved.isEmpty() && effectiveState == SmsChannelState.DRAINING &&
                channel.drainUntil?.let { now.isBefore(it) } == true
            ) {
                return GatewayRecoveryResult(
                    GatewayRecoveryStatus.WAITING_FOR_DRAIN_WINDOW,
                    "The previous 3030 reply is still inside its quiet period; no new SMS may be sent."
                )
            }
            gate.quarantine("Local unresolved work or a non-idle SMS channel exists but controller reports no active attempt.")
            return GatewayRecoveryResult(
                GatewayRecoveryStatus.QUARANTINED,
                "Orphaned local work or channel state found without a matching controller attempt; operator reconciliation is required."
            )
        }

        val job = ledger.loadJob(server.jobId)
        val attempt = ledger.loadAttempts(server.jobId).firstOrNull { it.attemptId == server.attemptId }
        if (job == null || attempt == null) {
            return quarantine(
                gate,
                "Controller has active work but the matching durable local job/attempt is missing.",
                server.jobId,
                server.attemptId
            )
        }

        if (unresolved.size != 1 || unresolved.singleOrNull()?.attempt?.attemptId != server.attemptId) {
            return quarantine(
                gate,
                "Local ledger contains additional or conflicting unresolved attempts; only one physical-SIM transaction is allowed.",
                server.jobId,
                server.attemptId
            )
        }

        if (job.canonicalDeviceId != server.canonicalDeviceId || job.queryProfile != server.queryProfile ||
            job.gatewayId.isBlank() || attempt.gatewayId != job.gatewayId ||
            attempt.leaseExpiresAt != job.leaseExpiresAt
        ) {
            return quarantine(
                gate,
                "Controller and local device, query profile, Gateway or lease identity do not match.",
                server.jobId,
                server.attemptId
            )
        }

        val channel = ledger.load()
        val assessment = GatewayDispatchRecoveryPolicy.assess(
            controller = ControllerDispatchSnapshot(server.jobId, server.attemptId, server.state),
            local = LocalDispatchSnapshot(
                jobId = job.jobId,
                attemptId = attempt.attemptId,
                attemptJobId = attempt.jobId,
                jobState = job.state.name,
                attemptState = attempt.state.name,
                transactionId = attempt.transactionId,
                sentAt = attempt.sentAt
            ),
            leaseTokenAvailable = server.localLeaseTokenAvailable,
            channel = channel
        )

        if (assessment.action == DispatchRecoveryAction.QUARANTINE) {
            return quarantine(gate, assessment.reason, server.jobId, server.attemptId)
        }

        if (assessment.action == DispatchRecoveryAction.NO_ACTIVE_WORK) {
            return GatewayRecoveryResult(GatewayRecoveryStatus.NO_ACTIVE_WORK, assessment.reason)
        }

        if (assessment.action == DispatchRecoveryAction.ELIGIBLE_LEASED_WORK) {
            val leaseExpiry = runCatching { Instant.parse(server.leaseExpiresAt) }.getOrNull()
                ?: return quarantine(gate, "Controller lease expiry is invalid.", server.jobId, server.attemptId)
            if (!now.isBefore(leaseExpiry)) {
                return quarantine(gate, "Controller lease has expired; the SMS must not be sent.", server.jobId, server.attemptId)
            }
            when (gate.state(now)) {
                SmsChannelState.CANCELLATION_COOLDOWN -> return GatewayRecoveryResult(
                    GatewayRecoveryStatus.WAITING_FOR_CANCELLATION_COOLDOWN,
                    "Cancellation cooldown is active; wait until its persisted deadline before dispatch.",
                    server.jobId,
                    server.attemptId,
                    channel.cancellationCooldownUntil
                )
                SmsChannelState.DRAINING -> return GatewayRecoveryResult(
                    GatewayRecoveryStatus.WAITING_FOR_DRAIN_WINDOW,
                    "The previous 3030 quiet period has not elapsed; no new SMS may be sent.",
                    server.jobId,
                    server.attemptId
                )
                SmsChannelState.IDLE -> Unit
                SmsChannelState.ACTIVE, SmsChannelState.QUARANTINED -> return quarantine(
                    gate, "SMS channel is not safe for another dispatch.", server.jobId, server.attemptId
                )
            }
            return GatewayRecoveryResult(
                GatewayRecoveryStatus.ELIGIBLE_LEASED_WORK,
                assessment.reason,
                server.jobId,
                server.attemptId
            )
        }

        if (assessment.action == DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK ||
            assessment.action == DispatchRecoveryAction.RESUME_WAITING_FOR_REPLY
        ) {
            val deadline = attempt.responseDeadline?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (deadline == null || !now.isBefore(deadline)) {
                return markAmbiguous(
                    controller, ledger, leaseTokens, gate,
                    "The 3030 response deadline is missing or expired during recovery.",
                    server.jobId, server.attemptId
                )
            }

            if (assessment.action == DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK) {
                val sentAt = attempt.sentAt
                    ?: return quarantine(gate, "Successful Android send callback has no durable timestamp.", server.jobId, server.attemptId)
                try {
                    controller.markAwaitingReply(server.jobId, attempt.attemptId, sentAt, ledger, leaseTokens)
                } catch (_: Exception) {
                    return quarantine(
                        gate,
                        "Could not reconcile the successful Android send callback with the controller; no resend is permitted.",
                        server.jobId,
                        server.attemptId
                    )
                }
            }

            return GatewayRecoveryResult(
                GatewayRecoveryStatus.RESUME_WAITING_FOR_REPLY,
                "Controller and durable Android state agree. Resume reply intake only; do not resend.",
                server.jobId,
                server.attemptId
            )
        }

        return quarantine(gate, "Unsupported recovery state; operator reconciliation is required.", server.jobId, server.attemptId)
    }

    private suspend fun markAmbiguous(
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        gate: GatewaySmsTransactionGate,
        reason: String,
        jobId: String,
        attemptId: String
    ): GatewayRecoveryResult {
        gate.quarantine(reason)
        runCatching { controller.markAmbiguous(jobId, attemptId, reason, ledger, leaseTokens) }
        return GatewayRecoveryResult(GatewayRecoveryStatus.QUARANTINED, reason, jobId, attemptId)
    }

    private fun quarantine(
        gate: GatewaySmsTransactionGate,
        reason: String,
        jobId: String,
        attemptId: String
    ): GatewayRecoveryResult {
        gate.quarantine(reason)
        return GatewayRecoveryResult(GatewayRecoveryStatus.QUARANTINED, reason, jobId, attemptId)
    }
}
