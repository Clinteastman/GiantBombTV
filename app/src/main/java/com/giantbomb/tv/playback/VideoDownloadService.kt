package com.giantbomb.tv.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.giantbomb.tv.DownloadsActivity
import com.giantbomb.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service that drains [Downloads]' queue, streaming each video to a
 * `.part` file and renaming it to `.mp4` on success. Sequential (one at a time)
 * which is plenty for an offline-saves feature and keeps the notification and
 * progress reporting simple. Survives the app being backgrounded; stops itself
 * once the queue is empty.
 */
class VideoDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Downloads are long-lived; don't let a read timeout kill them.
            .readTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must post a foreground notification promptly after startForegroundService.
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", 0, true))
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val next = Downloads.nextQueued() ?: break
                downloadOne(next)
            }
            ServiceCompat.stopForeground(this@VideoDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun downloadOne(download: Download) {
        val id = download.videoId
        val ctx = applicationContext
        if (Downloads.isCancelled(id)) {
            Downloads.clearCancelled(id)
            return
        }

        Downloads.put(download.copy(status = DownloadStatus.DOWNLOADING))
        updateNotification(download.video.title, 0, indeterminate = true)

        val part = DownloadStore.partFile(ctx, id)
        val target = DownloadStore.videoFile(ctx, id)

        try {
            val request = Request.Builder()
                .url(download.url)
                .header("User-Agent", "GBTV")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty response")
                val total = body.contentLength()

                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        var lastNotifyMs = 0L
                        while (true) {
                            if (Downloads.isCancelled(id) || !scope.isActive) {
                                throw CancellationSignal()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val percent = if (total > 0) {
                                ((downloaded * 100) / total).toInt()
                            } else 0
                            val now = System.currentTimeMillis()
                            // Throttle state + notification churn: on each 1%
                            // change, at most ~twice a second.
                            if (percent != lastPercent && now - lastNotifyMs > 400) {
                                lastPercent = percent
                                lastNotifyMs = now
                                Downloads.put(
                                    download.copy(
                                        status = DownloadStatus.DOWNLOADING,
                                        progressPercent = percent,
                                        bytesDownloaded = downloaded,
                                        totalBytes = total
                                    )
                                )
                                updateNotification(
                                    download.video.title,
                                    percent,
                                    indeterminate = total <= 0
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (!part.renameTo(target)) {
                    // Cross-filesystem fallback (shouldn't happen for same dir).
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }

                val completed = download.copy(
                    status = DownloadStatus.COMPLETED,
                    progressPercent = 100,
                    bytesDownloaded = target.length(),
                    totalBytes = if (total > 0) total else target.length(),
                    filePath = target.absolutePath
                )
                DownloadStore.writeMeta(ctx, completed)
                Downloads.put(completed)
                notifyComplete(id, download.video.title)
            }
        } catch (_: CancellationSignal) {
            part.delete()
            Downloads.clearCancelled(id)
            Downloads.remove(id)
        } catch (e: Exception) {
            part.delete()
            if (Downloads.isCancelled(id)) {
                Downloads.clearCancelled(id)
                Downloads.remove(id)
            } else {
                Downloads.put(
                    download.copy(
                        status = DownloadStatus.FAILED,
                        error = e.message ?: "Download failed"
                    )
                )
                notifyFailed(id, download.video.title)
            }
        }
    }

    override fun onDestroy() {
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // --- notifications -----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Offline video downloads" }
            )
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, DownloadsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(
        title: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(contentIntent())
            .build()

    private fun updateNotification(title: String, percent: Int, indeterminate: Boolean) {
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(title, percent, indeterminate))
    }

    private fun notifyComplete(videoId: Int, title: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(doneNotificationId(videoId), n)
    }

    private fun notifyFailed(videoId: Int, title: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download failed")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(doneNotificationId(videoId), n)
    }

    // Per-video terminal-notification id, offset off the ongoing-progress id so
    // it never collides with it. Keyed on the video id (not the title) so two
    // videos that share a title don't overwrite each other's complete/failed
    // notification.
    private fun doneNotificationId(videoId: Int): Int = NOTIFICATION_ID + 1 + videoId

    /** Internal signal used to unwind the read loop on cancellation. */
    private class CancellationSignal : Exception()

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4242

        // The worker drains Downloads.nextQueued(), so the start intent only
        // needs to wake the service — the specific id isn't read here.
        fun start(context: Context, videoId: Int) {
            val intent = Intent(context, VideoDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
