from pathlib import Path

main = Path("app/src/main/java/com/aziitech/simregmonitor/MainActivity.kt")
if not main.is_file():
    raise SystemExit("MainActivity.kt not found.")
source = main.read_text(encoding="utf-8")
verbose_assignment = "whatsNewHighlights = remote.ifEmpty { generated }"
if source.count(verbose_assignment) != 2:
    raise SystemExit(f"Expected two What's New assignments; found {source.count(verbose_assignment)}.")
source = source.replace(
    verbose_assignment,
    'whatsNewHighlights = listOf("Manage SMS Checker devices more easily, with improvements to reports and overall reliability.")'
)
old_dialog = '''                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("These highlights were generated from the release documentation.", style = MaterialTheme.typography.bodySmall)
                        whatsNewHighlights.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(item, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                },'''
new_dialog = '''                text = {
                    Text(
                        whatsNewHighlights.firstOrNull()
                            ?: "A smoother DevMonitor experience with useful improvements and fixes.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                },'''
if old_dialog not in source:
    raise SystemExit("What's New dialog layout anchor not found.")
source = source.replace(old_dialog, new_dialog, 1)
main.write_text(source, encoding="utf-8")
print("What's New now displays one short, plain-language message instead of technical release notes.")
