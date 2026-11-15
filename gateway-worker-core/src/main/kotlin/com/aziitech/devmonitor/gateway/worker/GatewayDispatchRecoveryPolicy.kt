package com.aziitech.devmonitor.gateway.worker

data class ControllerDispatchSnapshot(val jobId: String, val attemptId: String, val state: String)
data class LocalDispatchSnapshot(val jobId: String, val attemptId: String, val jobState: String, val attemptState: String, val transactionId: String? = null, val sentAt: String? = null)
enum class DispatchRecoveryAction { NO_ACTIVE_WORK, ELIGIBLE_LEASED_WORK, RECONCILE_SUCCESSFUL_SEND_CALLBACK, RESUME_WAITING_FOR_REPLY, QUARANTINE }
data class DispatchRecoveryAssessment(val action: DispatchRecoveryAction, val reason: String)

/** Never retries SENDING after process death: Android may already have accepted the SMS. */
object GatewayDispatchRecoveryPolicy {
 fun assess(controller: ControllerDispatchSnapshot?, local: LocalDispatchSnapshot?, leaseTokenAvailable: Boolean, channel: SmsChannelSnapshot): DispatchRecoveryAssessment {
  if (controller == null) return if (local != null && local.attemptState in setOf("LEASED","SENDING","AWAITING_REPLY","AMBIGUOUS","TIMED_OUT")) quarantine("Local unresolved work exists but controller reports no active attempt") else DispatchRecoveryAssessment(DispatchRecoveryAction.NO_ACTIVE_WORK,"No active controller or local attempt")
  if (local == null) return quarantine("Controller has active work but durable local job/attempt is missing")
  if (controller.jobId != local.jobId || controller.jobId != local.jobId || controller.attemptId != local.attemptId) return quarantine("Controller and local job/attempt identities do not match")
  if (!leaseTokenAvailable) return quarantine("Encrypted lease token is unavailable")
  if (channel.state == SmsChannelState.QUARANTINED) return quarantine("Local SMS channel is already quarantined")
  val tx = channel.transaction
  return when (controller.state.uppercase()) {
   "LEASED" -> if (local.jobState == "LEASED" && local.attemptState == "LEASED" && local.transactionId == null && channel.state in setOf(SmsChannelState.IDLE,SmsChannelState.DRAINING) && tx == null) DispatchRecoveryAssessment(DispatchRecoveryAction.ELIGIBLE_LEASED_WORK,"Matching lease is durable; explicit dispatch may proceed after drain expiry is checked") else quarantine("LEASED controller state conflicts with local send or channel state")
   "SENDING" -> if (local.jobState == "AWAITING_REPLY" && local.attemptState == "AWAITING_REPLY" && local.sentAt != null && channel.state == SmsChannelState.ACTIVE && tx?.attemptId == controller.attemptId) DispatchRecoveryAssessment(DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK,"Successful Android callback is durable; reconcile controller state without resending") else quarantine("Controller reports SENDING without a matching durable successful callback; SMS delivery is uncertain")
   "AWAITING_REPLY" -> if (local.jobState == "AWAITING_REPLY" && local.attemptState == "AWAITING_REPLY" && local.sentAt != null && channel.state == SmsChannelState.ACTIVE && tx?.attemptId == controller.attemptId) DispatchRecoveryAssessment(DispatchRecoveryAction.RESUME_WAITING_FOR_REPLY,"Controller, callback ledger and active transaction agree; resume reply intake only") else quarantine("AWAITING_REPLY state lacks a matching durable active transaction")
   else -> quarantine("Unsupported or terminal controller state requires reconciliation")
  }
 }
 private fun quarantine(reason: String) = DispatchRecoveryAssessment(DispatchRecoveryAction.QUARANTINE,reason)
}