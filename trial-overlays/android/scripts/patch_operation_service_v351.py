from pathlib import Path
import re

root = Path.cwd()
pkg = root / "app/src/main/java/com/aziitech/simregmonitor"
manifest = root / "app/src/main/AndroidManifest.xml"
main = pkg / "MainActivity.kt"
sync = pkg / "NextLevelScreen.kt"
overlay = Path(__file__).resolve().parent.parent
service_src = overlay / "app/src/main/java/com/aziitech/simregmonitor/OperationForegroundService.kt"
for p in (pkg, manifest, main, sync, service_src):
    if not p.exists():
        raise SystemExit(f"Required v351 input missing: {p}")

def once(source, old, new, label):
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {label}; found {count}")
    return source.replace(old, new, 1)

service = pkg / "OperationForegroundService.kt"
if service.exists():
    raise SystemExit("OperationForegroundService.kt already exists; refusing to overwrite.")
service.write_text(service_src.read_text(encoding="utf-8"), encoding="utf-8")

m = manifest.read_text(encoding="utf-8")
for name in ("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC"):
    if f'android:name="{name}"' not in m:
        anchor = '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'
        if anchor not in m:
            raise SystemExit("Notification permission anchor missing from manifest.")
        m = m.replace(anchor, anchor + f'\n    <uses-permission android:name="{name}" />', 1)
decl = '''        <service
            android:name=".OperationForegroundService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
'''
if ".OperationForegroundService" not in m:
    m = once(m, '        <activity android:name=".MainActivity"', decl + '        <activity android:name=".MainActivity"', "MainActivity manifest entry")
manifest.write_text(m, encoding="utf-8")

s = main.read_text(encoding="utf-8")
s = once(s, '        var running by remember { mutableStateOf(false) }',
         '        var running by remember { mutableStateOf(false) }\n        var pauseAfterCurrent by remember { mutableStateOf(false) }',
         "SMS running state")
s = once(s, '''            running = true
            message = if (retryOnly) {''',
         '''            running = true
            pauseAfterCurrent = false
            OperationForegroundService.start(this@MainActivity, "SMS Checker", "Preparing device checks…", 0, requestedCodes.size, controls = true)
            message = if (retryOnly) {''', "SMS operation start")
s = once(s, '''                while (isActive) {
                    // Drain the original request first, then consume any device''',
         '''                while (isActive) {
                    while (pauseAfterCurrent && isActive) {
                        OperationForegroundService.update(this@MainActivity, "SMS Checker", "Paused — waiting to resume", processedCodes.size, runTotal, paused = true, pauseRequested = true, controls = true)
                        delay(300)
                    }
                    // Drain the original request first, then consume any device''', "SMS queue pause gate")
s = once(s, '''                    currentIndex = processedCodes.lastIndex
                    waitingCode = code
                    val attempt = if (codeRetryOnly) {''',
         '''                    currentIndex = processedCodes.lastIndex
                    waitingCode = code
                    OperationForegroundService.update(this@MainActivity, "SMS Checker", "Checking $code", processedCodes.size, runTotal, controls = true)
                    val attempt = if (codeRetryOnly) {''', "SMS progress update")
s = once(s, '                    var matched: SimRegResult? = null',
         '                    OperationForegroundService.update(this@MainActivity, "SMS Checker", "Waiting for 3030 reply — $code", processedCodes.size, runTotal, controls = true)\n                    var matched: SimRegResult? = null',
         "SMS reply-wait notification")
s = once(s, '''                checkerStore.addHistory(records)
                pendingRecheckCodes.clear()''',
         '''                checkerStore.addHistory(records)
                OperationForegroundService.stop(this@MainActivity)
                pendingRecheckCodes.clear()''', "SMS completion cleanup")
s = once(s, '''        fun stopRun() {
            runJob?.cancel()''',
         '''        fun stopRun() {
            OperationForegroundService.stop(this@MainActivity)
            pauseAfterCurrent = false
            runJob?.cancel()''', "SMS Stop handler")
s = once(s, '''            message = "Stopped — completed results were kept."
        }

        val retryCodes = codes.filter {''',
         '''            message = "Stopped — completed results were kept."
        }

        OperationServiceCallbacks.onStopRequested = { stopRun() }
        OperationServiceCallbacks.onPauseRequested = {
            pauseAfterCurrent = true
            message = "Pause requested — the current device check will finish first."
        }
        OperationServiceCallbacks.onResumeRequested = {
            pauseAfterCurrent = false
            message = "Resuming SMS Checker."
        }

        val retryCodes = codes.filter {''', "notification action callbacks")

