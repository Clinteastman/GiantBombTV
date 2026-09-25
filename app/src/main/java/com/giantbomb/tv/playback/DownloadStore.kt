package com.giantbomb.tv.playback

import android.content.Context
import com.giantbomb.tv.model.Video
import org.json.JSONObject
import java.io.File

/** Status of a single offline download. */
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

/**
 * One offline download. While in flight the byte counters drive the progress
 * UI; once COMPLETED [filePath] points at the playable on-disk MP4.
 */
data class Download(
    val video: Video,
    val url: String,
    val qualityLabel: String,
    val status: DownloadStatus,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val filePath: String? = null,
    val error: String? = null
) {
    val videoId: Int get() = video.id
}

/**
 * On-disk layout for downloaded videos. Everything lives under the app-private
 * external files dir (`getExternalFilesDir`) so no storage permission is needed
 * and the OS reclaims it on uninstall. Each download is a trio:
 *   <id>.mp4   the finished, playable file
 *   <id>.part  the partial file while downloading (renamed to .mp4 on success)
 *   <id>.json  metadata so the Downloads screen / offline player can rebuild
 *              the Video without hitting the network.
 */
object DownloadStore {

    private fun baseDir(context: Context): File {
        // getExternalFilesDir can be null when external storage is unmounted;
        // fall back to internal filesDir so downloads still work.
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "downloads").apply { mkdirs() }
    }

    fun videoFile(context: Context, id: Int): File = File(baseDir(context), "$id.mp4")
    fun partFile(context: Context, id: Int): File = File(baseDir(context), "$id.part")
    private fun metaFile(context: Context, id: Int): File = File(baseDir(context), "$id.json")
    private fun pendingFile(context: Context, id: Int): File = File(baseDir(context), "$id.pending.json")
    private fun validatorFile(context: Context, id: Int): File = File(baseDir(context), "$id.validator")

    /**
     * ETag or Last-Modified of the server copy the partial file came from.
     * A resume sends it as If-Range, so a changed file restarts from zero
     * instead of appending new bytes to an old prefix.
     */
    fun readValidator(context: Context, id: Int): String? =
        validatorFile(context, id).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun writeValidator(context: Context, id: Int, validator: String?) {
        val file = validatorFile(context, id)
        if (validator.isNullOrBlank()) file.delete() else file.writeText(validator)
    }

    fun isDownloaded(context: Context, id: Int): Boolean =
        videoFile(context, id).exists() && metaFile(context, id).exists()

    fun writeMeta(context: Context, download: Download) {
        val json = downloadToJson(download)
        metaFile(context, download.videoId).writeText(json.toString())
        pendingFile(context, download.videoId).delete()
        validatorFile(context, download.videoId).delete()
    }

    fun writePending(context: Context, download: Download) {
        val json = downloadToJson(download)
        // Remember failures so a relaunch shows them as failed (retry is the
        // user's choice) instead of silently re-queueing them every time.
        if (download.status == DownloadStatus.FAILED) {
            json.put("failed", true)
            json.put("error", download.error ?: "Download failed")
        }
        pendingFile(context, download.videoId).writeText(json.toString())
    }

    private fun downloadToJson(download: Download): JSONObject = JSONObject().apply {
            put("video", videoToJson(download.video))
            put("url", download.url)
            put("qualityLabel", download.qualityLabel)
            put("totalBytes", download.totalBytes)
    }

    /** Rebuilds a COMPLETED [Download] from its on-disk metadata, or null. */
    fun readMeta(context: Context, id: Int): Download? {
        val meta = metaFile(context, id)
        val mp4 = videoFile(context, id)
        if (!meta.exists() || !mp4.exists()) return null
        return try {
            val json = JSONObject(meta.readText())
            val video = videoFromJson(json.getJSONObject("video"))
            Download(
                video = video,
                url = json.optString("url"),
                qualityLabel = json.optString("qualityLabel"),
                status = DownloadStatus.COMPLETED,
                progressPercent = 100,
                bytesDownloaded = mp4.length(),
                totalBytes = json.optLong("totalBytes", mp4.length()),
                filePath = mp4.absolutePath
            )
        } catch (_: Exception) {
            null
        }
    }

    /** All finished downloads, newest file first. */
    fun listCompleted(context: Context): List<Download> {
        val dir = baseDir(context)
        val ids = dir.listFiles { f -> f.extension == "mp4" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?: emptyList()
        return ids.mapNotNull { readMeta(context, it) }
            .sortedByDescending { File(it.filePath ?: "").lastModified() }
    }

    fun deletePending(context: Context, id: Int) {
        pendingFile(context, id).delete()
        validatorFile(context, id).delete()
    }

    fun listPending(context: Context): List<Download> =
        baseDir(context).listFiles { file -> file.name.endsWith(".pending.json") }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    val failed = json.optBoolean("failed", false)
                    Download(
                        video = videoFromJson(json.getJSONObject("video")),
                        url = json.getString("url"),
                        qualityLabel = json.optString("qualityLabel"),
                        status = if (failed) DownloadStatus.FAILED else DownloadStatus.QUEUED,
                        error = if (failed) json.optString("error", "Download failed") else null,
                        bytesDownloaded = partFile(
                            context,
                            json.getJSONObject("video").getInt("id")
                        ).length(),
                        totalBytes = json.optLong("totalBytes", 0L)
                    )
                }.getOrNull()
            }
            ?: emptyList()

    fun delete(context: Context, id: Int) {
        videoFile(context, id).delete()
        partFile(context, id).delete()
        metaFile(context, id).delete()
        pendingFile(context, id).delete()
        validatorFile(context, id).delete()
    }

    // --- (de)serialisation -------------------------------------------------

    private fun videoToJson(v: Video): JSONObject = JSONObject().apply {
        put("id", v.id)
        put("slug", v.slug)
        put("title", v.title)
        putOpt("description", v.description)
        put("publishDate", v.publishDate)
        putOpt("posterUrl", v.posterUrl)
        put("premium", v.premium)
        putOpt("showId", v.showId)
        putOpt("showTitle", v.showTitle)
        putOpt("author", v.author)
        putOpt("thumbnailUrl", v.thumbnailUrl)
        put("durationSeconds", v.durationSeconds)
    }

    private fun videoFromJson(o: JSONObject): Video = Video(
        id = o.getInt("id"),
        slug = o.optString("slug"),
        title = o.optString("title"),
        description = o.optStringOrNull("description"),
        publishDate = o.optString("publishDate"),
        posterUrl = o.optStringOrNull("posterUrl"),
        premium = o.optBoolean("premium"),
        showId = if (o.isNull("showId")) null else o.optInt("showId"),
        showTitle = o.optStringOrNull("showTitle"),
        author = o.optStringOrNull("author"),
        thumbnailUrl = o.optStringOrNull("thumbnailUrl"),
        durationSeconds = o.optInt("durationSeconds")
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)
}
