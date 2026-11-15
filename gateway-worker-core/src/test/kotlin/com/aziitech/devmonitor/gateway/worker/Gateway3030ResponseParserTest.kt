package com.aziitech.devmonitor.gateway.worker

import org.junit.Assert.*
import org.junit.Test

class Gateway3030ResponseParserTest {
    private val valid = """
        15/11/2026 10:30
        Today's SIMReg Count: 12
        MTD SIMReg Count: 145
        Active: 89
        Bar 48: 3
        Bar 96: 1
        Duplicates: 2
        Monthly Target: 500
    """.trimIndent()

    @Test fun parsesAllDocumentedMetrics() {
        val parsed = Gateway3030ResponseParser.parse(valid)
        assertTrue(parsed is Gateway3030ParseResult.Valid)
        val value = (parsed as Gateway3030ParseResult.Valid).value
        assertEquals("15/11/2026 10:30", value.reportDateTime)
        assertEquals("12", value.todaySimRegCount.toPlainString())
        assertEquals("145", value.mtdSimRegCount.toPlainString())
        assertEquals("500", value.monthlyTarget.toPlainString())
    }

    @Test fun rejectsIncompleteMessages() {
        val result = Gateway3030ResponseParser.parse("Today's SIMReg Count: 12\nActive: 4")
        assertTrue(result is Gateway3030ParseResult.Invalid)
    }

    @Test fun rejectsInvalidOrNegativeMetricValues() {
        assertTrue(Gateway3030ResponseParser.parse(valid.replace("Active: 89", "Active: many")) is Gateway3030ParseResult.Invalid)
        assertTrue(Gateway3030ResponseParser.parse(valid.replace("Bar 48: 3", "Bar 48: -3")) is Gateway3030ParseResult.Invalid)
    }

    @Test fun rejectsDuplicateMetrics() {
        assertTrue(Gateway3030ResponseParser.parse(valid + "\nActive: 90") is Gateway3030ParseResult.Invalid)
    }

    @Test fun rejectsEmptyMessages() {
        assertTrue(Gateway3030ResponseParser.parse(" ") is Gateway3030ParseResult.Invalid)
    }
}
