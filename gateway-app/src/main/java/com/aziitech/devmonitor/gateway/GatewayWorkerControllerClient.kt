package com.aziitech.devmonitor.gateway

import com.aziitech.devmonitor.gateway.worker.AndroidGatewayLeaseTokenStore
import com.aziitech.devmonitor.gateway.worker.AndroidGatewayCredentialStore
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

data class RegisteredGateway(val gatewayId: String, val controllerBaseUrl: String)

data class GatewayHeartbeatState(val gatewayId: String, val healthState: String)

data class ControllerActiveAttempt(
    val jobId: String,
    val attemptId: String,
    val state: String,
    val leaseExpiresAt: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val localLeaseTokenAvailable: Boolean
)

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

    /** Register this installation using the signed-in Firebase user token. The returned opaque token is stored encrypted. */
    suspend fun registerGateway(
        displayName: String,
        firebaseIdToken: String,
        credentialStore: AndroidGatewayCredentialStore
    ): RegisteredGateway = withContext(Dispatchers.IO) {
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(firebaseIdToken.isNotBlank())
        val response = postWithBearer(
            "v1/client/gateways",
            JSONObject().put("display_name", displayName.trim()),
            firebaseIdToken
        )
        val gatewayId = response.getString("gateway_id").also(::requireUuid)
        val gatewayToken = response.getString("gateway_token")
        require(gatewayToken.length >= 32) { "Controller returned an invalid Gateway credential" }
        credentialStore.save(gatewayId, gatewayToken, root.toString())
        RegisteredGateway(gatewayId, root.toString())
    }

    /** Report real phone readiness; do not claim SMS readiness until Android permissions/SIM checks exist. */
    suspend fun heartbeat(
        appVersion: String,
        smsPermissionReady: Boolean,
        telephonyReady: Boolean,
        networkReady: Boolean,
        lastSuccessful3030At: String? = null
    ): GatewayHeartbeatState {
        require(appVersion.isNotBlank() && appVersion.length <= 40)
        val body = JSONObject()
            .put("app_version", appVersion)
            .put("sms_permission_ready", smsPermissionReady)
            .put("telephony_ready", telephonyReady)
            .put("network_ready", networkReady)
        if (!lastSuccessful3030At.isNullOrBlank()) body.put("last_successful_3030_at", lastSuccessful3030At)
        val response = post("v1/gateway/heartbeat", body)
        return GatewayHeartbeatState(
            response.getString("gateway_id").also(::requireUuid),
            response.getString("health_state")
        )
    }

    /** Read server-side active work and compare it with locally retained lease material.
     * The server deliberately never returns lease tokens; missing local tokens require safe reconciliation.
     */
    suspend fun fetchActiveAttempt(
        leaseTokens: AndroidGatewayLeaseTokenStore
    ): ControllerActiveAttempt? {
        val response = get("v1/gateway/active-attempt")
        val active = response.optJSONObject("active_attempt") ?: return null
        val attemptId = active.getString("attempt_id").also(::requireUuid)
        return ControllerActiveAttempt(
            jobId = active.getString("job_id").also(::requireUuid),
            attemptId = attemptId,
            state = active.getString("state"),
            leaseExpiresAt = active.getString("lease_expires_at"),
            canonicalDeviceId = active.getString("canonical_device_id"),
            queryProfile = active.getString("query_profile"),
            localLeaseTokenAvailable = withContext(Dispatchers.IO) { leaseTokens.get(attemptId) != null }
        )
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
        withContext(Dispatchers.IO) {
            leaseTokens.put(attemptId, leaseToken)
            try {
                ledger.saveClaim(jobRecord, attemptRecord)
            } catch (failure: Throwable) {
                leaseTokens.remove(attemptId)
                throw failure
            }
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
        withContext(Dispatchers.IO) {
            val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
            val now = Instant.now().toString()
            ledger.saveJobAndAttempt(
                job.copy(state = GatewayJobState.SENDING, updatedAt = now),
                attempt.copy(state = GatewayAttemptState.SENDING, sendStartedAt = now, updatedAt = now)
            )
        }
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
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
    }


    /** Cancel only the exact controller lease. The server response is persisted locally before this returns. */
    suspend fun cancelAttempt(
        jobId: String,
        attemptId: String,
        reason: String,
        ledger: SqliteSmsChannelStateStore,
        leaseTokens: AndroidGatewayLeaseTokenStore,
        transactionGate: com.aziitech.devmonitor.gateway.worker.GatewaySmsTransactionGate
    ): GatewayCancellationResult {
        requireUuid(jobId)
        requireUuid(attemptId)
        require(reason.isNotBlank() && reason.length <= 240)
        val token = requireLeaseToken(leaseTokens, attemptId)
        val response = post(jobPath(jobId, "cancel"), JSONObject()
            .put("attempt_id", attemptId)
            .put("lease_token", token)
            .put("reason", reason))
        val returnedJobId = response.getString("job_id").also(::requireUuid)
        val returnedAttemptId = response.getString("attempt_id").also(::requireUuid)
        check(returnedJobId == jobId && returnedAttemptId == attemptId) {
            "Controller cancellation response did not match the requested attempt"
        }
        val outcome = response.getString("outcome")
        val cooldownUntil = if (response.isNull("cooldown_until") || !response.has("cooldown_until")) {
            null
        } else {
            Instant.parse(response.getString("cooldown_until"))
        }
        val cooldownSeconds = response.optInt("cooldown_seconds", 0)
        if (outcome == "CANCELLED_AFTER_SEND") {
            check(cooldownUntil != null && cooldownSeconds == 240) {
                "Controller returned an invalid cancellation cooldown"
            }
        }

        // Persist the server's exact deadline before reporting cancellation success.
        transactionGate.applyControllerCancellation(attemptId, outcome, cooldownUntil)
        withContext(Dispatchers.IO) {
            val (job, attempt) = requireLocalPair(ledger, jobId, attemptId)
            when (outcome) {
                "CANCELLED_BEFORE_SEND", "CANCELLED_AFTER_SEND" -> {
                    ledger.saveJobAndAttempt(
                        job.copy(state = GatewayJobState.CANCELLED, updatedAt = Instant.now().toString()),
                        attempt.copy(
                            state = GatewayAttemptState.REJECTED,
                            completedAt = Instant.now().toString(),
                            errorCode = outcome,
                            errorMessage = reason.take(160),
                            updatedAt = Instant.now().toString()
                        )
                    )
                    leaseTokens.remove(attemptId)
                }
                "BLOCKED_QUARANTINED" -> {
                    ledger.saveJobAndAttempt(
                        job.copy(state = GatewayJobState.AMBIGUOUS, updatedAt = Instant.now().toString()),
                        attempt.copy(
                            state = GatewayAttemptState.AMBIGUOUS,
                            errorCode = outcome,
                            errorMessage = reason.take(160),
                            updatedAt = Instant.now().toString()
                        )
                    )
                }
                else -> throw IllegalStateException("Controller returned an unknown cancellation outcome")
            }
        }
        return GatewayCancellationResult(outcome, cooldownUntil, cooldownSeconds)
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
        withContext(Dispatchers.IO) {
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
        postWithBearer(path, body, token, allowEmpty)
    }

    private fun postWithBearer(
        path: String,
        body: JSONObject,
        bearer: String,
        allowEmpty: Boolean = false
    ): JSONObject {
        val url = root.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gateway controller returned HTTP ${response.code}: ${raw.take(240)}")
            }
            if (raw.isBlank() && allowEmpty) JSONObject() else JSONObject(raw)
        }
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val token = gatewayTokenProvider()
        if (token.isBlank()) throw IllegalStateException("Gateway credential is unavailable")
        val url = root.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gateway controller returned HTTP ${response.code}: ${raw.take(240)}")
            }
            JSONObject(raw)
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


data class GatewayCancellationResult(
    val outcome: String,
    val cooldownUntil: Instant?,
    val cooldownSeconds: Int
)
