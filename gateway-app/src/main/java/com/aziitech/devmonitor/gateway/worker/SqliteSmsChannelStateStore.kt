package com.aziitech.devmonitor.gateway.worker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class GatewayJobState { QUEUED, RETRY_PENDING, LEASED, SENDING, AWAITING_REPLY, AMBIGUOUS, COMPLETED, FAILED, CANCELLED }
enum class GatewayAttemptState { LEASED, SENDING, AWAITING_REPLY, COMPLETED, SEND_FAILED, TIMED_OUT, AMBIGUOUS, REJECTED }

data class GatewayJobRecord(
    val jobId: String,
    val gatewayId: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val attemptNumber: Int,
    val leaseExpiresAt: String,
    val state: GatewayJobState,
    val updatedAt: String
)

data class GatewayAttemptRecord(
    val attemptId: String,
    val jobId: String,
    val attemptNumber: Int,
    val state: GatewayAttemptState,
    val createdAt: String,
    val updatedAt: String,
    val gatewayId: String,
    val leaseExpiresAt: String,
    val transactionId: String? = null,
    val sendStartedAt: String? = null,
    val sentAt: String? = null,
    val responseDeadline: String? = null,
    val completedAt: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val resultJson: String? = null
)

/**
 * Durable Android adapter for the pure Kotlin worker-core state contract.
 *
 * A single-row snapshot is replaced inside one SQLite transaction so a process
 * interruption cannot leave a partially-written channel state. The worker core
 * remains Android-free; only this app-layer adapter knows about SQLite.
 */
class SqliteSmsChannelStateStore(context: Context) : SmsChannelStateStore {
    private val helper = StateDatabase(context.applicationContext)

    @Synchronized
    override fun load(): SmsChannelSnapshot {
        val db = helper.readableDatabase
        db.rawQuery("SELECT snapshot_json FROM sms_channel_state WHERE singleton_id = 1", null).use { cursor ->
            if (!cursor.moveToFirst()) return SmsChannelSnapshot()
            return decode(cursor.getString(0))
        }
    }

