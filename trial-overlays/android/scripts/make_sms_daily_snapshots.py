from pathlib import Path
import re

p = Path("SmsCheckerDailyHistoryStore.kt")
s = p.read_text(encoding="utf-8")

# Existing installations may contain several attempts for one device/date.
# Keep the latest stored row before switching to the daily snapshot model.
anchor = """    init {
        pruneOldMonths(writableDatabase)
    }"""
replacement = """    init {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM sms_checker_daily_results WHERE id NOT IN (SELECT MAX(id) FROM sms_checker_daily_results GROUP BY activity_date, UPPER(code))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sms_history_one_daily_result ON sms_checker_daily_results(activity_date, code)")
            pruneOldMonths(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }"""
if anchor in s:
    s = s.replace(anchor, replacement, 1)
elif "idx_sms_history_one_daily_result" not in s:
    raise SystemExit("SMS history store init anchor not found.")

start = s.index("    fun recordCompleted(")
end = s.index("    /** Import the limited legacy", start)
method = """    /** Keep exactly one latest completed SMS result per device and local calendar date. */
    fun recordCompleted(record: QueryRecord, completedAtOverride: Long = record.completedAt) {
        if (record.status !in setOf(
                AttemptStatus.SUCCESS, AttemptStatus.NOT_FOUND, AttemptStatus.TIMEOUT,
                AttemptStatus.SEND_FAILED, AttemptStatus.UNRECOGNIZED
            )) return
        val timestamp = completedAtOverride.takeIf { it > 0L } ?: System.currentTimeMillis()
        val activityDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val code = canonicalDeviceCode(record.code)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sms_checker_daily_results", "activity_date=? AND UPPER(code)=?",
                arrayOf(activityDate, code.uppercase(java.util.Locale.US)))
            db.execSQL(
                "INSERT INTO sms_checker_daily_results(activity_date,code,completed_at,record_json) VALUES(?,?,?,?)",
                arrayOf(activityDate, code, timestamp, recordToJson(record).toString())
            )
            pruneOldMonths(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

"""
s = s[:start] + method + s[end:]

# Legacy migration may contain multiple attempts. Process chronologically and retain the final row per day.
s = s.replace("legacyHistory.forEach { item ->", "legacyHistory.sortedBy { it.completedAt }.forEach { item ->", 1)
old = """                    db.execSQL(
                        "INSERT INTO sms_checker_daily_results(activity_date,code,completed_at,record_json) VALUES(?,?,?,?)",
                        arrayOf(activityDate, canonicalDeviceCode(record.code), timestamp, recordToJson(record).toString())
                    )"""
new = """                    val code = canonicalDeviceCode(record.code)
                    db.delete("sms_checker_daily_results", "activity_date=? AND UPPER(code)=?",
                        arrayOf(activityDate, code.uppercase(java.util.Locale.US)))
                    db.execSQL(
                        "INSERT INTO sms_checker_daily_results(activity_date,code,completed_at,record_json) VALUES(?,?,?,?)",
                        arrayOf(activityDate, code, timestamp, recordToJson(record).toString())
                    )"""
if old in s:
    s = s.replace(old, new, 1)
elif "legacyHistory.sortedBy" not in s:
    raise SystemExit("Legacy history migration insert anchor not found.")

# Return the latest daily snapshots as the source rows for the report-style table.
anchor = """    /** All completed checks for one device/partner code during the retained two-month window. */"""
insert = """    /** Latest saved result for each device/date in the retained two-month window. */
    fun allDailySnapshots(): List<SmsCheckerHistoryEvent> {
        pruneOldMonths(writableDatabase)
        val out = mutableListOf<SmsCheckerHistoryEvent>()
        readableDatabase.rawQuery(
            "SELECT activity_date,completed_at,record_json FROM sms_checker_daily_results ORDER BY activity_date ASC,code ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    SmsCheckerHistoryEvent(
                        cursor.getString(0), cursor.getLong(1),
                        jsonToRecord(JSONObject(cursor.getString(2)))
                    )
                }.getOrNull()?.let(out::add)
            }
        }
        return out
    }

""" + anchor
if "fun allDailySnapshots()" not in s:
    if anchor not in s:
        raise SystemExit("Daily snapshot insertion anchor not found.")
    s = s.replace(anchor, insert, 1)

p.write_text(s, encoding="utf-8")
print("SMS history store now upserts one latest snapshot per device/date and exposes daily table rows.")
