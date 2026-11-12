from pathlib import Path
import re

online = Path("OnlineReports.kt")
s = online.read_text(encoding="utf-8")

# Bound the complete paginated Drive listing, not just each individual socket read.
# HttpURLConnection is blocking, so a coroutine timeout alone cannot reliably stop
# a stalled read. The remaining wall-clock budget is also applied to each request.
if "requestDeadlineNanos" not in s:
    anchor = """        var pageCount = 0
        while (pageCount < 20) {
            pageCount++
"""
    replacement = """        val requestDeadlineNanos = System.nanoTime() + 30_000_000_000L
        var pageCount = 0
        while (pageCount < 20) {
            val remainingMs = ((requestDeadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) error("Google Drive report listing timed out after 30 seconds. Please check your connection and retry.")
            pageCount++
"""
    if anchor not in s:
        raise SystemExit("Online Reports pagination deadline anchor not found.")
    s = s.replace(anchor, replacement, 1)

s = s.replace("connectTimeout = 8_000", "connectTimeout = remainingMs.coerceAtMost(8_000).coerceAtLeast(1)", 1)
s = s.replace("readTimeout = 12_000", "readTimeout = remainingMs.coerceAtMost(12_000).coerceAtLeast(1)", 1)
if "requestDeadlineNanos" not in s or "readTimeout = remainingMs.coerceAtMost(12_000)" not in s:
    raise SystemExit("Online Reports per-request timeout was not applied.")

# Do not silently show a partial report list if pagination is still incomplete.
loop_end = """        }

        if (reports.isEmpty()) {"""
if loop_end in s and "returned more than 20 pages" not in s:
    s = s.replace(loop_end, """        }
        if (!pageToken.isNullOrBlank()) {
            error("Google Drive returned more than 20 pages of reports. Narrow the shared folder or retry.")
        }

        if (reports.isEmpty()) {""", 1)

# Always leave the loading state, including timeout, network, and API-key failures.
refresh_pattern = re.compile(
    r"    suspend fun refresh\(\) \{[\s\S]*?\n    \}\n\n    LaunchedEffect\(refreshToken\) \{ refresh\(\) \}"
)
refresh_replacement = """    LaunchedEffect(refreshToken) {
        refreshing = true
        error = null
        try {
            when {
                !NetworkUtils.hasActiveInternet(context) -> {
                    reports = emptyList()
                    error = NetworkUtils.OFFLINE_MESSAGE
                }
                !OnlineReportStore.configured() -> {
                    reports = emptyList()
                    error = "Online reports are not configured yet. Set the Google Drive folder ID and API key in the build configuration."
                }
                else -> {
                    reports = withTimeout(35_000) {
                        withContext(Dispatchers.IO) { OnlineReportStore.listReports(context) }
                    }
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            reports = emptyList()
            error = "Google Drive did not respond within 35 seconds. Check your connection and tap Retry."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            reports = emptyList()
            error = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        } finally {
            refreshing = false
        }
    }"""
if "LaunchedEffect(refreshToken) { refresh() }" in s:
    s, count = refresh_pattern.subn(refresh_replacement, s, count=1)
    if count != 1:
        raise SystemExit(f"Expected to replace one Online Reports refresh function; replaced {count}.")
elif "finally {\n            refreshing = false" not in s:
    raise SystemExit("Online Reports refresh function was not found for timeout-safe replacement.")

# Keep report downloads bounded too; the dialog must not remain locked indefinitely.
s = s.replace("connectTimeout = 15_000\n            readTimeout = 120_000",
              "connectTimeout = 10_000\n            readTimeout = 45_000", 1)

online.write_text(s, encoding="utf-8")

main = Path("MainActivity.kt")
m = main.read_text(encoding="utf-8")
old = """                        val result = withContext(Dispatchers.IO) {
                            runCatching { OnlineReportStore.downloadToCache(this@MainActivity, onlineFile) }
                        }"""
new = """                        val result = runCatching {
                            kotlinx.coroutines.withTimeout(60_000) {
                                withContext(Dispatchers.IO) {
                                    OnlineReportStore.downloadToCache(this@MainActivity, onlineFile)
                                }
                            }
                        }"""
if old in m:
    m = m.replace(old, new, 1)
elif "kotlinx.coroutines.withTimeout(60_000)" not in m:
    raise SystemExit("Online report selection download timeout anchor not found.")
main.write_text(m, encoding="utf-8")

print("Online Reports now has a bounded Drive listing, timeout-safe loading state, actionable errors, and bounded downloads.")