    @Synchronized
    override fun save(snapshot: SmsChannelSnapshot) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("singleton_id", 1)
            put("snapshot_json", encode(snapshot))
        }
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "sms_channel_state",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }


    /** Atomically stores a controller job claim and its first attempt. */
    @Synchronized
    fun saveClaim(job: GatewayJobRecord, attempt: GatewayAttemptRecord) {
        require(job.jobId.isNotBlank() && job.gatewayId.isNotBlank() && job.canonicalDeviceId.isNotBlank())
        require(attempt.attemptId.isNotBlank() && attempt.jobId == job.jobId && attempt.gatewayId == job.gatewayId && attempt.attemptNumber == job.attemptNumber && attempt.attemptNumber > 0)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val prior = db.rawQuery(
                "SELECT record_json FROM gateway_attempts WHERE attempt_id = ?",
                arrayOf(attempt.attemptId)
            ).use { cursor -> if (cursor.moveToFirst()) decodeAttempt(cursor.getString(0)) else null }
            require(prior == null || prior.jobId == attempt.jobId) {
                "Attempt ID is already associated with another job"
            }
            putRecord(db, "gateway_jobs", "job_id", job.jobId, null, encodeJob(job))
            putRecord(db, "gateway_attempts", "attempt_id", attempt.attemptId, attempt.jobId, encodeAttempt(attempt))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Atomically advances the durable job and attempt lifecycle together. */
    @Synchronized
    fun saveJobAndAttempt(job: GatewayJobRecord, attempt: GatewayAttemptRecord) {
        require(job.jobId.isNotBlank() && job.gatewayId.isNotBlank())
        require(attempt.attemptId.isNotBlank())
        require(job.jobId == attempt.jobId) { "Job and attempt IDs must match" }
        require(job.gatewayId == attempt.gatewayId) { "Job and attempt must belong to the same Gateway" }
        require(job.attemptNumber == attempt.attemptNumber && attempt.attemptNumber > 0) {
            "Job and attempt numbers must match and be positive"
        }
        require(job.leaseExpiresAt == attempt.leaseExpiresAt) {
            "Job and attempt lease expiries must match"
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            putRecord(db, "gateway_jobs", "job_id", job.jobId, null, encodeJob(job))
            putRecord(db, "gateway_attempts", "attempt_id", attempt.attemptId, attempt.jobId, encodeAttempt(attempt))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Applies an Android SMS_SENT callback exactly once to the matching durable
     * attempt. Late, duplicate, or out-of-order callbacks cannot reopen a job.
     */
    @Synchronized
    fun recordSendResult(attemptId: String, androidResultCode: Int, sentAt: String): Boolean {
        require(attemptId.isNotBlank() && sentAt.isNotBlank())
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val attempt = db.rawQuery(
                "SELECT record_json FROM gateway_attempts WHERE attempt_id = ?",
                arrayOf(attemptId)
            ).use { cursor -> if (cursor.moveToFirst()) decodeAttempt(cursor.getString(0)) else null }
                ?: return false
            if (attempt.state != GatewayAttemptState.SENDING) return false
            val job = db.rawQuery(
                "SELECT record_json FROM gateway_jobs WHERE job_id = ?",
                arrayOf(attempt.jobId)
            ).use { cursor -> if (cursor.moveToFirst()) decodeJob(cursor.getString(0)) else null }
                ?: return false
            if (job.state != GatewayJobState.SENDING) return false

            val succeeded = androidResultCode == android.app.Activity.RESULT_OK
            val nextAttempt = attempt.copy(
                state = if (succeeded) GatewayAttemptState.AWAITING_REPLY else GatewayAttemptState.SEND_FAILED,
                updatedAt = sentAt,
                sentAt = if (succeeded) sentAt else attempt.sentAt,
                errorCode = if (succeeded) null else "ANDROID_SEND_RESULT_$androidResultCode",
                errorMessage = if (succeeded) null else "Android SMS_SENT callback reported failure"
            )
            val nextJob = job.copy(
                state = if (succeeded) GatewayJobState.AWAITING_REPLY else GatewayJobState.FAILED,
                updatedAt = sentAt
            )
            putRecord(db, "gateway_jobs", "job_id", nextJob.jobId, null, encodeJob(nextJob))
            putRecord(db, "gateway_attempts", "attempt_id", nextAttempt.attemptId, nextAttempt.jobId, encodeAttempt(nextAttempt))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadJob(jobId: String): GatewayJobRecord? =
        helper.readableDatabase.rawQuery(
            "SELECT record_json FROM gateway_jobs WHERE job_id = ?",
            arrayOf(jobId)
        ).use { cursor -> if (cursor.moveToFirst()) decodeJob(cursor.getString(0)) else null }

    @Synchronized
    fun loadAttempts(jobId: String): List<GatewayAttemptRecord> = buildList {
        helper.readableDatabase.rawQuery(
            "SELECT record_json FROM gateway_attempts WHERE job_id = ? ORDER BY attempt_number ASC",
            arrayOf(jobId)
        ).use { cursor ->
            while (cursor.moveToNext()) add(decodeAttempt(cursor.getString(0)))
        }
    }

    private fun putRecord(
        db: SQLiteDatabase,
        table: String,
        key: String,
        id: String,
        jobId: String?,
        json: String
    ) {
        val values = ContentValues().apply {
            put(key, id)
            if (jobId != null) put("job_id", jobId)
            put("record_json", json)
            if (table == "gateway_attempts") put("attempt_number", JSONObject(json).getInt("attemptNumber"))
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encodeJob(r: GatewayJobRecord): String = JSONObject()
        .put("jobId", r.jobId).put("gatewayId", r.gatewayId)
        .put("canonicalDeviceId", r.canonicalDeviceId).put("queryProfile", r.queryProfile)
        .put("attemptNumber", r.attemptNumber).put("leaseExpiresAt", r.leaseExpiresAt)
        .put("state", r.state.name).put("updatedAt", r.updatedAt).toString()

    private fun decodeJob(raw: String): GatewayJobRecord {
        val j = JSONObject(raw)
        return GatewayJobRecord(
            j.getString("jobId"), j.getString("gatewayId"), j.getString("canonicalDeviceId"),
            j.getString("queryProfile"), j.getInt("attemptNumber"), j.getString("leaseExpiresAt"),
            GatewayJobState.valueOf(j.getString("state")), j.getString("updatedAt")
        )
    }

    private fun encodeAttempt(r: GatewayAttemptRecord): String = JSONObject()
        .put("attemptId", r.attemptId).put("jobId", r.jobId)
        .put("attemptNumber", r.attemptNumber).put("state", r.state.name)
        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
        .put("gatewayId", r.gatewayId).put("leaseExpiresAt", r.leaseExpiresAt)
        .put("transactionId", r.transactionId ?: JSONObject.NULL)
        .put("sendStartedAt", r.sendStartedAt ?: JSONObject.NULL)
        .put("sentAt", r.sentAt ?: JSONObject.NULL)
        .put("responseDeadline", r.responseDeadline ?: JSONObject.NULL)
        .put("completedAt", r.completedAt ?: JSONObject.NULL)
        .put("errorCode", r.errorCode ?: JSONObject.NULL)
        .put("errorMessage", r.errorMessage ?: JSONObject.NULL)
        .put("resultJson", r.resultJson ?: JSONObject.NULL).toString()

    private fun decodeAttempt(raw: String): GatewayAttemptRecord {
        val j = JSONObject(raw)
        return GatewayAttemptRecord(
            attemptId = j.getString("attemptId"), jobId = j.getString("jobId"),
            attemptNumber = j.getInt("attemptNumber"),
            state = GatewayAttemptState.valueOf(j.getString("state")),
            createdAt = j.getString("createdAt"), updatedAt = j.getString("updatedAt"),
            gatewayId = j.getString("gatewayId"), leaseExpiresAt = j.getString("leaseExpiresAt"),
            transactionId = j.nullable("transactionId"), sendStartedAt = j.nullable("sendStartedAt"),
            sentAt = j.nullable("sentAt"), responseDeadline = j.nullable("responseDeadline"),
            completedAt = j.nullable("completedAt"), errorCode = j.nullable("errorCode"),
            errorMessage = j.nullable("errorMessage"), resultJson = j.nullable("resultJson")
        )
    }

    private fun JSONObject.nullable(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)


    /** Returns every locally unresolved dispatch so restart recovery can detect orphaned work. */
    @Synchronized
    fun loadUnresolvedDispatches(): List<GatewayUnresolvedDispatch> {
        val db = helper.readableDatabase
        val jobs = db.rawQuery("SELECT record_json FROM gateway_jobs", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(decodeJob(cursor.getString(0)))
            }
        }.associateBy { it.jobId }
        val attempts = db.rawQuery("SELECT record_json FROM gateway_attempts", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(decodeAttempt(cursor.getString(0)))
            }
        }
        val unresolvedStates = setOf(
            GatewayAttemptState.LEASED,
            GatewayAttemptState.SENDING,
            GatewayAttemptState.AWAITING_REPLY,
            GatewayAttemptState.SEND_FAILED,
            GatewayAttemptState.TIMED_OUT,
            GatewayAttemptState.AMBIGUOUS
        )
        return attempts.asSequence()
            .filter { it.state in unresolvedStates }
            .mapNotNull { attempt ->
                val job = jobs[attempt.jobId] ?: return@mapNotNull null
                GatewayUnresolvedDispatch(job, attempt)
            }
            .toList()
    }

    fun close() = helper.close()

    private fun encode(snapshot: SmsChannelSnapshot): String {
        val json = JSONObject()
            .put("state", snapshot.state.name)
            .put("drainUntil", snapshot.drainUntil?.toString() ?: JSONObject.NULL)
            .put("lastResolutionNote", snapshot.lastResolutionNote ?: JSONObject.NULL)
            .put("seenMessageFingerprints", JSONArray(snapshot.seenMessageFingerprints.toList()))

        val tx = snapshot.transaction
        if (tx == null) {
            json.put("transaction", JSONObject.NULL)
        } else {
            json.put("transaction", JSONObject()
                .put("transactionId", tx.transactionId)
                .put("jobId", tx.jobId)
                .put("attemptId", tx.attemptId)
                .put("canonicalDeviceId", tx.canonicalDeviceId)
                .put("queryProfile", tx.queryProfile)
                .put("startedAt", tx.startedAt.toString())
                .put("responseDeadline", tx.responseDeadline.toString()))
        }
        return json.toString()
    }

    private fun decode(raw: String): SmsChannelSnapshot {
        val json = JSONObject(raw)
        val state = SmsChannelState.valueOf(json.getString("state"))
        val txJson = json.optJSONObject("transaction")
        val transaction = txJson?.let {
            SmsTransaction(
                transactionId = it.getString("transactionId"),
                jobId = it.getString("jobId"),
                attemptId = it.getString("attemptId"),
                canonicalDeviceId = it.getString("canonicalDeviceId"),
                queryProfile = it.getString("queryProfile"),
                startedAt = Instant.parse(it.getString("startedAt")),
                responseDeadline = Instant.parse(it.getString("responseDeadline"))
            )
        }
        val fingerprints = buildSet {
            val array = json.optJSONArray("seenMessageFingerprints") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return SmsChannelSnapshot(
            state = state,
            transaction = transaction,
            drainUntil = json.optNullableString("drainUntil")?.let(Instant::parse),
            seenMessageFingerprints = fingerprints,
            lastResolutionNote = json.optNullableString("lastResolutionNote")
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private class StateDatabase(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE sms_channel_state (
                    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    snapshot_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            createWorkTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createWorkTables(db)
        }

        private fun createWorkTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS gateway_jobs (job_id TEXT PRIMARY KEY NOT NULL, record_json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS gateway_attempts (attempt_id TEXT PRIMARY KEY NOT NULL, job_id TEXT NOT NULL, attempt_number INTEGER NOT NULL, record_json TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_gateway_attempts_job ON gateway_attempts(job_id, attempt_number)")
        }

        companion object {
            private const val DATABASE_NAME = "devmonitor_gateway_worker.db"
            private const val DATABASE_VERSION = 2
        }
    }
}