# Both report-import entry points are user-started and receive an ongoing notification.
s = once(s, '''        importingSimReg = true
        simRegLoadingTitle = "Opening your report…"''',
         '''        importingSimReg = true
        OperationForegroundService.start(this, "Report processing", "Reading the selected workbook…")
        simRegLoadingTitle = "Opening your report…"''', "selected report import")
s = once(s, '''        importingSimReg = true
        simRegLoadingTitle = "Opening the downloaded report…"''',
         '''        importingSimReg = true
        OperationForegroundService.start(this, "Report processing", "Opening the downloaded workbook…")
        simRegLoadingTitle = "Opening the downloaded report…"''', "downloaded report import")
for old, new, label in (
    ('''            result.onSuccess { parsed ->
                simRegLoadingTitle = "Rendering the imported table…"''',
     '''            result.onSuccess { parsed ->
                OperationForegroundService.stop(this@MainActivity)
                simRegLoadingTitle = "Rendering the imported table…"''', "report success"),
    ('''            }.onFailure { error ->
                DevMonitorLogger.error("IMPORT",''',
     '''            }.onFailure { error ->
                OperationForegroundService.stop(this@MainActivity)
                DevMonitorLogger.error("IMPORT",''', "report failure"),
):
    if s.count(old) != 2:
        raise SystemExit(f"Expected two {label} callbacks.")
    s = s.replace(old, new)

# Drive backup and restore operations stop their notification even when transfer fails.
backup = "GoogleDriveBackup.backup(driveAccessToken, database.databaseFile())"
restore = "GoogleDriveBackup.restore(driveAccessToken, temp)"
if s.count(backup) != 2 or s.count(restore) != 2:
    raise SystemExit("Expected two existing Drive backup and restore call sites.")
s = s.replace(backup, '''run {
                                    OperationForegroundService.start(this@MainActivity, "Google Drive backup", "Uploading database backup…")
                                    try { GoogleDriveBackup.backup(driveAccessToken, database.databaseFile()) }
                                    finally { OperationForegroundService.stop(this@MainActivity) }
                                }''')
s = s.replace(restore, '''run {
                                    OperationForegroundService.start(this@MainActivity, "Google Drive restore", "Downloading database backup…")
                                    try { GoogleDriveBackup.restore(driveAccessToken, temp) }
                                    finally { OperationForegroundService.stop(this@MainActivity) }
                                }''')
s = once(s, '''    override fun onDestroy() {
        scope.cancel()''',
         '''    override fun onDestroy() {
        OperationForegroundService.stop(this)
        OperationServiceCallbacks.onStopRequested = null
        OperationServiceCallbacks.onPauseRequested = null
        OperationServiceCallbacks.onResumeRequested = null
        scope.cancel()''', "Activity cleanup")
release_note_old = 'whatsNewHighlights = listOf("Manage SMS Checker devices more easily, with improvements to reports and overall reliability.")'
release_note_new = 'whatsNewHighlights = listOf("Track active checks and data operations from Android notifications, with pause and stop controls for SMS Checker.")'
if s.count(release_note_old) != 2:
    raise SystemExit("Expected both existing What's New entry points before updating v351 release notes.")
s = s.replace(release_note_old, release_note_new)
main.write_text(s, encoding="utf-8")

# Only the explicit Sync now action starts this service; periodic screen-scoped sync stays unchanged.
n = sync.read_text(encoding="utf-8")
n = once(n, '''            updateBridgeStatus("Synchronizing…")
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { SyncBridgeClient.sync(context, db, bridgeEndpoint, bridgeKey) }
                }''',
         '''            updateBridgeStatus("Synchronizing…")
            OperationForegroundService.start(context, "DevMonitor sync", "Synchronizing operational data with the PC…")
            scope.launch {
                val result = try {
                    runCatching {
                        withContext(Dispatchers.IO) { SyncBridgeClient.sync(context, db, bridgeEndpoint, bridgeKey) }
                    }
                } finally {
                    OperationForegroundService.stop(context)
                }''', "manual Sync now action")
sync.write_text(n, encoding="utf-8")
print("Applied v351 foreground operation notifications, SMS progress/Pause/Stop controls, report import, Drive backup/restore and manual LAN sync.")
