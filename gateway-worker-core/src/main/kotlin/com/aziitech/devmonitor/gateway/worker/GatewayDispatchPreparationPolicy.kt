package com.aziitech.devmonitor.gateway.worker

import java.time.Duration
import java.time.Instant

data class GatewayDispatchPreparationRequest(
    val jobId: String,
    val attemptId: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val leaseExpiresAt: Instant,
    val now: Instant,
    val selectedSubscriptionId: Int?,
    val smsPermissionReady: Boolean,
    val telephonyReady: Boolean,
    val networkReady: Boolean,
    val responseTimeout: Duration,
    val channel: SmsChannelSnapshot
)

sealed interface GatewayDispatchPreparation {
    data object ReadyForExplicitDispatchReview : GatewayDispatchPreparation
    data class Blocked(val reason: String) : GatewayDispatchPreparation
}

/**
 * Side-effect-free final preflight for a controller-leased Gateway attempt.
 *
 * This policy does not transition controller/local state and cannot submit SMS.
 * A READY result is permission to proceed to a separately reviewed dispatch
 * coordinator only; it is not permission to bypass operator/service controls.
 */
object GatewayDispatchPreparationPolicy {
    fun assess(request: GatewayDispatchPreparationRequest): GatewayDispatchPreparation {
        if (request.jobId.isBlank() || request.attemptId.isBlank()) {
            return blocked("A durable job and attempt identity are required.")
        }
        if (request.canonicalDeviceId.isBlank() ||
            request.canonicalDeviceId.length > 40 ||
            !request.canonicalDeviceId.matches(Regex("[A-Za-z0-9-]+"))
        ) {
            return blocked("The canonical device query code is invalid.")
        }
        if (request.queryProfile.isBlank() || request.queryProfile.length > 80) {
            return blocked("The controller query profile is missing or invalid.")
        }
        if (!request.now.isBefore(request.leaseExpiresAt)) {
            return blocked("The controller lease has expired; do not send this attempt.")
        }
        if (request.selectedSubscriptionId == null || request.selectedSubscriptionId < 0) {
            return blocked("An explicit active SIM selection is required; no fallback SIM is allowed.")
        }
        if (!request.smsPermissionReady) {
            return blocked("Android SMS permissions are not ready.")
        }
        if (!request.telephonyReady) {
            return blocked("The selected SIM is not active and ready.")
        }
        if (!request.networkReady) {
            return blocked("A validated network connection is required for controller coordination.")
        }
        if (request.responseTimeout.isNegative || request.responseTimeout.isZero) {
            return blocked("A positive 3030 response deadline is required.")
        }

        val channelReady = when (request.channel.state) {
            SmsChannelState.IDLE -> true
            SmsChannelState.DRAINING ->
                request.channel.drainUntil?.let { !request.now.isBefore(it) } == true
            SmsChannelState.ACTIVE, SmsChannelState.QUARANTINED -> false
        }
        if (!channelReady) {
            return blocked(
                when (request.channel.state) {
                    SmsChannelState.ACTIVE -> "Another 3030 transaction is active."
                    SmsChannelState.DRAINING -> "The channel drain interval has not elapsed."
                    SmsChannelState.QUARANTINED -> "The channel is quarantined and requires operator reconciliation."
                    SmsChannelState.IDLE -> "The channel is not ready."
                }
            )
        }

        return GatewayDispatchPreparation.ReadyForExplicitDispatchReview
    }

    private fun blocked(reason: String) = GatewayDispatchPreparation.Blocked(reason)
}
