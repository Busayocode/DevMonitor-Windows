package com.aziitech.devmonitor.gateway.worker

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GatewaySmsTransactionGateTest {
    private val t0 = Instant.parse("2026-11-15T10:00:00Z")

    private fun gate(store: InMemorySmsChannelStateStore = InMemorySmsChannelStateStore()) =
        GatewaySmsTransactionGate(store, Duration.ofSeconds(10))

    private fun begin(g: GatewaySmsTransactionGate, id: String = "tx-1") =
        g.begin(id, "job-$id", "attempt-$id", "GLO-GD-273M", "standard", t0, Duration.ofSeconds(120))

    @Test fun acceptsOnlyOneActiveTransactionAndEnforcesDrainWindow() {
        val g = gate()
        val tx = begin(g)
        assertEquals(SmsChannelState.ACTIVE, g.state(t0))
        assertThrows(IllegalStateException::class.java) { begin(g, "tx-2") }

        val accepted = g.on3030Message("sms-row-1", t0.plusSeconds(30), "TODAY=4;MTD=20")
        assertTrue(accepted is InboundSmsDecision.Accepted)
        assertEquals(tx, (accepted as InboundSmsDecision.Accepted).transaction)
        assertEquals(SmsChannelState.DRAINING, g.state(t0.plusSeconds(31)))
        assertThrows(IllegalStateException::class.java) {
            g.begin("tx-2", "job-2", "attempt-2", "GLO-GD-342F", "standard",
                t0.plusSeconds(31), Duration.ofSeconds(120))
        }
        val lateDuplicate = g.on3030Message("sms-row-2", t0.plusSeconds(32), "TODAY=4;MTD=20")
        assertTrue(lateDuplicate is InboundSmsDecision.Unassigned)
        assertEquals(SmsChannelState.DRAINING, g.state(t0.plusSeconds(32)))
        val tx2 = g.begin("tx-2", "job-2", "attempt-2", "GLO-GD-342F", "standard",
            t0.plusSeconds(42), Duration.ofSeconds(120))
        assertEquals("GLO-GD-342F", tx2.canonicalDeviceId)
    }

    @Test fun zeroPostReplyQuietPeriodAllowsNextTransactionImmediately() {
        val store = InMemorySmsChannelStateStore()
        val g = GatewaySmsTransactionGate(store, Duration.ZERO)
        begin(g)
        val accepted = g.on3030Message("sms-immediate", t0.plusSeconds(30), "TODAY=4;MTD=20")
        assertTrue(accepted is InboundSmsDecision.Accepted)
        assertEquals(SmsChannelState.IDLE, g.state(t0.plusSeconds(30)))
        val next = g.begin(
            "tx-2", "job-2", "attempt-2", "GLO-GD-342F", "standard",
            t0.plusSeconds(30), Duration.ofSeconds(120)
        )
        assertEquals("attempt-2", next.attemptId)
        assertEquals(SmsChannelState.ACTIVE, g.state(t0.plusSeconds(30)))
    }

    @Test fun timeoutQuarantinesUntilExplicitOperatorResolution() {
        val g = gate()
        begin(g)
        assertFalse(g.markTimedOut(t0.plusSeconds(119)))
        assertTrue(g.markTimedOut(t0.plusSeconds(120)))
        assertEquals(SmsChannelState.QUARANTINED, g.state(t0.plusSeconds(121)))
        assertThrows(IllegalStateException::class.java) { begin(g, "tx-2") }
        assertThrows(IllegalStateException::class.java) {
            g.resolveQuarantineByOperator("operator-1", "reviewed", false)
        }
        g.resolveQuarantineByOperator("operator-1", "inbox reviewed and drained", true)
        assertEquals(SmsChannelState.IDLE, g.state(t0.plusSeconds(122)))
    }

    @Test fun explicitSafetyQuarantineBlocksNewWorkUntilOperatorResolution() {
        val g = gate()
        begin(g)
        g.quarantine("Android rejected SMS submission")
        assertEquals(SmsChannelState.QUARANTINED, g.state(t0.plusSeconds(1)))
        assertThrows(IllegalStateException::class.java) { begin(g, "tx-2") }
        g.resolveQuarantineByOperator("operator-1", "verified no SMS was sent; inbox reviewed", true)
        assertEquals(SmsChannelState.IDLE, g.state(t0.plusSeconds(2)))
    }

    @Test fun duplicateAndUnsolicitedMessagesAreNeverAssigned() {
        val g = gate()
        assertTrue(g.on3030Message("sms-0", t0, "orphan") is InboundSmsDecision.Unassigned)
        begin(g)
        val first = g.on3030Message("sms-1", t0.plusSeconds(5), "valid")
        assertTrue(first is InboundSmsDecision.Accepted)
        assertTrue(g.on3030Message("sms-1", t0.plusSeconds(6), "valid") is InboundSmsDecision.Duplicate)
    }

    @Test fun expiredReplyQuarantinesInsteadOfCompleting() {
        val g = gate()
        begin(g)
        val decision = g.on3030Message("sms-late", t0.plusSeconds(121), "late payload")
        assertTrue(decision is InboundSmsDecision.Unassigned)
        assertEquals(SmsChannelState.QUARANTINED, g.state(t0.plusSeconds(122)))
    }

    @Test fun persistedSnapshotRestoresActiveTransactionAndQuarantine() {
        val store = InMemorySmsChannelStateStore()
        val firstProcess = gate(store)
        begin(firstProcess)
        val restartedProcess = gate(store)
        assertEquals(SmsChannelState.ACTIVE, restartedProcess.state(t0.plusSeconds(1)))
        assertTrue(restartedProcess.markTimedOut(t0.plusSeconds(120)))
        val afterRestart = gate(store)
        assertEquals(SmsChannelState.QUARANTINED, afterRestart.state(t0.plusSeconds(121)))
    }
}
