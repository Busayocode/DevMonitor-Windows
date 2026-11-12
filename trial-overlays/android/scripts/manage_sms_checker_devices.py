from pathlib import Path

p = Path("MainActivity.kt")
s = p.read_text(encoding="utf-8")

anchor = '        var showSmsHistory by remember { mutableStateOf(false) }\n'
addition = '''        var showSmsDeviceManager by remember { mutableStateOf(false) }
        var showSmsDeviceDeleteConfirm by remember { mutableStateOf(false) }
        val selectedSmsDeviceCodes = remember { mutableStateListOf<String>() }
        var smsDeviceEditTarget by remember { mutableStateOf<String?>(null) }
        var smsDeviceEditValue by remember { mutableStateOf("") }
'''
if s.count(anchor) != 1:
    raise SystemExit("SMS history state anchor missing or ambiguous")
s = s.replace(anchor, anchor + addition, 1)

anchor = '        fun persist() = checkerStore.saveCurrent(codes, records)\n'
addition = '''
        fun removeSmsCheckerDevices(removing: Set<String>) {
            if (running || removing.isEmpty()) return
            val normalized = removing.map { it.trim().uppercase(Locale.US) }.toSet()
            codes = codes.filterNot { it.trim().uppercase(Locale.US) in normalized }
            records = records.filterKeys { it.trim().uppercase(Locale.US) !in normalized }
            persist()
            selectedSmsDeviceCodes.clear()
            showSmsDeviceManager = false
            message = "Removed ${normalized.size} device${if (normalized.size == 1) "" else "s"} and permanently deleted its saved SMS history."
            scope.launch(Dispatchers.IO) { activeSmsHistoryStore.deleteCodes(normalized) }
        }

        fun renameSmsCheckerDevice(oldCode: String, newCodeRaw: String) {
            if (running) return
            val newCode = canonicalDeviceCode(newCodeRaw)
            val oldKey = oldCode.trim().uppercase(Locale.US)
            val newKey = newCode.trim().uppercase(Locale.US)
            if (newCode.isBlank()) { message = "Enter a valid device or Trade Code."; return }
            if (codes.any { it.trim().uppercase(Locale.US) == newKey && it.trim().uppercase(Locale.US) != oldKey }) {
                message = "That code is already in this SMS Checker list."; return
            }
            codes = codes.map { if (it.trim().uppercase(Locale.US) == oldKey) newCode else it }
            records = records.entries.associate { (key, value) ->
                (if (key.trim().uppercase(Locale.US) == oldKey) newCode else key) to value
            }
            persist()
            showSmsDeviceManager = false
            smsDeviceEditTarget = null
            scope.launch(Dispatchers.IO) { activeSmsHistoryStore.renameCode(oldCode, newCode) }
            message = "Updated $oldCode to $newCode. Its saved SMS history was carried over."
        }
'''
if s.count(anchor) != 1:
    raise SystemExit("persist anchor missing or ambiguous")
s = s.replace(anchor, anchor + addition, 1)

anchor = '''                        item {
                            TextField(
                                value = smsListSearchQuery,
'''
addition = '''                        item {
                            DevMonitorOutlinedButton(
                                onClick = { selectedSmsDeviceCodes.clear(); showSmsDeviceManager = true },
                                enabled = !running && codes.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Manage devices • ${codes.size}") }
                        }
'''
if s.count(anchor) != 1:
    raise SystemExit("SMS list search anchor missing or ambiguous")
s = s.replace(anchor, addition + anchor, 1)

anchor = '''        if (showSmsHistory) {
            SmsCheckerHistoryDialog(
'''
addition = '''        if (showSmsDeviceManager) {
            AlertDialog(
                onDismissRequest = { showSmsDeviceManager = false; selectedSmsDeviceCodes.clear() },
                title = { Text(if (selectedTab == 6) "Manage partner Trade Codes" else "Manage SMS Checker devices") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Select one or more entries to remove them from this list and permanently delete their saved SMS History.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${selectedSmsDeviceCodes.size} selected", fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = {
                                if (selectedSmsDeviceCodes.size == codes.size) selectedSmsDeviceCodes.clear()
                                else { selectedSmsDeviceCodes.clear(); selectedSmsDeviceCodes.addAll(codes) }
                            }) { Text(if (selectedSmsDeviceCodes.size == codes.size) "Clear selection" else "Select all") }
                        }
                        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(codes, key = { it }) { deviceCode ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Checkbox(checked = deviceCode in selectedSmsDeviceCodes, onCheckedChange = { checked ->
                                        if (checked) { if (deviceCode !in selectedSmsDeviceCodes) selectedSmsDeviceCodes.add(deviceCode) }
                                        else selectedSmsDeviceCodes.remove(deviceCode)
                                    })
                                    Text(deviceCode, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { smsDeviceEditTarget = deviceCode; smsDeviceEditValue = deviceCode }) { Text("Edit") }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSmsDeviceDeleteConfirm = true }, enabled = selectedSmsDeviceCodes.isNotEmpty() && !running) {
                        Text("Delete selected (${selectedSmsDeviceCodes.size})", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showSmsDeviceManager = false; selectedSmsDeviceCodes.clear() }) { Text("Done") } }
            )
        }

        if (showSmsDeviceDeleteConfirm) {
            val deleteCount = selectedSmsDeviceCodes.size
            AlertDialog(
                onDismissRequest = { showSmsDeviceDeleteConfirm = false },
                title = { Text("Permanently delete selected devices?") },
                text = { Text("This removes $deleteCount selected item${if (deleteCount == 1) "" else "s"} from the active SMS Checker list and permanently deletes their saved SMS History. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSmsDeviceDeleteConfirm = false
                        removeSmsCheckerDevices(selectedSmsDeviceCodes.toSet())
                    }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showSmsDeviceDeleteConfirm = false }) { Text("Cancel") } }
            )
        }

        if (smsDeviceEditTarget != null) {
            val originalCode = smsDeviceEditTarget!!
            AlertDialog(
                onDismissRequest = { smsDeviceEditTarget = null },
                title = { Text("Edit device / trade code") },
                text = {
                    TextField(value = smsDeviceEditValue, onValueChange = { smsDeviceEditValue = it }, label = { Text("Device or Trade Code") }, singleLine = true)
                },
                confirmButton = { TextButton(onClick = { renameSmsCheckerDevice(originalCode, smsDeviceEditValue) }, enabled = !running && smsDeviceEditValue.isNotBlank()) { Text("Save changes") } },
                dismissButton = { TextButton(onClick = { smsDeviceEditTarget = null }) { Text("Cancel") } }
            )
        }

'''
if s.count(anchor) != 1:
    raise SystemExit("SMS history dialog anchor missing or ambiguous")
s = s.replace(anchor, addition + anchor, 1)
p.write_text(s, encoding="utf-8")
print("Applied SMS Checker bulk device management.")
