from pathlib import Path
import re

online = Path("OnlineReports.kt")
s = online.read_text(encoding="utf-8")
imports = """import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
"""
if "import android.content.Context" not in s:
    anchor = "package com.aziitech.simregmonitor\n"
    if anchor not in s:
        raise SystemExit("OnlineReports package declaration not found.")
    s = s.replace(anchor, anchor + "\n" + imports, 1)

helper_anchor = "object OnlineReportStore {\n"
helper = """object OnlineReportStore {
    /** Identity headers required when the Drive API key is restricted to this Android app. */
    private fun androidKeyIdentityHeaders(context: Context): Map<String, String> {
        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        @Suppress("DEPRECATION")
        val signature = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        }) ?: throw IllegalStateException("The app signing certificate could not be read for Google Drive authorization")
        val certificateSha1 = MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString("") { byte -> "%02X".format(byte) }
        return mapOf(
            "X-Android-Package" to context.packageName,
            "X-Android-Cert" to certificateSha1
        )
    }
"""
if "private fun androidKeyIdentityHeaders" not in s:
    if helper_anchor not in s:
        raise SystemExit("OnlineReportStore object anchor not found.")
    s = s.replace(helper_anchor, helper, 1)

s = s.replace("fun listReports(): List<OnlineReportFile> {", "fun listReports(context: Context): List<OnlineReportFile> {", 1)
if "fun listReports(context: Context)" not in s:
    raise SystemExit("Could not update Online Reports listReports signature.")

list_anchor = '                setRequestProperty("Accept", "application/json")\n'
if list_anchor not in s:
    raise SystemExit("Online Reports list request header anchor not found.")
if "androidKeyIdentityHeaders(context).forEach" not in s:
    s = s.replace(list_anchor, list_anchor + '                androidKeyIdentityHeaders(context).forEach { (name, value) -> setRequestProperty(name, value) }\n', 1)

download_anchor = '            setRequestProperty("Accept", XLSX_MIME)\n'
if download_anchor not in s:
    raise SystemExit("Online Reports download request header anchor not found.")
if s.count("androidKeyIdentityHeaders(context).forEach") < 2:
    s = s.replace(download_anchor, download_anchor + '            androidKeyIdentityHeaders(context).forEach { (name, value) -> setRequestProperty(name, value) }\n', 1)
if s.count("androidKeyIdentityHeaders(context).forEach") != 2:
    raise SystemExit("Expected Android identity headers on both report list and download requests.")

s = s.replace("OnlineReportStore.listReports()", "OnlineReportStore.listReports(context)", 1)
if "OnlineReportStore.listReports(context)" not in s:
    raise SystemExit("Online Reports UI call was not updated to pass Context.")
# Search shared folders across all accessible Drive corpora, not only the default user corpus.
old_query = '''val query = "'$folderId' in parents and trashed = false and mimeType != \'application/vnd.google-apps.folder\'"'''
new_query = '''val query = "'$folderId' in parents and trashed = false"'''
if old_query in s:
    s = s.replace(old_query, new_query, 1)
elif new_query not in s:
    raise SystemExit("Online Reports folder query anchor not found.")
s = s.replace('append("&spaces=drive&pageSize=1000")',
              'append("&spaces=drive&corpora=allDrives&pageSize=1000")', 1)
if "corpora=allDrives" not in s:
    raise SystemExit("Online Reports all-drives search scope was not added.")

# Native Google Sheets files have no .xlsx suffix; list them and export as XLSX on download.
s = s.replace('    val size: Long?\n)', '    val size: Long?,\n    val mimeType: String = ""\n)', 1)
s = s.replace('    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"\n',
              '    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"\n    private const val GOOGLE_SHEETS_MIME = "application/vnd.google-apps.spreadsheet"\n', 1)
s = s.replace('        var pageToken: String? = null\n',
              '        var pageToken: String? = null\n        var discoveredFiles = 0\n        val unsupportedFiles = mutableListOf<String>()\n', 1)
