package com.aziitech.devmonitor.gateway.worker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class StoredGatewayInboundSms(
    val fingerprint: String,
    val sender: String,
    val receivedAtMillis: Long,
    val subscriptionId: Int?,
    val body: String,
    val processingState: String
)

/**
 * Private, bounded inbox for verified 3030 messages.
 *
 * This intake layer never assigns a reply to a device/job and never submits a
 * controller result. Correlation is a separate worker-core gate. Only 3030
 * short-code messages are stored; unrelated personal SMS are not copied.
 */
class GatewayInboundSmsStore(context: Context) {
    private val helper = InboxDatabase(context.applicationContext)

    @Synchronized
    fun record3030(
        sender: String,
        receivedAtMillis: Long,
        subscriptionId: Int?,
        body: String
    ): Boolean {
        require(SmsSenderPolicy.is3030(sender)) { "Only the exact 3030 sender is accepted" }
        require(receivedAtMillis > 0 && body.isNotBlank())
        val fingerprint = fingerprint(sender, receivedAtMillis, body)
        val values = ContentValues().apply {
            put("fingerprint", fingerprint)
            put("sender", "3030")
            put("received_at", receivedAtMillis)
            if (subscriptionId == null || subscriptionId < 0) putNull("subscription_id")
            else put("subscription_id", subscriptionId)
            put("body", body)
            put("processing_state", "RECEIVED_UNASSIGNED")
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(
                "inbound_3030",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            db.execSQL(
                "DELETE FROM inbound_3030 WHERE row_id NOT IN " +
                    "(SELECT row_id FROM inbound_3030 ORDER BY received_at DESC, row_id DESC LIMIT ?)",
                arrayOf(MAX_ROWS)
            )
            db.setTransactionSuccessful()
            return inserted != -1L
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markDecision(fingerprint: String, state: String, note: String? = null): Boolean {
        require(fingerprint.isNotBlank())
        require(state in setOf(
            "ACCEPTED_PENDING_CONTROLLER", "UNASSIGNED_INVALID", "UNASSIGNED_NO_TRANSACTION",
            "UNASSIGNED_LATE", "UNASSIGNED_DRAINING", "DUPLICATE", "QUARANTINED"
        ))
        val values = ContentValues().apply {
            put("processing_state", state)
            if (note.isNullOrBlank()) putNull("decision_note") else put("decision_note", note.take(240))
        }
        return helper.writableDatabase.update(
            "inbound_3030", values, "fingerprint = ?", arrayOf(fingerprint)
        ) == 1
    }

    @Synchronized
    fun loadUnassigned(limit: Int = 50): List<StoredGatewayInboundSms> {
        val safeLimit = limit.coerceIn(1, MAX_ROWS)
        val rows = ArrayList<StoredGatewayInboundSms>()
        helper.readableDatabase.rawQuery(
            "SELECT fingerprint, sender, received_at, subscription_id, body, processing_state " +
                "FROM inbound_3030 WHERE processing_state = 'RECEIVED_UNASSIGNED' " +
                "ORDER BY received_at ASC, row_id ASC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += StoredGatewayInboundSms(
                    fingerprint = cursor.getString(0),
                    sender = cursor.getString(1),
                    receivedAtMillis = cursor.getLong(2),
                    subscriptionId = if (cursor.isNull(3)) null else cursor.getInt(3),
                    body = cursor.getString(4),
                    processingState = cursor.getString(5)
                )
            }
        }
        return rows
    }

    fun close() = helper.close()

    private fun fingerprint(sender: String, receivedAt: Long, body: String): String {
        val bytes = "$sender\u0000$receivedAt\u0000$body".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private class InboxDatabase(context: Context) : SQLiteOpenHelper(
        context, DATABASE_NAME, null, DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE inbound_3030 (
                    row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fingerprint TEXT NOT NULL UNIQUE,
                    sender TEXT NOT NULL,
                    received_at INTEGER NOT NULL,
                    subscription_id INTEGER,
                    body TEXT NOT NULL,
                    processing_state TEXT NOT NULL,
                    decision_note TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_inbound_3030_state_time ON inbound_3030(processing_state, received_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE inbound_3030 ADD COLUMN decision_note TEXT")
            }
        }

        companion object {
            private const val DATABASE_NAME = "devmonitor_gateway_inbox.db"
            private const val DATABASE_VERSION = 2
            const val MAX_ROWS = 200
        }
    }

    private companion object {
        const val MAX_ROWS = 200
    }
}
