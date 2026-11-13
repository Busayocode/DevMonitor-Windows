from pathlib import Path

p = Path("app/src/main/java/com/aziitech/simregmonitor/MainActivity.kt")
if not p.is_file():
    raise SystemExit("MainActivity.kt not found; refusing to apply last-checked ordering patch.")
s = p.read_text(encoding="utf-8")

state_anchor = '        var currentIndex by remember { mutableIntStateOf(-1) }'
state_line = '        var lastCheckedSmsCode by remember { mutableStateOf(store.getSetting("sms_checker_last_checked_code").orEmpty().ifBlank { null }) }'
if state_line not in s:
    if s.count(state_anchor) != 1:
        raise SystemExit("SMS Checker state insertion point missing or ambiguous.")
    s = s.replace(state_anchor, state_anchor + "\n" + state_line, 1)

process_anchor = '                    processedCodes.add(code)'
process_addition = '''                    processedCodes.add(code)
                    // Keep the device currently being checked at the top; persist the
                    // last checked code so it remains easy to find after reopening SMS Checker.
                    lastCheckedSmsCode = code
                    store.setSetting("sms_checker_last_checked_code", code)
'''
if process_addition not in s:
    if s.count(process_anchor) != 1:
        raise SystemExit("SMS query processing anchor missing or ambiguous.")
    s = s.replace(process_anchor, process_addition.rstrip(), 1)

sort_anchor = '''                        val visibleCodes = codes.filter { it.contains(smsListSearchQuery.trim(), ignoreCase = true) }
                            .sortedWith(
                                compareBy<String> { recheckPriorityIndex[it] ?: Int.MAX_VALUE }
                                    .thenBy { if (selectedTab != 6 && Ownership.from(it) == Ownership.COMPANY) 0 else 1 }
                                    .thenBy { originalCodeIndex[it] ?: Int.MAX_VALUE }
                            )'''
sort_replacement = '''                        val visibleCodes = codes.filter { it.contains(smsListSearchQuery.trim(), ignoreCase = true) }
                            .sortedWith(
                                compareBy<String> { if (it.equals(lastCheckedSmsCode, ignoreCase = true)) 0 else 1 }
                                    .thenBy { recheckPriorityIndex[it] ?: Int.MAX_VALUE }
                                    .thenBy { if (selectedTab != 6 && Ownership.from(it) == Ownership.COMPANY) 0 else 1 }
                                    .thenBy { originalCodeIndex[it] ?: Int.MAX_VALUE }
                            )'''
if sort_replacement not in s:
    if s.count(sort_anchor) != 1:
        raise SystemExit("SMS result-list sorting block missing or ambiguous.")
    s = s.replace(sort_anchor, sort_replacement, 1)

if "sms_checker_last_checked_code" not in s or "equals(lastCheckedSmsCode, ignoreCase = true)" not in s:
    raise SystemExit("Last-checked device ordering verification failed.")
p.write_text(s, encoding="utf-8")
print("Applied persistent last-checked device priority to the SMS Checker result list.")
