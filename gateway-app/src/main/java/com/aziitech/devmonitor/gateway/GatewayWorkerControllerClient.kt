package com.aziitech.devmonitor.gateway

import com.aziitech.devmonitor.gateway.worker.AndroidGatewayLeaseTokenStore
import com.aziitech.devmonitor.gateway.worker.GatewayAttemptRecord
import com.aziitech.devmonitor.gateway.worker.GatewayAttemptState
import com.aziitech.devmonitor.gateway.worker.GatewayJobRecord
import com.aziitech.devmonitor.gateway.worker.GatewayJobState
import com.aziitech.devmonitor.gateway.worker.SqliteSmsChannelStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID

data class ClaimedGatewayWork(
    val jobId: String,
    val attemptId: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val attemptNumber: Int,
    val leaseExpiresAt: String
)

/**
 * Gateway-worker transport for the reference controller contract.
 * Uses the dedicated opaque Gateway bearer credential, never a Firebase user token.
 * This client only claims work and records state transitions; it does not send SMS.
 */
class GatewayWorkerControllerClient(
    baseUrl: String,
    private val gatewayTokenProvider: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient()
) {
    private val root = requireNotNull(baseUrl.trim().toHttpUrlOrNull()) {
        "Enter a valid controller URL"
    }

    init {
        require(root.isHttps) { "Gateway controller must use HTTPS" }
    }

    suspend fun claimAndPersist(
        gatewayId: String,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore
    ): ClaimedGatewayWork? {
        requireUuid(gatewayId)
        val response = post("v1/gateway/jobs/claim", JSONObject(), allowEmpty = true)
        val job = response.optJSONObject("job") ?: return null
        val jobId = job.getString("job_id").also(::requireUuid)
        val attemptId = job.getString("attempt_id").also(::requireUuid)
        val leaseToken = job.getString("lease_token")
        val deviceId = job.getString("canonical_device_id")
        val profile = job.getString("query_profile")
        val attemptNumber = job.getInt("attempt_number")
        val expiresAt = job.getString("lease_expires_at")
        require(leaseToken.isNotBlank() && deviceId.isNotBlank() && profile.isNotBlank() && attemptNumber > 0)

        val now = Instant.now().toString()
        val jobRecord = GatewayJobRecord(
            jobId = jobId,
            gatewayId = gatewayId,
            canonicalDeviceId = deviceId,
            queryProfile = profile,
            attemptNumber = attemptNumber,
            leaseExpiresAt = expiresAt,
            state = GatewayJobState.LEASED,
            updatedAt = now
        )
        val attemptRecord = GatewayAttemptRecord(
            attemptId = attemptId,
            jobId = jobId,
            attemptNumber = attemptNumber,
            state = GatewayAttemptState.LEASED,
            createdAt = now,
            updatedAt = now,
            gatewayId = gatewayId,
            leaseExpiresAt = expiresAt
        )

        // Persist the secret first. SQLite stores only the attempt reference, never the token.
        leaseTokens.put(attemptId, leaseToken)
        try {
            ledger.saveClaim(jobRecord, attemptRecord)
        } catch (failure: Throwable) {
            leaseTokens.remove(attemptId)
            throw failure
        }
        return ClaimedGatewayWork(jobId, attemptId, deviceId, profile, attemptNumber, expiresAt)
    }

    suspend fun markSending(
        jobId: String,
        attemptId: String,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore
    ) {
        requireUuid(jobId)
        requireUuid(attemptId)
        val token = requireLeaseToken(leaseTokens, attemptId)
        post(jobPath(jobId, "sending"), JSONObject()
            .put("attempt_id", attemptId)
            .put("lease_token", token))
        val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
        val now = Instant.now().toString()
        ledger.saveJobAndAttempt(
            job.copy(state = GatewayJobState.SENDING, updatedAt = now),
            attempt.copy(state = GatewayAttemptState.SENDING, sendStartedAt = now, updatedAt = now)
        )
    }

    suspend fun markAwaitingReply(
        jobId: String,
        attemptId: String,
        smsSentAt: String,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore
    ) {
        requireUuid(jobId)
        requireUuid(attemptId)
        val token = requireLeaseToken(leaseTokens, attemptId)
        post(jobPath(jobId, "awaiting-reply"), JSONObject()
            .put("attempt_id", attemptId)
            .put("lease_token", token)
            .put("sms_sent_at", smsSentAt))
        val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
        val now = Instant.now().toString()
        ledger.saveJobAndAttempt(
            job.copy(state = GatewayJobState.AWAITING_REPLY, updatedAt = now),
            attempt.copy(
                state = GatewayAttemptState.AWAITING_REPLY,
                sentAt = smsSentAt,
                updatedAt = now
            )
        )
    }

    suspend fun markAmbiguous(
        jobId: String,
        attemptId: String,
        reason: String,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore
    ) {
        requireUuid(jobId)
        requireUuid(attemptId)
        require(reason.isNotBlank())
        post(jobPath(jobId, "ambiguous"), JSONObject()
            .put("attempt_id", attemptId)
            .put("reason", reason.take(160)))
        val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
        val now = Instant.now().toString()
        ledger.saveJobAndAttempt(
            job.copy(state = GatewayJobState.AMBIGUOUS, updatedAt = now),
            attempt.copy(
                state = GatewayAttemptState.AMBIGUOUS,
                errorMessage = reason.take(160),
                updatedAt = now
            )
        )
        leaseTokens.remove(attemptId)
    }


    suspend fun submitResult(
        jobId: String,
        attemptId: String,
        leaseTokenStore: AndroidGatewayLeaseTokenStore,
        ledger: SqliteSmsChannelStateStore,
        businessDate: String,
        checkedAt: String,
        receivedAt: String,
        values: JSONObject,
        parserVersion: String
    ) {
        requireUuid(jobId)
        requireUuid(attemptId)
        require(businessDate.isNotBlank() && checkedAt.isNotBlank() && receivedAt.isNotBlank() && parserVersion.isNotBlank())
        val token = requireLeaseToken(leaseTokenStore, attemptId)
        post(jobPath(jobId, "result"), JSONObject()
            .put("attempt_id", attemptId)
            .put("lease_token", token)
            .put("business_date", businessDate)
            .put("checked_at", checkedAt)
            .put("received_at", receivedAt)
            .put("values", values)
            .put("parser_version", parserVersion))
        val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
        val now = Instant.now().toString()
        ledger.saveJobAndAttempt(
            job.copy(state = GatewayJobState.COMPLETED, updatedAt = now),
            attempt.copy(
                state = GatewayAttemptState.COMPLETED,
                completedAt = now,
                resultJson = values.toString(),
                updatedAt = now
            )
        )
        leaseTokenStore.remove(attemptId)
    }

    private suspend fun requireLeaseToken(
        store: AndroidGatewayLeaseTokenStore,
        attemptId: String
    ): String = store.get(attemptId)
        ?: throw IllegalStateException("The secure lease token for this attempt is unavailable")

    private fun requireLocalPair(
        ledger: SqliteSmsChannelStateStore,
        jobId: String,
        attemptId: String
    ): Pair<GatewayJobRecord, GatewayAttemptRecord> {
        val job = ledger.loadJob(jobId)
            ?: throw IllegalStateException("Claimed Gateway job is missing from durable storage")
        val attempt = ledger.loadAttempts(jobId).firstOrNull { it.attemptId == attemptId }
            ?: throw IllegalStateException("Claimed Gateway attempt is missing from durable storage")
        return job to attempt
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        allowEmpty: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = gatewayTokenProvider()
        if (token.isBlank()) throw IllegalStateException("Gateway credential is unavailable")
        val url = root.newBuilder().addPathSegments(path).build()
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gateway controller returned HTTP ${response.code}: ${raw.take(240)}")
            }
            if (raw.isBlank() && allowEmpty) JSONObject() else JSONObject(raw)
        }
    }

    private fun jobPath(jobId: String, action: String): String =
        "v1/gateway/jobs/$jobId/$action"

    private fun requireUuid(value: String) {
        try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Controller returned an invalid identifier", failure)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
