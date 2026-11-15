package com.aziitech.devmonitor.gateway.worker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Preserve the durable snapshot. Future schema changes must use explicit migrations.
        }

        companion object {
            private const val DATABASE_NAME = "devmonitor_gateway_worker.db"
            private const val DATABASE_VERSION = 1
        }
    }
}
