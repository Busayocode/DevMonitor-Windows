from pathlib import Path
import re

root = Path(".")
pkg = root / "app/src/main/java/com/aziitech/simregmonitor"
backup = pkg / "GoogleDriveBackup.kt"
main = pkg / "MainActivity.kt"
if not backup.exists() or not main.exists():
    raise SystemExit("Expected Android Kotlin source files were not found.")

backup.write_text(r'''package com.aziitech.simregmonitor

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** User-visible Google Drive backup in the signed-in user's My Drive. */
object GoogleDriveBackup {
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER = "DevMonitor Backups"
    private const val NAME = "DevMonitorBackup.db"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private fun report(progress: ((String, Float) -> Unit)?, phase: String, value: Float) {
        progress?.invoke(phase, value.coerceIn(0f, 1f))
    }

    suspend fun backup(accessToken: String, dbFile: File, progress: ((String, Float) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        if (!dbFile.isFile || !dbFile.canRead() || dbFile.length() < 1000L) throw IllegalStateException("The local DevMonitor database is missing or too small to back up")
        validateDatabase(dbFile)
        val token = accessToken.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Google Drive authorisation returned an empty access token")
        report(progress, "Preparing database", 0.05f)
        val folderId = findOrCreateFolder(token, progress)
        val existing = findBackup(token, folderId)
        val boundary = "DevMonitor-${System.currentTimeMillis()}"
        val metadata = JSONObject().put("name", NAME).put("parents", JSONArray().put(folderId))
        val destination = if (existing == null) "$UPLOAD?uploadType=multipart&fields=id,name,modifiedTime" else "$UPLOAD/${existing.first}?uploadType=multipart&fields=id,name,modifiedTime"
        val conn = URL(destination).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.requestMethod = if (existing == null) "POST" else "PATCH"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(prefix.size.toLong() + dbFile.length() + suffix.size)
            conn.outputStream.use { out ->
                out.write(prefix)
                dbFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        report(progress, "Uploading backup", 0.20f + 0.72f * sent.toFloat() / dbFile.length().toFloat())
                    }
                }
                out.write(suffix)
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("Google Drive backup failed (${conn.responseCode}): ${readError(conn)}")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            if (runCatching { JSONObject(response).optString("id") }.getOrDefault("").isBlank()) throw IllegalStateException("Google Drive returned no backup file ID")
            report(progress, "Backup complete", 1f)
            if (existing == null) "Backup completed in your Google Drive ‘$FOLDER’ folder" else "Backup updated in your Google Drive ‘$FOLDER’ folder"
        } finally { conn.disconnect() }
    }

    suspend fun restore(accessToken: String, target: File, progress: ((String, Float) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        val token = accessToken.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Google Drive authorisation returned an empty access token")
        report(progress, "Finding your backup", 0.05f)
        val folderId = findFolder(token) ?: throw IllegalStateException("The ‘$FOLDER’ folder was not found in this Google account")
        val newest = findBackup(token, folderId) ?: throw IllegalStateException("No DevMonitor backup was found in your Google Drive ‘$FOLDER’ folder")
        val download = URL("$API/${newest.first}?alt=media").openConnection() as HttpURLConnection
        val staged = File(target.parentFile ?: target.absoluteFile.parentFile, target.name + ".download.tmp")
        try {
            download.connectTimeout = 20_000
            download.readTimeout = 90_000
            download.setRequestProperty("Authorization", "Bearer $token")
            if (download.responseCode !in 200..299) throw IllegalStateException("Google Drive restore failed (${download.responseCode}): ${readError(download)}")
            val total = download.contentLengthLong
            staged.outputStream().use { out ->
                download.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        report(progress, "Downloading backup", if (total > 0) 0.10f + 0.72f * received.toFloat() / total.toFloat() else 0.5f)
                    }
                }
            }
            report(progress, "Validating database", 0.88f)
            validateDatabase(staged)
            if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace the previous temporary restore file")
            if (!staged.renameTo(target)) { staged.copyTo(target, overwrite = true); staged.delete() }
            report(progress, "Restore complete", 1f)
            "Restore downloaded and validated successfully"
        } catch (e: Exception) {
            staged.delete()
            throw e
        } finally { download.disconnect() }
    }

    private fun findOrCreateFolder(token: String, progress: ((String, Float) -> Unit)?): String {
        report(progress, "Finding backup folder", 0.10f)
        findFolder(token)?.let { return it }
        report(progress, "Creating ‘$FOLDER’ folder", 0.16f)
        val body = JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME)
        val response = request("$API?fields=id,name,mimeType", token, "POST", body.toString())
        return JSONObject(response).optString("id").takeIf { it.isNotBlank() } ?: throw IllegalStateException("Google Drive did not return the new backup folder ID")
    }

    private fun findFolder(token: String): String? {
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='$FOLDER_MIME' and trashed=false", "UTF-8")
        val json = JSONObject(request("$API?q=$q&spaces=drive&fields=files(id,name,mimeType)&pageSize=100", token))
        val files = json.optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.firstOrNull()
    }

    private fun findBackup(token: String, folderId: String): Pair<String, String>? {
        val q = URLEncoder.encode("name='$NAME' and '$folderId' in parents and trashed=false", "UTF-8")
        val files = JSONObject(request("$API?q=$q&spaces=drive&fields=files(id,name,modifiedTime)&pageSize=100", token)).optJSONArray("files") ?: JSONArray()
        var best: Pair<String, String>? = null
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            val time = item.optString("modifiedTime")
            if (best == null || time > best.second) best = id to time
        }
        return best
    }

    private fun validateDatabase(file: File) {
        if (!file.isFile || file.length() < 1000L) throw IllegalStateException("The backup file is empty or incomplete")
        val header = ByteArray(16)
        file.inputStream().use { if (it.read(header) != header.size || !header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) throw IllegalStateException("The backup is not a valid DevMonitor SQLite database") }
        val db = try { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) } catch (e: Exception) { throw IllegalStateException("The backup database could not be opened: ${e.message ?: "invalid database"}", e) }
        try {
            db.rawQuery("PRAGMA quick_check", null).use { if (!it.moveToFirst() || !it.getString(0).equals("ok", true)) throw IllegalStateException("The backup database failed its integrity check") }
            val found = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { while (it.moveToNext()) found += it.getString(0) }
            if (!found.containsAll(setOf("codes", "records", "settings"))) throw IllegalStateException("This file is not a complete DevMonitor database backup")
        } finally { db.close() }
    }

    private fun request(url: String, token: String, method: String = "GET", body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 30_000; c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            if (c.responseCode !in 200..299) throw IllegalStateException("Google Drive request failed (${c.responseCode}): ${readError(c)}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    private fun readError(c: HttpURLConnection): String = runCatching { (c.errorStream ?: return@runCatching "no error details").bufferedReader().use { it.readText() }.replace(Regex("\\s+"), " ").take(240) }.getOrDefault("no error details")
}
''', encoding="utf-8")

