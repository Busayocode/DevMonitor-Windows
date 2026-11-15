package com.aziitech.devmonitor.gateway.worker

data class ControllerDispatchSnapshot(val jobId: String, val attemptId: String, val state: String)

enum class DispatchRecoveryAction { NO_ACTIVE_WORK, ELIGIBLE_LEASED_WORK, RECONCILE_SUCCESSFUL_SEND_CALLBACK, RESUME_WAITING_FOR_REPLY, QUARANTINE }
data class DispatchRecoveryAssessment(val action: DispatchRecoveryAction, val reason: String)

/** Never retries SENDING after process death: Android may already have accepted the SMS. */
object GatewayDispatchRecoveryPolicy {
    fun assess(controller: ControllerDispatchSnapshot?, localJob: GatewayJobRecord?, localAttempt: GatewayAttemptRecord?, leaseTokenAvailable: Boolean, channelState: SmsChannelState, channelTransaction: SmsTransaction?): DispatchRecoveryAssessment {
        if (controller == null) {
            val unresolved = localAttempt?.state in setOf(GatewayAttemptState.LEASED, GatewayAttemptState.SENDING, GatewayAttemptState.AWAITING_REPLY, GatewayAttemptState.AMBIGUOUS, GatewayAttemptState.TIMED_OUT)
            return if (unresolved) quarantine("Local work exists but the controller reports no active attempt") else DispatchRecoveryAssessment(DispatchRecoveryAction.NO_ACTIVE_WORK, "No active controller or local attempt")
        }
        if (localJob == null || localAttempt == null) return quarantine("Controller has active work but its durable local job/attempt is missing")
        if (controller.jobId != localJob.jobId || controller.jobId != localAttempt.jobId || controller.attemptId != localAttempt.attemptId) return quarantine("Controller and local job/attempt identities do not match")
        if (!leaseTokenAvailable) return quarantine("The encrypted lease token is unavailable")
        if (channelState == SmsChannelState.QUARANTINED) return quarantine("The local SMS channel is already quarantined")
        return when (controller.state.uppercase()) {
            "LEASED" -> if (localJob.state == GatewayJobState.LEASED && localAttempt.state == GatewayAttemptState.LEASED && localAttempt.transactionId == null && channelState in setOf(SmsChannelState.IDLE, SmsChannelState.DRAINING) && channelTransaction == null) DispatchRecoveryAssessment(DispatchRecoveryAction.ELIGIBLE_LEASED_WORK, "Matching lease is durable; explicit dispatch may proceed after drain expiry is checked") else quarantine("LEASED controller state conflicts with local send or channel state")
            "SENDING" -> if (localJob.state == GatewayJobState.AWAITING_REPLY && localAttempt.state == GatewayAttemptState.AWAITING_REPLY && localAttempt.sentAt != null && channelState == SmsChannelState.ACTIVE && channelTransaction?.attemptId == controller.attemptId) DispatchRecoveryAssessment(DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK, "Successful Android callback is durable; reconcile controller state without resending") else quarantine("Controller reports SENDING without a matching durable successful callback; SMS delivery is uncertain")
            "AWAITING_REPLY" -> if (localJob.state == GatewayJobState.AWAITING_REPLY && localAttempt.state == GatewayAttemptState.AWAITING_REPLY && localAttempt.sentAt != null && channelState == SmsChannelState.ACTIVE && channelTransaction?.attemptId == controller.attemptId) DispatchRecoveryAssessment(DispatchRecoveryAction.RESUME_WAITING_FOR_REPLY, "Controller, callback ledger and active transaction agree; resume reply intake only") else quarantine("AWAITING_REPLY state lacks a matching durable active transaction")
            else -> quarantine("Unsupported or terminal controller state requires reconciliation")
        }
    }
    private fun quarantine(reason: String) = DispatchRecoveryAssessment(DispatchRecoveryAction.QUARANTINE, reason)
}