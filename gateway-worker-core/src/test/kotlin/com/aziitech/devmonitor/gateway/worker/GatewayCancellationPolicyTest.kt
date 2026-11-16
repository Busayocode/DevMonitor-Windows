package com.aziitech.devmonitor.gateway.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayCancellationPolicyTest {
    @Test fun leasedAttemptCanOnlyBeCancelledBeforeAnySendPreparation() {
        val snapshot = GatewayCancellationSnapshot(
            controllerState = "LEASED",
            localJobState = "LEASED",
            localAttemptState = "LEASED",
            transactionId = null,
            sendStartedAt = null,
            sentAt = null
        )
        assertEquals(
            GatewayCancellationDecision.CANCEL_BEFORE_SEND,
            GatewayCancellationPolicy.assess(snapshot)
        )
    }

    @Test fun confirmedSentAttemptRequiresControllerClosureAndCooldown() {
        val snapshot = GatewayCancellationSnapshot(
            controllerState = "AWAITING_REPLY",
            localJobState = "AWAITING_REPLY",
            localAttemptState = "AWAITING_REPLY",
            transactionId = "tx-1",
            sendStartedAt = "2026-11-16T10:00:00Z",
            sentAt = "2026-11-16T10:00:02Z"
        )
        assertEquals(
            GatewayCancellationDecision.REQUIRE_CONTROLLER_CLOSURE_AND_COOLDOWN,
            GatewayCancellationPolicy.assess(snapshot)
        )
    }

    @Test fun sendingWithoutSuccessfulAndroidCallbackIsNeverTreatedAsCancellableOrSent() {
        val snapshot = GatewayCancellationSnapshot(
            controllerState = "SENDING",
            localJobState = "SENDING",
            localAttemptState = "SENDING",
            transactionId = "tx-1",
            sendStartedAt = "2026-11-16T10:00:00Z",
            sentAt = null
        )
        assertEquals(
            GatewayCancellationDecision.BLOCK_AND_QUARANTINE,
            GatewayCancellationPolicy.assess(snapshot)
        )
    }

    @Test fun mismatchedControllerAndLocalStatesCannotBeCancelled() {
        val snapshot = GatewayCancellationSnapshot(
            controllerState = "LEASED",
            localJobState = "AWAITING_REPLY",
            localAttemptState = "AWAITING_REPLY",
            transactionId = "tx-1",
            sendStartedAt = "2026-11-16T10:00:00Z",
            sentAt = "2026-11-16T10:00:02Z"
        )
        assertEquals(
            GatewayCancellationDecision.BLOCK_AND_QUARANTINE,
            GatewayCancellationPolicy.assess(snapshot)
        )
    }

    @Test fun ambiguousAttemptCannotBeReleasedByCancellationRequest() {
        val snapshot = GatewayCancellationSnapshot(
            controllerState = "AMBIGUOUS",
            localJobState = "AMBIGUOUS",
            localAttemptState = "AMBIGUOUS",
            transactionId = "tx-1",
            sendStartedAt = "2026-11-16T10:00:00Z",
            sentAt = null
        )
        assertEquals(
            GatewayCancellationDecision.BLOCK_AND_QUARANTINE,
            GatewayCancellationPolicy.assess(snapshot)
        )
    }
}