s = main.read_text(encoding="utf-8")
s = s.replace("Scopes.DRIVE_APPFOLDER", "Scopes.DRIVE_FILE")
s = s.replace("Google Drive App Folder permission", "Google Drive file access")
s = s.replace("Drive App Folder permission", "Drive file access")
s = s.replace("var cloudBusy by remember { mutableStateOf(false) }", """var cloudBusy by remember { mutableStateOf(false) }
        var cloudProgress by remember { mutableFloatStateOf(0f) }
        var cloudPhase by remember { mutableStateOf("") }
        val cloudProgressCallback: (String, Float) -> Unit = { phase, fraction ->
            cloudPhase = phase
            cloudProgress = fraction
        }""", 1)
s = s.replace("GoogleDriveBackup.backup(driveAccessToken, database.databaseFile())", "GoogleDriveBackup.backup(driveAccessToken, database.databaseFile(), cloudProgressCallback)")
s = s.replace("GoogleDriveBackup.restore(driveAccessToken, temp)", "GoogleDriveBackup.restore(driveAccessToken, temp, cloudProgressCallback)")
s = s.replace("cloudBusy = true\n", "cloudBusy = true\n                                                        cloudProgress = 0.02f\n                                                        cloudPhase = \"Preparing Google Drive operation\"\n", 1) if False else s
# Every cloud backup/restore entry starts progress at zero; callbacks update real byte-based transfer progress.
s = s.replace("cloudBusy = true\n                                                        requestDriveAuthorization", "cloudBusy = true\n                                                        cloudProgress = 0.02f\n                                                        cloudPhase = \"Requesting Drive access\"\n                                                        requestDriveAuthorization")
s = s.replace("cloudBusy = true\n                                                    requestDriveAuthorization", "cloudBusy = true\n                                                    cloudProgress = 0.02f\n                                                    cloudPhase = \"Requesting Drive access\"\n                                                    requestDriveAuthorization")
# Show the same progress presentation in the account backup panel.
anchor = """                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DevMonitorButton(onClick = {"""
replacement = """                                        if (cloudBusy) {
                                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(cloudPhase.ifBlank { "Working with Google Drive…" }, style = MaterialTheme.typography.bodySmall)
                                                LinearProgressIndicator(progress = { cloudProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                                Text("${(cloudProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DevMonitorButton(onClick = {"""
if anchor not in s: raise SystemExit("Could not find cloud backup controls to attach progress UI")
s = s.replace(anchor, replacement, 1)
main.write_text(s, encoding="utf-8")
print("Migrated cloud backup to visible My Drive folder with drive.file scope and byte-based transfer progress.")
