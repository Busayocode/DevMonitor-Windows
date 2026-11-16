package com.aziitech.devmonitor.gateway.worker

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SqliteSmsChannelStateStoreTest {
    private lateinit var context: Context
    private var store: SqliteSmsChannelStateStore? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("devmonitor_gateway_worker.db")
        store = SqliteSmsChannelStateStore(context)
    }

    @After
    fun tearDown() {
        store?.close()
        store = null
        context.deleteDatabase("devmonitor_gateway_worker.db")
    }

    @Test
    fun claimAndAttemptSurviveStoreRecreation() {
        val (job, attempt) = records()
        store!!.saveClaim(job, attempt)
        store!!.close()

        store = SqliteSmsChannelStateStore(context)
        assertEquals(job, store!!.loadJob(job.jobId))
        assertEquals(listOf(attempt), store!!.loadAttempts(job.jobId))
        assertEquals(1, store!!.loadUnresolvedDispatches().size)
    }

    @Test
    fun successfulSendCallbackIsAppliedExactlyOnce() {
        val (job, attempt) = records(
            jobState = GatewayJobState.SENDING,
            attemptState = GatewayAttemptState.SENDING
        )
        store!!.saveClaim(job, attempt)

        assertTrue(store!!.recordSendResult(attempt.attemptId, Activity.RESULT_OK, SENT_AT))
        assertFalse(store!!.recordSendResult(attempt.attemptId, Activity.RESULT_OK, SENT_AT))

        assertEquals(GatewayJobState.AWAITING_REPLY, store!!.loadJob(job.jobId)!!.state)
        val saved = store!!.loadAttempts(job.jobId).single()
        assertEquals(GatewayAttemptState.AWAITING_REPLY, saved.state)
        assertEquals(SENT_AT, saved.sentAt)
    }

    @Test
    fun failedSendCallbackIsPersistedAndNotRetried() {
        val (job, attempt) = records(
            jobState = GatewayJobState.SENDING,
            attemptState = GatewayAttemptState.SENDING
        )
        store!!.saveClaim(job, attempt)

        assertTrue(store!!.recordSendResult(attempt.attemptId, 1, SENT_AT))
        assertFalse(store!!.recordSendResult(attempt.attemptId, Activity.RESULT_OK, SENT_AT))

        assertEquals(GatewayJobState.FAILED, store!!.loadJob(job.jobId)!!.state)
        val saved = store!!.loadAttempts(job.jobId).single()
        assertEquals(GatewayAttemptState.SEND_FAILED, saved.state)
        assertEquals("ANDROID_SEND_RESULT_1", saved.errorCode)
        assertNotNull(saved.errorMessage)
    }

    @Test
    fun callbackForUnknownOrNonSendingAttemptIsIgnored() {
        val (job, attempt) = records()
        store!!.saveClaim(job, attempt)

        assertFalse(store!!.recordSendResult("missing-attempt", Activity.RESULT_OK, SENT_AT))
        assertFalse(store!!.recordSendResult(attempt.attemptId, Activity.RESULT_OK, SENT_AT))
        assertEquals(GatewayJobState.LEASED, store!!.loadJob(job.jobId)!!.state)
        assertEquals(GatewayAttemptState.LEASED, store!!.loadAttempts(job.jobId).single().state)
    }

    private fun records(
        jobState: GatewayJobState = GatewayJobState.LEASED,
        attemptState: GatewayAttemptState = GatewayAttemptState.LEASED
    ): Pair<GatewayJobRecord, GatewayAttemptRecord> {
        val job = GatewayJobRecord(
            jobId = "job-001",
            gatewayId = "gateway-001",
            canonicalDeviceId = "GLO-GD-001",
            queryProfile = "STANDARD",
            attemptNumber = 1,
            leaseExpiresAt = LEASE_EXPIRES,
            state = jobState,
            updatedAt = CREATED_AT
        )
        val attempt = GatewayAttemptRecord(
            attemptId = "attempt-001",
            jobId = job.jobId,
            attemptNumber = 1,
            state = attemptState,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
            gatewayId = job.gatewayId,
            leaseExpiresAt = job.leaseExpiresAt
        )
        return job to attempt
    }

    companion object {
        private const val CREATED_AT = "2026-11-16T07:00:00Z"
        private const val SENT_AT = "2026-11-16T07:01:00Z"
        private const val LEASE_EXPIRES = "2026-11-16T07:05:00Z"
    }
}
