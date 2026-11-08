from pathlib import Path

p = Path("MainActivity.kt")
s = p.read_text(encoding="utf-8")

anchor = '        var showSmsHistory by remember { mutableStateOf(false) }'
if anchor not in s:
    raise SystemExit("SMS history state anchor not found.")
s = s.replace(anchor, anchor + '\n        var smsHistoryDeviceCode by remember { mutableStateOf<String?>(null) }', 1)

old = '''                                        if (flag != null) {
                                            AssistChip(onClick = { showFlags = true }, label = { Text(flag.severity) })
                                        }'''
new = '''                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            if (selectedTab != 6) {
                                                TextButton(
                                                    onClick = {
                                                        smsHistoryDeviceCode = code
                                                        showSmsHistory = true
                                                    },
                                                    enabled = !running
                                                ) { Text("History", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                                            }
                                            if (flag != null) {
                                                AssistChip(onClick = { showFlags = true }, label = { Text(flag.severity) })
                                            }
                                        }'''
if old not in s:
    raise SystemExit("Device card top-right status/flag slot not found.")
s = s.replace(old, new, 1)

old_bottom = '''            SmsCheckerHistoryDialog(
                store = if (selectedTab == 6) partnerSmsHistoryStore else deviceSmsHistoryStore,
                title = if (selectedTab == 6) "My Partners SMS Checker History" else "Device Monitor SMS Checker History",
                onDismiss = { showSmsHistory = false }
            )'''
new_bottom = '''            SmsCheckerHistoryDialog(
                store = if (selectedTab == 6) partnerSmsHistoryStore else deviceSmsHistoryStore,
                title = if (selectedTab == 6) "My Partners SMS Checker History" else "Device Monitor SMS Checker History",
                initialDeviceCode = smsHistoryDeviceCode,
                onDismiss = {
                    showSmsHistory = false
                    smsHistoryDeviceCode = null
                }
            )'''
if old_bottom not in s:
    raise SystemExit("SMS history page invocation not found.")
s = s.replace(old_bottom, new_bottom, 1)

# The global history control always opens the all-date view.
s = s.replace('onClick = { showSmsHistory = true }, enabled = !running, modifier = Modifier.weight(1f)', 'onClick = { smsHistoryDeviceCode = null; showSmsHistory = true }, enabled = !running, modifier = Modifier.weight(1f)', 1)

p.write_text(s, encoding="utf-8")
print("Added per-device History action to the device card header and wired both history entry points.")
