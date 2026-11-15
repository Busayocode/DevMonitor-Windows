package com.aziitech.devmonitor.gateway.worker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
class GatewayDispatchRecoveryPolicyTest {
 private val job = GatewayJobRecord("job-1","gateway-1","GLO-GD-273M","standard",1,"2026-11-15T12:00:00Z",GatewayJobState.LEASED,"2026-11-15T11:00:00Z")
 private val attempt = GatewayAttemptRecord("attempt-1","job-1",1,GatewayAttemptState.LEASED,"2026-11-15T11:00:00Z","2026-11-15T11:00:00Z","gateway-1","2026-11-15T12:00:00Z")
 private val remote = ControllerDispatchSnapshot("job-1","attempt-1","LEASED")
 private val tx = SmsTransaction("tx-1","job-1","attempt-1","GLO-GD-273M","standard",Instant.parse("2026-11-15T11:00:00Z"),Instant.parse("2026-11-15T11:02:00Z"))
 @Test fun matchingLeaseCanProceedOnlyThroughExplicitCoordinator() { assertEquals(DispatchRecoveryAction.ELIGIBLE_LEASED_WORK, GatewayDispatchRecoveryPolicy.assess(remote,job,attempt,true,SmsChannelState.IDLE,null).action) }
 @Test fun missingLeaseTokenBlocksDispatch() { assertEquals(DispatchRecoveryAction.QUARANTINE, GatewayDispatchRecoveryPolicy.assess(remote,job,attempt,false,SmsChannelState.IDLE,null).action) }
 @Test fun sendingWithoutCallbackIsNeverRetried() { assertEquals(DispatchRecoveryAction.QUARANTINE, GatewayDispatchRecoveryPolicy.assess(remote.copy(state="SENDING"),job.copy(state=GatewayJobState.SENDING),attempt.copy(state=GatewayAttemptState.SENDING,transactionId="tx-1"),true,SmsChannelState.ACTIVE,tx).action) }
 @Test fun successfulCallbackCanBeReconciledWithoutResend() { assertEquals(DispatchRecoveryAction.RECONCILE_SUCCESSFUL_SEND_CALLBACK, GatewayDispatchRecoveryPolicy.assess(remote.copy(state="SENDING"),job.copy(state=GatewayJobState.AWAITING_REPLY),attempt.copy(state=GatewayAttemptState.AWAITING_REPLY,transactionId="tx-1",sentAt="2026-11-15T11:00:01Z"),true,SmsChannelState.ACTIVE,tx).action) }
 @Test fun activeReplyWaitCanResumeOnlyWhenAllRecordsMatch() { assertEquals(DispatchRecoveryAction.RESUME_WAITING_FOR_REPLY, GatewayDispatchRecoveryPolicy.assess(remote.copy(state="AWAITING_REPLY"),job.copy(state=GatewayJobState.AWAITING_REPLY),attempt.copy(state=GatewayAttemptState.AWAITING_REPLY,transactionId="tx-1",sentAt="2026-11-15T11:00:01Z"),true,SmsChannelState.ACTIVE,tx).action) }
 @Test fun serverLocalMismatchQuarantines() { assertEquals(DispatchRecoveryAction.QUARANTINE, GatewayDispatchRecoveryPolicy.assess(remote.copy(attemptId="other"),job,attempt,true,SmsChannelState.IDLE,null).action) }
 @Test fun missingRemoteAttemptWithLocalLeaseQuarantines() { assertEquals(DispatchRecoveryAction.QUARANTINE, GatewayDispatchRecoveryPolicy.assess(null,job,attempt,true,SmsChannelState.IDLE,null).action) }
}