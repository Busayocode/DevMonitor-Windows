package com.aziitech.simregmonitor

import java.time.YearMonth
import java.util.Locale

data class SmsConversionDeviceRow(val code: String, val mtd: Int?, val active: Int?, val loss: Int?, val conversionPercent: Double?, val completedAt: Long?)

data class SmsConversionSummary(val rows: List<SmsConversionDeviceRow>, val mtd: Int, val active: Int, val loss: Int, val conversionPercent: Double, val devicesWithResults: Int, val deviceCount: Int)

/** Current-calendar-month conversion based on each device's latest successful saved SMS return. */
object SmsConversionMetrics {
    fun calculate(events: List<SmsCheckerHistoryEvent>, deviceCodes: List<String>, month: YearMonth = YearMonth.now()): SmsConversionSummary {
        val wanted = deviceCodes.map(::canonicalDeviceCode).filter { it.isNotBlank() }.distinctBy { it.uppercase(Locale.US) }
        val latestByCode = events.asSequence()
            .filter { it.activityDate.startsWith(month.toString()) }
            .filter { it.record.status == AttemptStatus.SUCCESS }
            .mapNotNull { event ->
                val result = event.record.result ?: return@mapNotNull null
                val mtd = result.mtd ?: return@mapNotNull null
                val active = result.active ?: return@mapNotNull null
                val code = canonicalDeviceCode(event.record.code)
                if (code.isBlank()) return@mapNotNull null
                Triple(code.uppercase(Locale.US), event, Pair(mtd, active))
            }
            .groupBy { it.first }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.second.completedAt }!! }
        val rows = wanted.map { code ->
            val latest = latestByCode[code.uppercase(Locale.US)]
            if (latest == null) SmsConversionDeviceRow(code, null, null, null, null, null)
            else {
                val (mtd, active) = latest.third
                SmsConversionDeviceRow(code, mtd, active, mtd - active, if (mtd > 0) active * 100.0 / mtd else 0.0, latest.second.completedAt)
            }
        }
        val populated = rows.filter { it.mtd != null && it.active != null }
        val totalMtd = populated.sumOf { it.mtd ?: 0 }
        val totalActive = populated.sumOf { it.active ?: 0 }
        return SmsConversionSummary(rows, totalMtd, totalActive, totalMtd - totalActive,
            if (totalMtd > 0) totalActive * 100.0 / totalMtd else 0.0, populated.size, wanted.size)
    }
}
