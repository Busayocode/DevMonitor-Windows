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
