package com.giantbomb.tv.data

import android.util.Log
import com.giantbomb.tv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class GiantBombApi(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE
) {

    companion object {
        private const val TAG = "GiantBombApi"
        private const val DEFAULT_BASE = "https://giantbomb.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun validateKey(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = get("/api/public/key-status?api_key=$apiKey")
            Result.success(json.optBoolean("is_premium", false))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideos(
        limit: Int = 20,
        offset: Int = 0,
        showId: Int? = null,
        premium: Boolean? = null,
        query: String? = null
    ): Result<List<Video>> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf(
                "api_key=$apiKey",
                "limit=$limit",
                "offset=$offset",
                "images=true"
            )
            showId?.let { params.add("video_show=$it") }
            premium?.let { params.add("premium=$it") }
            query?.let { params.add("q=${java.net.URLEncoder.encode(it, "UTF-8")}") }

            val json = get("/api/public/videos?${params.joinToString("&")}")
            val results = json.getJSONArray("results")
            Result.success(parseVideos(results))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShows(limit: Int = 100): Result<List<Show>> = withContext(Dispatchers.IO) {
        try {
            val json = get("/api/public/shows?api_key=$apiKey&limit=$limit&sort=latest_video:desc")
            val results = json.getJSONArray("results")
            val shows = mutableListOf<Show>()
            for (i in 0 until results.length()) {
                val s = results.getJSONObject(i)
                shows.add(Show(
                    id = s.getInt("id"),
                    slug = s.optString("slug", ""),
                    title = s.optString("title", ""),
                    deck = s.optString("deck", ""),
                    active = s.optBoolean("active", false),
                    posterUrl = s.optJSONObject("poster_image")?.optString("url"),
                    logoUrl = s.optJSONObject("logo_image")?.optString("url")
                ))
            }
            Result.success(shows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getShowVideos(showId: Int, limit: Int = 100, offset: Int = 0): Result<List<Video>> =
        getVideos(limit = limit, offset = offset, showId = showId)

    suspend fun getPlayback(videoId: Int): Result<PlaybackInfo> = withContext(Dispatchers.IO) {
        try {
            val json = get("/api/public/videos/$videoId/playback?api_key=$apiKey")
            val title = json.optString("title", "")

            val sources = json.optJSONObject("premium") ?: json.optJSONObject("free")

            val hlsUrl = sources?.optString("hls_url", null)
            val duration = sources?.optDouble("duration", 0.0) ?: 0.0
            val poster = sources?.optString("poster", null)

            // YouTube URL (may be a valid URL, or sometimes junk data)
            val rawYt = json.optString("youtube_url", null)
            val youtubeUrl = rawYt?.takeIf { url ->
                try {
                    val host = URI(url).host?.lowercase() ?: return@takeIf false
                    host == "youtu.be" || host.endsWith(".youtube.com") || host == "youtube.com"
                } catch (_: Exception) { false }
            }

            val mp4s = mutableListOf<Mp4Source>()
            sources?.optJSONArray("mp4s")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    mp4s.add(Mp4Source(
                        url = m.getString("url"),
                        width = m.optInt("width", 0),
                        height = m.optInt("height", 0),
                        label = m.optString("label", "")
                    ))
                }
            }

            Result.success(PlaybackInfo(
                videoId = videoId,
                title = title,
                hlsUrl = hlsUrl,
                mp4s = mp4s,
                duration = duration,
                posterUrl = poster,
                youtubeUrl = youtubeUrl
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProgress(): Result<List<ProgressEntry>> = withContext(Dispatchers.IO) {
        try {
            val json = get("/api/public/video-progress?api_key=$apiKey&limit=100")
            val results = json.getJSONArray("results")
            val entries = mutableListOf<ProgressEntry>()
            for (i in 0 until results.length()) {
                val p = results.getJSONObject(i)
                entries.add(ProgressEntry(
                    videoId = p.getInt("video_id"),
                    currentTime = p.getDouble("current_time"),
                    duration = p.getDouble("duration"),
                    percentComplete = p.optInt("percent_complete", 0)
                ))
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveProgress(videoId: Int, currentTime: Double, duration: Double): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("video_id", videoId)
                put("current_time", currentTime)
                put("duration", duration)
            }
            post("/api/public/video-progress?api_key=$apiKey", body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markWatched(videoId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("video_id", videoId) }
            post("/api/public/watched?api_key=$apiKey", body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWatchlist(): Result<List<Video>> = withContext(Dispatchers.IO) {
        try {
            val json = get("/api/public/watchlist?api_key=$apiKey&limit=100&images=true")
            val results = json.getJSONArray("results")
            Result.success(parseVideos(results))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addToWatchlist(videoId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("video_id", videoId) }
            post("/api/public/watchlist?api_key=$apiKey", body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromWatchlist(videoId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            delete("/api/public/watchlist?api_key=$apiKey&video_id=$videoId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUpcoming(): Result<UpcomingResponse> = withContext(Dispatchers.IO) {
        try {
            val json = get("/upcoming_json")
            val liveNow = json.optJSONObject("liveNow")?.let { l ->
                UpcomingStream(
                    type = l.optString("type", ""),
                    title = l.optString("title", ""),
                    image = l.safeString("image"),
                    date = l.optString("date", ""),
                    premium = l.optBoolean("premium", false)
                )
            }
            val upcomingArr = json.optJSONArray("upcoming")
            val upcoming = mutableListOf<UpcomingStream>()
            if (upcomingArr != null) {
                for (i in 0 until upcomingArr.length()) {
                    val u = upcomingArr.getJSONObject(i)
                    upcoming.add(UpcomingStream(
                        type = u.optString("type", ""),
                        title = u.optString("title", ""),
                        image = u.safeString("image"),
                        date = u.optString("date", ""),
                        premium = u.optBoolean("premium", false)
                    ))
                }
            }
            Result.success(UpcomingResponse(liveNow = liveNow, upcoming = upcoming))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun JSONObject.safeString(key: String): String? {
        if (isNull(key)) return null
        val s = optString(key, "")
        return s.ifEmpty { null }?.takeIf { it != "null" }
    }

    private fun parseVideos(results: JSONArray): List<Video> {
        val videos = mutableListOf<Video>()
        for (i in 0 until results.length()) {
            val v = results.getJSONObject(i)
            val show = v.optJSONObject("show")
            val thumb = v.optJSONObject("thumbnail")
            val image = v.optJSONObject("image")

            val thumbUrl = thumb?.safeString("url")
                ?: thumb?.safeString("medium_url")
                ?: thumb?.safeString("small_url")
                ?: image?.safeString("url")
                ?: image?.safeString("medium_url")
                ?: image?.safeString("small_url")
                ?: v.safeString("poster_url")

            if (thumbUrl == null) {
                Log.w(TAG, "No thumbnail for video: ${v.optString("title")} (id=${v.optInt("id")})" +
                    " thumb=${thumb} image=${image} poster=${v.optString("poster_url")}")
            }

            videos.add(Video(
                id = v.getInt("id"),
                slug = v.optString("slug", ""),
                title = v.optString("title", ""),
                description = v.safeString("description"),
                publishDate = v.optString("publish_date", ""),
                posterUrl = v.safeString("poster_url"),
                premium = v.optBoolean("premium", false),
                showId = show?.optInt("id"),
                showTitle = show?.optString("title"),
                author = v.safeString("author"),
                thumbnailUrl = thumbUrl,
                durationSeconds = v.optInt("length_seconds", 0)
            ))
        }
        return videos
    }

    private fun get(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""
        Log.d(TAG, "GET $path -> $code")

        if (code != 200) {
            Log.e(TAG, "GET error: ${text.take(300)}")
            throw Exception("HTTP $code")
        }

        return JSONObject(text)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val jsonType = "application/json".toMediaType()
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""

        if (code != 200) throw Exception("HTTP $code")
        return JSONObject(text)
    }

    private fun delete(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""

        if (code !in 200..299) throw Exception("HTTP $code")
        return if (text.isNotEmpty()) JSONObject(text) else JSONObject()
    }
}
