package com.giantbomb.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API = "https://api.github.com/repos/Clinteastman/GiantBombTV/releases/latest"
    }

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() ?: "" }

            if (response.code != 200) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return@withContext null
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val latestVersion = tagName.removePrefix("v")
            val releaseNotes = json.optString("body", "")

            val currentVersion = getInstalledVersion()
            if (!isNewer(latestVersion, currentVersion)) {
                Log.d(TAG, "Up to date: $currentVersion (latest: $latestVersion)")
                return@withContext null
            }

            // Find the APK asset
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        val downloadUrl = asset.getString("browser_download_url")
                        Log.d(TAG, "Update available: $latestVersion (current: $currentVersion)")
                        return@withContext UpdateInfo(
                            versionName = latestVersion,
                            downloadUrl = downloadUrl,
                            releaseNotes = releaseNotes
                        )
                    }
                }
            }

            Log.w(TAG, "Release found but no APK asset")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    suspend fun downloadUpdate(
        downloadUrl: String,
        onProgress: (percent: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            // Clean old downloads
            updateDir.listFiles()?.forEach { it.delete() }

            val outFile = File(updateDir, "update.apk")

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
                .newCall(request).execute()

            response.use { resp ->
                if (resp.code != 200) {
                    Log.e(TAG, "Download failed: HTTP ${resp.code}")
                    return@withContext null
                }

                val body = resp.body ?: return@withContext null
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var lastReportedPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Download complete: ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }

    fun getInstallIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    private fun getInstalledVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
