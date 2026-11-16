package com.aziitech.devmonitor.gateway.worker

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Gateway3030InboxProcessorTest {
    private lateinit var context: Context
    private lateinit var inbox: GatewayInboundSmsStore
    private lateinit var ledger: SqliteSmsChannelStateStore
    private lateinit var gate: GatewaySmsTransactionGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("devmonitor_gateway_inbox.db")
        context.deleteDatabase("devmonitor_gateway_worker.db")
        inbox = GatewayInboundSmsStore(context)
        ledger = SqliteSmsChannelStateStore(context)
        gate = GatewaySmsTransactionGate(
            ledger,
            Duration.ofMinutes(4)
        )
    }

    @After
    fun tearDown() {
        inbox.close()
        ledger.close()
        context.deleteDatabase("devmonitor_gateway_inbox.db")
        context.deleteDatabase("devmonitor_gateway_worker.db")
    }

    @Test
    fun correlatedReplyIsDurablyStagedWithExactAttemptIdentity() {
        val sentAt = Instant.parse("2026-11-16T09:00:00Z")
        gate.begin(
            transactionId = "tx-1",
            jobId = "job-1",
            attemptId = "attempt-1",
            canonicalDeviceId = "GLO-GD-001",
            queryProfile = "STANDARD",
            startedAt = sentAt,
            responseTimeout = Duration.ofMinutes(3)
        )
        val receivedAt = sentAt.plusSeconds(30)
        assertTrue(inbox.record3030("3030", receivedAt.toEpochMilli(), 1, validReply))

        val summary = Gateway3030InboxProcessor(inbox, gate).processUnassigned()

        assertEquals(1, summary.acceptedPendingController)
        val pending = inbox.loadPendingController().single()
        assertEquals("job-1", pending.matchedJobId)
        assertEquals("attempt-1", pending.matchedAttemptId)
        assertNotNull(pending.normalizedPayload)
        assertEquals("ACCEPTED_PENDING_CONTROLLER", pending.processingState)
        assertTrue(inbox.loadUnassigned().isEmpty())
    }

    @Test
    fun duplicateSmsBroadcastDoesNotCreateSecondInboxRow() {
        val receivedAt = 1_795_000_000_000L
        assertTrue(inbox.record3030("3030", receivedAt, 1, validReply))
        assertFalse(inbox.record3030("3030", receivedAt, 1, validReply))
        assertEquals(1, inbox.loadUnassigned().size)
    }

    @Test
    fun uncertainControllerSubmissionIsNotAutomaticallyQueuedAgain() {
        val receivedAt = 1_795_000_000_000L
        inbox.record3030("3030", receivedAt, 1, validReply)
        val row = inbox.loadUnassigned().single()
        assertTrue(inbox.stageAccepted(row.fingerprint, "job-1", "attempt-1", "canonical-payload"))
        assertTrue(inbox.markSubmissionUncertain(row.fingerprint, "network outcome unknown"))
        assertTrue(inbox.loadPendingController().isEmpty())
        assertEquals("SUBMISSION_UNCERTAIN", inbox.loadStateForTest(row.fingerprint))
    }

    private fun GatewayInboundSmsStore.loadStateForTest(fingerprint: String): String {
        val db = context.openOrCreateDatabase("devmonitor_gateway_inbox.db", Context.MODE_PRIVATE, null)
        return db.rawQuery(
            "SELECT processing_state FROM inbound_3030 WHERE fingerprint = ?",
            arrayOf(fingerprint)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }.also { db.close() }
    }

    private val validReply = """
        16/11/2026 09:00
        Today's SIMReg Count: 12
        MTD SIMReg Count: 145
        Active: 89
        Bar 48: 3
        Bar 96: 1
        Duplicates: 2
        Monthly Target: 500
    """.trimIndent()
}
