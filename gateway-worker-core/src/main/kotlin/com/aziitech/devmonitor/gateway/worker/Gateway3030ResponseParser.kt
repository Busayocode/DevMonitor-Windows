package com.aziitech.devmonitor.gateway.worker

import java.math.BigDecimal

data class Gateway3030Result(
    val reportDateTime: String?,
    val todaySimRegCount: BigDecimal,
    val mtdSimRegCount: BigDecimal,
    val active: BigDecimal,
    val bar48: BigDecimal,
    val bar96: BigDecimal,
    val duplicates: BigDecimal,
    val monthlyTarget: BigDecimal
) {
    fun toCanonicalPayload(): String = listOf(
        "dateTime=${reportDateTime.orEmpty()}",
        "todaySimRegCount=${todaySimRegCount.toPlainString()}",
        "mtdSimRegCount=${mtdSimRegCount.toPlainString()}",
        "active=${active.toPlainString()}",
        "bar48=${bar48.toPlainString()}",
        "bar96=${bar96.toPlainString()}",
        "duplicates=${duplicates.toPlainString()}",
        "monthlyTarget=${monthlyTarget.toPlainString()}"
    ).joinToString(";")
}

sealed interface Gateway3030ParseResult {
    data class Valid(val value: Gateway3030Result) : Gateway3030ParseResult
    data class Invalid(val reason: String) : Gateway3030ParseResult
}

/**
 * Conservative parser for the documented 3030 report fields.
 *
 * It deliberately requires every numeric metric. Unknown or incomplete formats
 * are rejected rather than guessed. The date/time is retained as source text
 * because the carrier's exact date formatting is not yet fixed by protocol docs.
 */
object Gateway3030ResponseParser {
    private val labelPatterns = linkedMapOf(
        "todaySimRegCount" to listOf(
            Regex("""today(?:'s)?\s+simreg\s+count""", RegexOption.IGNORE_CASE),
            Regex("""today\s+count""", RegexOption.IGNORE_CASE)
        ),
        "mtdSimRegCount" to listOf(
            Regex("""mtd\s+simreg\s+count""", RegexOption.IGNORE_CASE),
            Regex("""month\s+to\s+date\s+simreg\s+count""", RegexOption.IGNORE_CASE)
        ),
        "active" to listOf(Regex("""active""", RegexOption.IGNORE_CASE)),
        "bar48" to listOf(Regex("""bar\s*48""", RegexOption.IGNORE_CASE)),
        "bar96" to listOf(Regex("""bar\s*96""", RegexOption.IGNORE_CASE)),
        "duplicates" to listOf(Regex("""duplicates?""", RegexOption.IGNORE_CASE)),
        "monthlyTarget" to listOf(Regex("""monthly\s+target""", RegexOption.IGNORE_CASE))
    )

    private val number = Regex("""^[+]?(?:\d+(?:[,.]\d+)?|[,.]\d+)%?$""")

    fun parse(body: String): Gateway3030ParseResult {
        if (body.isBlank()) return Gateway3030ParseResult.Invalid("Empty 3030 message")
        val found = mutableMapOf<String, BigDecimal>()
        var dateTime: String? = null

        body.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            // A date/time line may itself contain a colon (HH:mm). Detect it
            // before treating ':' as a metric label/value separator.
            if (dateTime == null &&
                (line.contains("/") || line.contains("-")) &&
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(line)
            ) {
                dateTime = line
                return@forEach
            }
            val separator = line.indexOfFirst { it == ':' || it == '=' }
            if (separator <= 0) return@forEach
            val label = line.substring(0, separator).trim()
            val rawValue = line.substring(separator + 1).trim().replace(",", "")
            val key = labelPatterns.entries.firstOrNull { (_, patterns) ->
                patterns.any { it.matches(label) }
            }?.key ?: return@forEach
            if (key in found) return Gateway3030ParseResult.Invalid("Duplicate metric: $label")
            val numericText = rawValue.removeSuffix("%").trim()
            if (!number.matches(rawValue) || numericText.isBlank()) {
                return Gateway3030ParseResult.Invalid("Invalid numeric value for $label")
            }
            val value = runCatching { BigDecimal(numericText) }.getOrNull()
                ?: return Gateway3030ParseResult.Invalid("Invalid numeric value for $label")
            if (value < BigDecimal.ZERO) {
                return Gateway3030ParseResult.Invalid("Negative metric: $label")
            }
            found[key] = value
        }

        val missing = labelPatterns.keys - found.keys
        if (missing.isNotEmpty()) {
            return Gateway3030ParseResult.Invalid("Missing required 3030 fields: ${missing.joinToString()}")
        }
        return Gateway3030ParseResult.Valid(
            Gateway3030Result(
                dateTime, found.getValue("todaySimRegCount"), found.getValue("mtdSimRegCount"),
                found.getValue("active"), found.getValue("bar48"), found.getValue("bar96"),
                found.getValue("duplicates"), found.getValue("monthlyTarget")
            )
        )
    }
}