s = s.replace('                        val mimeType = item.optString("mimeType").trim()\n                        val supported = mimeType == XLSX_MIME || SUPPORTED_EXTENSIONS.any { lowerName.endsWith(it) }\n',
              '                        val mimeType = item.optString("mimeType").trim()\n                        if (id.isNotBlank() && name.isNotBlank()) discoveredFiles++\n                        val supported = mimeType == XLSX_MIME || mimeType == GOOGLE_SHEETS_MIME || SUPPORTED_EXTENSIONS.any { lowerName.endsWith(it) }\n', 1)
s = s.replace('                                    item.optLong("size").takeIf { it > 0 }\n',
              '                                    item.optLong("size").takeIf { it > 0 },\n                                    mimeType\n', 1)
s = s.replace('                            )\n                        }\n                    }\n                }\n                pageToken = payload.optString("nextPageToken").takeIf { it.isNotBlank() }\n',
              '                            )\n                        } else if (id.isNotBlank() && name.isNotBlank()) {\n                            unsupportedFiles.add("$name ($mimeType)")\n                        }\n                    }\n                }\n                pageToken = payload.optString("nextPageToken").takeIf { it.isNotBlank() }\n', 1)
s = s.replace('        return reports\n            .distinctBy { it.id }\n',
              '        if (reports.isEmpty()) {\n            if (discoveredFiles == 0) {\n                error("Google Drive returned no files for the configured company reports folder. Check that the folder contains reports and that its sharing permissions allow this app to view it.")\n            } else {\n                val examples = unsupportedFiles.take(3).joinToString()\n                error("Google Drive returned $discoveredFiles file(s), but none are supported Excel/CSV reports. Supported: XLSX, XLSM, XLS, CSV and Google Sheets. Found: $examples")\n            }\n        }\n\n        return reports\n            .distinctBy { it.id }\n', 1)
s = s.replace('        val url = URL(\n            "$DRIVE_API/${URLEncoder.encode(file.id, "UTF-8")}?alt=media&supportsAllDrives=true&key=${URLEncoder.encode(BuildConfig.DRIVE_REPORT_API_KEY.trim(), "UTF-8")}"\n        )\n',
              '        val encodedId = URLEncoder.encode(file.id, "UTF-8")\n        val encodedKey = URLEncoder.encode(BuildConfig.DRIVE_REPORT_API_KEY.trim(), "UTF-8")\n        val downloadUrl = if (file.mimeType == GOOGLE_SHEETS_MIME) {\n            "$DRIVE_API/$encodedId/export?mimeType=$XLSX_MIME&key=$encodedKey"\n        } else {\n            "$DRIVE_API/$encodedId?alt=media&supportsAllDrives=true&key=$encodedKey"\n        }\n        val url = URL(downloadUrl)\n', 1)
if "GOOGLE_SHEETS_MIME" not in s or "corpora=allDrives" not in s or "discoveredFiles" not in s:
    raise SystemExit("Online Reports shared-drive/Google Sheets discovery patch was incomplete.")
online.write_text(s, encoding="utf-8")

main = Path("MainActivity.kt")
m = main.read_text(encoding="utf-8")
m, removed = re.subn(r'(?m)^\s*val account = GoogleSignIn\.getLastSignedInAccount\(this@MainActivity\)\n', "", m)
if removed != 4:
    raise SystemExit(f"Expected 4 cached GoogleSignIn account declarations; found {removed}.")
m, nested = re.subn(r'else if \(account == null\) message = "Sign in to Google first"', 'else if (FirebaseAuth.getInstance().currentUser == null) message = "Connect your Google account first"', m)
m, simple = re.subn(r'if \(account == null\) message = "Sign in to Google first"', 'if (FirebaseAuth.getInstance().currentUser == null) message = "Connect your Google account first"', m)
if nested != 2 or simple != 2:
    raise SystemExit(f"Expected 2 nested and 2 simple Drive auth gates; found {nested} and {simple}.")
if "GoogleSignIn.getLastSignedInAccount(this@MainActivity)" in m:
    raise SystemExit("A stale GoogleSignIn cached-account gate remains.")
main.write_text(m, encoding="utf-8")
print("Patched Online Reports Android API-key identity headers and Firebase-backed Drive backup account checks.")
