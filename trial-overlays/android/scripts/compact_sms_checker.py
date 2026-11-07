from pathlib import Path

p = Path("MainActivity.kt")
s = p.read_text(encoding="utf-8")

old = '''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DevMonitorButton(
                                            onClick = {
                                                addCodes(normalizedList(input))
                                                input = ""
                                            },
                                            enabled = !running && input.isNotBlank(),
                                            modifier = Modifier
                                                .weight(1f)
                                        ) { Text("Add list") }
                                        DevMonitorOutlinedButton(onClick = { codes = normalizedList(input); records = emptyMap(); input = ""; persist() }, enabled = !running && input.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Replace") }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {'''
new = '''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        DevMonitorButton(
                                            onClick = {
                                                addCodes(normalizedList(input))
                                                input = ""
                                            },
                                            enabled = !running && input.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 7.dp)
                                        ) { Text("Add list", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                                        DevMonitorOutlinedButton(
                                            onClick = { codes = normalizedList(input); records = emptyMap(); input = ""; persist() },
                                            enabled = !running && input.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 7.dp)
                                        ) { Text("Replace", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {'''
if old not in s:
    raise SystemExit("Could not find the Add list / Replace action row; refusing to patch an unexpected source version.")
s = s.replace(old, new, 1)

s = s.replace('''                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(18.dp)
                                        ) { Text("Quick Check", fontWeight = FontWeight.Bold) }''', '''                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 7.dp)
                                        ) { Text("Quick Check", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false) }''', 1)

s = s.replace('''                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(18.dp)
                                            ) { Text(if (zeroDevicesLoading) "Finding…" else "Zero Devices", fontWeight = FontWeight.Bold) }''', '''                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(14.dp),
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                                            ) { Text(if (zeroDevicesLoading) "Finding…" else "Zero Devices", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false) }''', 1)

s = s.replace('''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DevMonitorOutlinedButton(onClick = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }, enabled = !running, modifier = Modifier.weight(1f)) { Text("Load Excel") }''', '''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        DevMonitorOutlinedButton(onClick = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }, enabled = !running, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 7.dp)) { Text("Load Excel", fontSize = 13.sp, maxLines = 1, softWrap = false) }''', 1)

s = s.replace('''                                                .then(if (saveHintActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                        ) { Text("Save list") }''', '''                                                .then(if (saveHintActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 7.dp)
                                        ) { Text("Save list", fontSize = 13.sp, maxLines = 1, softWrap = false) }''', 1)

old4 = '''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DevMonitorOutlinedButton(
                                            onClick = { showSavedLists = true },
                                            enabled = !running,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(if (saveHintActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                        ) { Text("Saved") }
                                        DevMonitorOutlinedButton(onClick = { showArchived = true }, enabled = !running, modifier = Modifier.weight(1f)) { Text("Archived") }
                                        DevMonitorOutlinedButton(onClick = { codes = emptyList(); records = emptyMap(); persist() }, enabled = !running && codes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
                                        DevMonitorOutlinedButton(onClick = { showSmsHistory = true }, enabled = !running, modifier = Modifier.weight(1f)) { Text("History") }
                                    }'''
new4 = '''                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        DevMonitorOutlinedButton(
                                            onClick = { showSavedLists = true },
                                            enabled = !running,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                                        ) { Text("Saved", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                        DevMonitorOutlinedButton(
                                            onClick = { showArchived = true },
                                            enabled = !running,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                                        ) { Text("Archived", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                        DevMonitorOutlinedButton(
                                            onClick = { codes = emptyList(); records = emptyMap(); persist() },
                                            enabled = !running && codes.isNotEmpty(),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                                        ) { Text("Clear", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                        DevMonitorOutlinedButton(
                                            onClick = { showSmsHistory = true },
                                            enabled = !running,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                                        ) { Text("History", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                    }'''
if old4 not in s:
    raise SystemExit("Could not find the four SMS Checker utility buttons; refusing to patch an unexpected source version.")
s = s.replace(old4, new4, 1)

p.write_text(s, encoding="utf-8")
print("Applied compact SMS Checker controls: reduced action padding, tighter spacing, smaller labels, and one-line rectangular utility buttons.")
