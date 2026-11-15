package com.aziitech.devmonitor.gateway.worker

/**
 * Identifies the 3030 service short code without accepting ordinary phone numbers.
 *
 * Android may expose a short code with a leading plus sign or separators. After
 * removing only those presentation characters, the complete address must be
 * exactly "3030". This deliberately does not accept suffix/prefix matches.
 */
object SmsSenderPolicy {
    fun is3030(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val normalized = address.trim().filter { it.isDigit() }
        return normalized == "3030"
    }
}
