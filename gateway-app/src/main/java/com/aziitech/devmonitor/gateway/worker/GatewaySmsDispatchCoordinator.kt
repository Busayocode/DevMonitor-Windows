package com.aziitech.devmonitor.gateway.worker

import android.content.Context
import com.aziitech.devmonitor.gateway.GatewayWorkerControllerClient
import com.aziitech.devmonitor.gateway.ClaimedGatewayWork
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface GatewayCoordinatedDispatch {
    data class Submitted(val attemptId: String) : GatewayCoordinatedDispatch
    data class NotSent(val reason: String) : GatewayCoordinatedDispatch
}

/**
 * One-at-a-time controller-authorised SMS dispatch coordinator.
 * Explicit worker API only; it is not yet called by GatewayForegroundService.
 */
class GatewaySmsDispatchCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val sender = GatewaySmsSender(appContext)

    suspend fun dispatch(
        work: ClaimedGatewayWork,
        selectedSubscriptionId: Int?,
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        gate: GatewaySmsTransactionGate,
        responseTimeout: Duration
    ): GatewayCoordinatedDispatch {
        require(!responseTimeout.isNegative && !responseTimeout.isZero) {
            "A reviewed positive response timeout is required"
        }

        // Server acceptance precedes every local gate transition and SMS attempt.
        try {
            controller.markSending(work.jobId, work.attemptId, ledger, leaseTokens)
        } catch (failure: Exception) {
            // The server may have committed despite a lost response. Try the existing
            // ambiguity transition, but never send when acceptance is uncertain.
            runCatching {
                controller.markAmbiguous(
                    work.jobId, work.attemptId,
                    "SENDING acceptance could not be confirmed; no SMS was submitted",
                    ledger, leaseTokens
                )
            }
            return GatewayCoordinatedDispatch.NotSent(
                "Controller SENDING acceptance could not be confirmed; no SMS was sent. Reconcile this lease before further work."
            )
        }

        val startedAt = Instant.now()
        val transactionId = UUID.randomUUID().toString()
        val transaction = try {
            gate.begin(
                transactionId = transactionId,
                jobId = work.jobId,
                attemptId = work.attemptId,
                canonicalDeviceId = work.canonicalDeviceId,
                queryProfile = work.queryProfile,
                startedAt = startedAt,
                responseTimeout = responseTimeout
            )
        } catch (failure: Exception) {
            safelyMarkAmbiguous(work, "Local SMS channel was not safe to begin: ${failure.javaClass.simpleName}",
                controller, ledger, leaseTokens, gate)
            return GatewayCoordinatedDispatch.NotSent("SMS channel is not idle; controller attempt was marked ambiguous.")
        }

        try {
            val job = ledger.loadJob(work.jobId) ?: error("Durable claimed job is missing")
            val attempt = ledger.loadAttempts(work.jobId).firstOrNull { it.attemptId == work.attemptId }
                ?: error("Durable claimed attempt is missing")
            check(job.state == GatewayJobState.SENDING && attempt.state == GatewayAttemptState.SENDING) {
                "Durable job/attempt did not remain in SENDING"
            }
            ledger.saveJobAndAttempt(
                job,
                attempt.copy(
                    transactionId = transaction.transactionId,
                    sendStartedAt = startedAt.toString(),
                    responseDeadline = transaction.responseDeadline.toString(),
                    updatedAt = startedAt.toString()
                )
            )
        } catch (failure: Exception) {
            safelyMarkAmbiguous(work, "Could not persist transaction before SMS submission",
                controller, ledger, leaseTokens, gate)
            return GatewayCoordinatedDispatch.NotSent("Durable transaction preparation failed; no SMS was sent.")
        }

        val dispatchResult = runCatching {
            sender.sendQuery(
                selectedSubscriptionId = selectedSubscriptionId,
                canonicalDeviceId = work.canonicalDeviceId,
                sentResultIntent = GatewaySmsSender.createSendResultPendingIntent(appContext, work.attemptId),
                controllerAcceptedSending = true
            )
        }.getOrElse {
            GatewaySmsDispatchResult.Blocked("SMS preparation failed: ${it.javaClass.simpleName}")
        }

        return when (dispatchResult) {
            GatewaySmsDispatchResult.SubmittedToAndroid ->
                GatewayCoordinatedDispatch.Submitted(work.attemptId)
            is GatewaySmsDispatchResult.Blocked -> {
                safelyMarkAmbiguous(work, dispatchResult.reason, controller, ledger, leaseTokens, gate)
                GatewayCoordinatedDispatch.NotSent(dispatchResult.reason)
            }
        }
    }

    /** Replays the durable SMS_SENT callback outcome to the controller after process restart. */
    suspend fun reconcileSendCallback(
        jobId: String,
        attemptId: String,
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore
    ) {
        val attempt = ledger.loadAttempts(jobId).firstOrNull { it.attemptId == attemptId }
            ?: throw IllegalStateException("Durable SMS attempt is unavailable")
        when (attempt.state) {
            GatewayAttemptState.AWAITING_REPLY -> {
                val sentAt = attempt.sentAt
                    ?: throw IllegalStateException("Successful SMS callback has no sent timestamp")
                controller.markAwaitingReply(jobId, attemptId, sentAt, ledger, leaseTokens)
            }
            GatewayAttemptState.SEND_FAILED -> {
                controller.markAmbiguous(
                    jobId, attemptId,
                    "Android SMS_SENT callback reported failure; operator reconciliation required",
                    ledger, leaseTokens
                )
            }
            else -> throw IllegalStateException(
                "No reconciliable SMS_SENT outcome is stored for this attempt (${attempt.state})"
            )
        }
    }

    private suspend fun safelyMarkAmbiguous(
        work: ClaimedGatewayWork,
        reason: String,
        controller: GatewayWorkerControllerClient,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        gate: GatewaySmsTransactionGate
    ) {
        gate.quarantine(reason)
        runCatching {
            controller.markAmbiguous(work.jobId, work.attemptId, reason.take(160), ledger, leaseTokens)
        }
    }
}
