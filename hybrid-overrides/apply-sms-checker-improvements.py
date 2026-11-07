from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

def replace_exact(old, new, expected=1):
    global s
    count = s.count(old)
    if count != expected:
        raise SystemExit(f"Expected {expected} occurrence(s), found {count}: {old[:100]!r}")
    s = s.replace(old, new, expected)

replace_exact("private const val DEFAULT_TIMEOUT_MS = 120_000L\n",
              "private const val DEFAULT_TIMEOUT_MS = 120_000L\nprivate const val PARTNER_TIMEOUT_MS = 180_000L\n")
replace_exact("        val activeRunCodes = remember { mutableStateListOf<String>() }\n",
              "        val activeRunCodes = remember { mutableStateListOf<String>() }\n"
              "        // Temporary display priority for devices explicitly selected for Recheck.\n"
              "        // It changes only the visible ordering, never the stored device list or run order.\n"
              "        val recheckPriorityCodes = remember { mutableStateListOf<String>() }\n")
replace_exact("""                if (additions.isNotEmpty()) {
                    pendingRecheckCodes.addAll(additions)
                    additions.forEach { queuedRecheckRetryModes[it] = false }
                    activeRunCodes.addAll(additions)
""",
"""                if (additions.isNotEmpty()) {
                    pendingRecheckCodes.addAll(additions)
                    additions.forEach { queuedRecheckRetryModes[it] = false }
                    recheckPriorityCodes.removeAll(additions.toSet())
                    recheckPriorityCodes.addAll(0, additions)
                    activeRunCodes.addAll(additions)
""")
replace_exact("""            runJob = scope.launch {
                val initialCodes = requestedCodes
""",
"""            runJob = scope.launch {
                val requestTimeoutMs = if (selectedTab == 6) PARTNER_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
                val timeoutMinutes = requestTimeoutMs / 60_000L
                val initialCodes = requestedCodes
""")
replace_exact("Waiting up to 2 minutes: $code", "Waiting up to ${timeoutMinutes} minutes: $code", 2)
replace_exact("System.currentTimeMillis() - start < DEFAULT_TIMEOUT_MS &&\n                        matched == null",
              "System.currentTimeMillis() - start < requestTimeoutMs &&\n                        matched == null")
replace_exact("secondsLeft = ((DEFAULT_TIMEOUT_MS - elapsed + 999) / 1000).coerceAtLeast(0)",
              "secondsLeft = ((requestTimeoutMs - elapsed + 999) / 1000).coerceAtLeast(0)")
replace_exact("No valid response within 2 minutes. SMS delivery reporting indicated a sending/delivery problem; immediate retries were attempted.",
              "No valid response within ${timeoutMinutes} minutes. SMS delivery reporting indicated a sending/delivery problem; immediate retries were attempted.")
replace_exact("No valid response within 2 minutes", "No valid response within ${timeoutMinutes} minutes")
replace_exact("""                activeRunCodes.clear()
                running = false
                if (userScopedNoticePending) userScopedNoticeBusy = false
""",
"""                activeRunCodes.clear()
                recheckPriorityCodes.clear()
                running = false
                if (userScopedNoticePending) userScopedNoticeBusy = false
""")
replace_exact("""            activeRunCodes.clear()
            running = false
            waitingCode = null
""",
"""            activeRunCodes.clear()
            recheckPriorityCodes.clear()
            running = false
            waitingCode = null
""")
replace_exact("System.currentTimeMillis() - start < DEFAULT_TIMEOUT_MS && matched == null",
              "System.currentTimeMillis() - start < PARTNER_TIMEOUT_MS && matched == null")
replace_exact("Waiting for the 30-30 response…", "Waiting up to 3 minutes for the 30-30 response…")
replace_exact("No valid 30-30 partner summary response was received for $cleanTradeCode.",
              "No valid 30-30 partner summary response was received for $cleanTradeCode within 3 minutes.")
replace_exact("""                        val visibleCodes = codes.filter { it.contains(smsListSearchQuery.trim(), ignoreCase = true) }
""",
"""                        val recheckPriorityIndex = recheckPriorityCodes.withIndex().associate { it.value to it.index }
                        val originalCodeIndex = codes.withIndex().associate { it.value to it.index }
                        val visibleCodes = codes.filter { it.contains(smsListSearchQuery.trim(), ignoreCase = true) }
                            .sortedWith(
                                compareBy<String> { recheckPriorityIndex[it] ?: Int.MAX_VALUE }
                                    .thenBy { if (selectedTab != 6 && Ownership.from(it) == Ownership.COMPANY) 0 else 1 }
                                    .thenBy { originalCodeIndex[it] ?: Int.MAX_VALUE }
                            )
""")
replace_exact("""                                            onClick = { if (!permissionOk) requestSmsPermissions(checkedTodayCodes, false) else beginRun(checkedTodayCodes, false) },
""",
"""                                            onClick = {
                                                recheckPriorityCodes.clear()
                                                recheckPriorityCodes.addAll(checkedTodayCodes)
                                                if (!permissionOk) requestSmsPermissions(checkedTodayCodes, false) else beginRun(checkedTodayCodes, false)
                                            },
""")
replace_exact("""                                            TextButton(onClick = { if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false) }) { Text(if (selectedTab == 6) "Recheck partner code" else "Recheck this code") }
""",
"""                                            TextButton(onClick = {
                                                recheckPriorityCodes.clear()
                                                recheckPriorityCodes.add(code)
                                                if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false)
                                            }) { Text(if (selectedTab == 6) "Recheck partner code" else "Recheck this code") }
""")
replace_exact("""                                                onClick = { if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false) },
""",
"""                                                onClick = {
                                                    recheckPriorityCodes.clear()
                                                    recheckPriorityCodes.add(code)
                                                    if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false)
                                                },
""", 2)
replace_exact("""                                                        onClick = {
                                                            paymentGateMessage = null
                                                            paymentGateMessageCode = null
                                                            if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false)
                                                        },
""",
"""                                                        onClick = {
                                                            paymentGateMessage = null
                                                            paymentGateMessageCode = null
                                                            recheckPriorityCodes.clear()
                                                            recheckPriorityCodes.add(code)
                                                            if (!permissionOk) requestSmsPermissions(listOf(code), false) else beginRun(listOf(code), false)
                                                        },
""")
replace_exact("Sending Trade Code ${partnerSummaryCode} to $destination and waiting for the 30-30 summary…",
              "Sending Trade Code ${partnerSummaryCode} to $destination and waiting up to 3 minutes for the 30-30 summary…")

p.write_text(s)
print("Applied SMS Checker ordering, Recheck priority and partner 3-minute timeout.")
