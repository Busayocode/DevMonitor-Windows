package com.aziitech.devmonitor.gateway.worker

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDispatchPreparationPolicyTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val base = GatewayDispatchPreparationRequest(
        jobId = "job-1",
        attemptId = "attempt-1",
        canonicalDeviceId = "GLO-GD-273M",
        queryProfile = "standard",
        leaseExpiresAt = now.plusSeconds(60),
        now = now,
        selectedSubscriptionId = 1,
        smsPermissionReady = true,
        telephonyReady = true,
        networkReady = true,
        responseTimeout = Duration.ofMinutes(2),
        channel = SmsChannelSnapshot()
    )

    private fun assess(request: GatewayDispatchPreparationRequest = base) =
        GatewayDispatchPreparationPolicy.assess(request)

    private fun assertBlocked(request: GatewayDispatchPreparationRequest, expected: String) {
        val result = assess(request)
        assertTrue("Expected blocked result but got $result", result is GatewayDispatchPreparation.Blocked)
        assertEquals(expected, (result as GatewayDispatchPreparation.Blocked).reason)
    }

    @Test fun completeFreshLeaseAndReadyPhonePassPreflight() {
        assertEquals(GatewayDispatchPreparation.ReadyForExplicitDispatchReview, assess())
    }

    @Test fun expiredLeaseIsBlocked() {
        assertBlocked(base.copy(leaseExpiresAt = now), "The controller lease has expired; do not send this attempt.")
    }

    @Test fun missingSimNeverFallsBackToAnotherSubscription() {
        assertBlocked(base.copy(selectedSubscriptionId = null), "An explicit active SIM selection is required; no fallback SIM is allowed.")
    }

    @Test fun negativeSimIdIsBlocked() {
        assertBlocked(base.copy(selectedSubscriptionId = -1), "An explicit active SIM selection is required; no fallback SIM is allowed.")
    }

    @Test fun missingSmsPermissionBlocksPreflight() {
        assertBlocked(base.copy(smsPermissionReady = false), "Android SMS permissions are not ready.")
    }

    @Test fun unreadySelectedSimBlocksPreflight() {
        assertBlocked(base.copy(telephonyReady = false), "The selected SIM is not active and ready.")
    }

    @Test fun missingValidatedNetworkBlocksPreflight() {
        assertBlocked(base.copy(networkReady = false), "A validated network connection is required for controller coordination.")
    }

    @Test fun invalidDeviceCodeIsBlocked() {
        assertBlocked(base.copy(canonicalDeviceId = "GLO GD 273M"), "The canonical device query code is invalid.")
    }

    @Test fun missingJobIdentityIsBlocked() {
        assertBlocked(base.copy(attemptId = ""), "A durable job and attempt identity are required.")
    }

    @Test fun nonPositiveResponseTimeoutIsBlocked() {
        assertBlocked(base.copy(responseTimeout = Duration.ZERO), "A positive 3030 response deadline is required.")
    }

    @Test fun activeTransactionBlocksPreflight() {
        val tx = SmsTransaction(
            "tx-1", "job-0", "attempt-0", "GLO-GD-000A", "standard",
            now.minusSeconds(5), now.plusSeconds(115)
        )
        assertBlocked(
            base.copy(channel = SmsChannelSnapshot(SmsChannelState.ACTIVE, tx)),
            "Another 3030 transaction is active."
        )
    }

    @Test fun cancellationOrDrainCooldownBlocksUntilDeadline() {
        val until = now.plusSeconds(240)
        val draining = SmsChannelSnapshot(SmsChannelState.DRAINING, drainUntil = until)
        assertBlocked(
            base.copy(channel = draining),
            "The channel cooldown has not elapsed."
        )
        assertEquals(
            GatewayDispatchPreparation.ReadyForExplicitDispatchReview,
            assess(base.copy(now = until, channel = draining))
        )
    }

    @Test fun quarantinedChannelRequiresOperatorReconciliation() {
        assertBlocked(
            base.copy(channel = SmsChannelSnapshot(SmsChannelState.QUARANTINED)),
            "The channel is quarantined and requires operator reconciliation."
        )
    }
}
