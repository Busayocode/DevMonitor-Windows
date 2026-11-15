package com.aziitech.devmonitor.gateway.worker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
class GatewayDispatchRecoveryPolicyTest {
 private val local = LocalDispatchSnapshot("job-1","attempt-1","job-1","LEASED","LEASED")
 private val remote = ControllerDispatchSnapshot("job-1","attempt-1","LEASED")
 private val tx = SmsTransaction("tx-1","job-1","attempt-1","GLO-GD-273M","standard",Instant.parse("2026-11-15T11:00:00Z"),Instant.parse("2026-11-15T11:02:00Z"))
 @Test fun matchingLeaseCanProceedOnlyThroughExplicitCoordinator() { assertEquals(DispatchRecoveryAction.ELIGIBLE_LEASED_WORK,GatewayDispatchRecoveryPolicy.assess(remote,local,true,SmsChannelSnapshot()).action) }
 @Test fun missingLeaseTokenBlocksDispatch() { assertEquals(DispatchRecoveryAction.QUARANTINE,GatewayDispatchRecoveryPolicy.assess(remote,local,false,SmsChannelSnapshot()).action) }
 @Test fun sendingWithoutCallbackIsNeverRetried() { assertEquals(DispatchRecoveryAction.QUARANTINE,GatewayDispatchRecoveryPolicy.assess(remote.copy(state="SENDING"),local.copy(jobState="SENDING",attemptState="SENDING",transactionId="tx-1"),true,SmsChannelSnapshot(SmsChannelState.ACTIVE,tx)).action) }
 @Test fun successfulCallbackCanBeReconciledWithoutResend() { assertEquals(DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK,GatewayDispatchRecoveryPolicy.assess(remote.copy(state="SENDING"),local.copy(jobState="AWAITING_REPLY",attemptState="AWAITING_REPLY",transactionId="tx-1",sentAt="2026-11-15T11:00:01Z"),true,SmsChannelSnapshot(SmsChannelState.ACTIVE,tx)).action) }
 @Test fun activeReplyWaitCanResumeOnlyWhenAllRecordsMatch() { assertEquals(DispatchRecoveryAction.RESUME_WAITING_FOR_REPLY,GatewayDispatchRecoveryPolicy.assess(remote.copy(state="AWAITING_REPLY"),local.copy(jobState="AWAITING_REPLY",attemptState="AWAITING_REPLY",transactionId="tx-1",sentAt="2026-11-15T11:00:01Z"),true,SmsChannelSnapshot(SmsChannelState.ACTIVE,tx)).action) }
 @Test fun serverLocalMismatchQuarantines() { assertEquals(DispatchRecoveryAction.QUARANTINE,GatewayDispatchRecoveryPolicy.assess(remote.copy(attemptId="other"),local,true,SmsChannelSnapshot()).action) }
 @Test fun attemptOwnedByDifferentJobQuarantines() { assertEquals(DispatchRecoveryAction.QUARANTINE,GatewayDispatchRecoveryPolicy.assess(remote,local.copy(attemptJobId="other-job"),true,SmsChannelSnapshot()).action) }
 @Test fun missingRemoteAttemptWithLocalLeaseQuarantines() { assertEquals(DispatchRecoveryAction.QUARANTINE,GatewayDispatchRecoveryPolicy.assess(null,local,true,SmsChannelSnapshot()).action) }
}