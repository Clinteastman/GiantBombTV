package com.giantbomb.tv.data

import android.util.Log
import com.giantbomb.tv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class ApiException(
    val httpCode: Int,
    val endpoint: String,
    val responseSnippet: String,
    val userMessage: String
) : Exception(userMessage) {

    val diagnosticInfo: String
        get() = "HTTP $httpCode on $endpoint | ${responseSnippet.take(200)}"
}

class GiantBombApi(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE
) {

    companion object {
        private const val TAG = "GiantBombApi"
        private const val DEFAULT_BASE = "https://giantbomb.com"
        private const val USER_AGENT = "GBTV"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun friendlyErrorMessage(e: Throwable): String {
            if (e is ApiException) return e.userMessage
            val msg = e.message ?: ""
            return when {
                msg.contains("Unable to resolve host") || msg.contains("No address associated") ->
                    "No internet connection. Check your network and try again."
                msg.contains("timeout") || msg.contains("timed out") ->
                    "Connection timed out. The server may be slow - try again in a moment."
                msg.contains("Connection refused") ->
                    "Could not reach Giant Bomb. The site may be down - try again later."
                else -> "Something went wrong: $msg"
            }
        }
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
                    premium = l.optBoolean("premium", false),
                    isLive = true
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
            // Filter stale items — if the scheduled date is more than 6 hours
            // in the past the stream has almost certainly ended and the API is stale
            val sixHoursAgo = System.currentTimeMillis() - 6 * 60 * 60 * 1000
            val filteredLiveNow = liveNow?.let {
                val dateMs = com.giantbomb.tv.ui.UpcomingCardView.parseDate(it.date)
                if (dateMs > 0L && dateMs < sixHoursAgo) null else it
            }
            val filteredUpcoming = upcoming.filter {
                val dateMs = com.giantbomb.tv.ui.UpcomingCardView.parseDate(it.date)
                dateMs == 0L || dateMs >= sixHoursAgo
            }

            Result.success(UpcomingResponse(liveNow = filteredLiveNow, upcoming = filteredUpcoming))
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
            val images = v.optJSONObject("images")

            val thumbUrl = thumb?.safeString("url")
                ?: thumb?.safeString("medium_url")
                ?: thumb?.safeString("small_url")
                ?: image?.safeString("url")
                ?: image?.safeString("medium_url")
                ?: image?.safeString("small_url")
                ?: v.safeString("poster_url")
                ?: v.safeString("youtube_url")?.let { ytUrl ->
                    // Derive thumbnail from YouTube URL - more reliable than JWPlayer CDN
                    try {
                        val host = URI(ytUrl).host?.lowercase() ?: ""
                        val isYouTube = host == "youtu.be" || host.endsWith("youtube.com")
                        if (!isYouTube) return@let null
                        val videoId = ytUrl.substringAfter("v=", "")
                            .substringBefore("&")
                            .ifEmpty { ytUrl.substringAfterLast("/", "").substringBefore("?") }
                        if (videoId.isNotEmpty()) "https://img.youtube.com/vi/$videoId/hqdefault.jpg" else null
                    } catch (_: Exception) { null }
                }
                ?: images?.safeString("poster")
                ?: images?.optJSONArray("thumbnails")?.let { arr ->
                    val count = arr.length()
                    if (count > 0) {
                        val mid = (count / 2).coerceIn(0, count - 1)
                        arr.optJSONObject(mid)?.safeString("src")
                            ?: arr.optJSONObject(0)?.safeString("src")
                    } else null
                }

            if (thumbUrl == null) {
                Log.w(TAG, "No thumbnail for video: ${v.optString("title")} (id=${v.optInt("id")})" +
                    " thumb=${thumb} image=${image} images=${images} poster=${v.optString("poster_url")}")
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

    private fun logDiagnostics(method: String, path: String, request: Request, response: Response, body: String) {
        if (!com.giantbomb.tv.BuildConfig.DEBUG) return
        val safeUrl = request.url.toString().replace(Regex("api_key=[^&]+"), "api_key=REDACTED")
        Log.w(TAG, "--- DIAGNOSTIC: $method $safeUrl ---")
        Log.w(TAG, "  Status: ${response.code} ${response.message}")

        // Request headers
        val reqHeaders = request.headers.joinToString { "${it.first}: ${it.second}" }
        Log.w(TAG, "  Request headers: $reqHeaders")

        // Response headers (key for diagnosing Cloudflare vs API rejection)
        for (name in response.headers.names()) {
            Log.w(TAG, "  Response header: $name: ${response.header(name)}")
        }

        // Cloudflare signals
        val cfRay = response.header("cf-ray")
        val server = response.header("server")
        val cfCacheStatus = response.header("cf-cache-status")
        Log.w(TAG, "  Server: $server | CF-Ray: $cfRay | CF-Cache: $cfCacheStatus")

        // Body classification
        val isCloudflareChallenge = body.contains("challenge-platform", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("Just a moment", ignoreCase = true)
        val isCloudflareBlock = body.contains("cloudflare", ignoreCase = true) &&
                body.contains("blocked", ignoreCase = true)
        val isJsonResponse = body.trimStart().startsWith("{") || body.trimStart().startsWith("[")

        Log.w(TAG, "  Body type: ${when {
            isCloudflareChallenge -> "CLOUDFLARE_CHALLENGE"
            isCloudflareBlock -> "CLOUDFLARE_BLOCK"
            isJsonResponse -> "JSON"
            else -> "HTML/OTHER"
        }}")
        Log.w(TAG, "  Body (first 1000 chars): ${body.take(1000)}")
        Log.w(TAG, "--- END DIAGNOSTIC ---")
    }

    private fun get(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""
        Log.d(TAG, "GET $path -> $code")

        if (code != 200) {
            Log.e(TAG, "GET $path error ($code): ${text.take(500)}")
            logDiagnostics("GET", path, request, response, text)
            val endpoint = path.substringBefore("?").substringBefore("api_key")
            val isCloudflare = text.contains("cloudflare", ignoreCase = true) ||
                    text.contains("cf-browser-verification", ignoreCase = true) ||
                    text.contains("challenge-platform", ignoreCase = true)
            val userMsg = when {
                code == 403 && isCloudflare ->
                    "Blocked by Cloudflare (403). This usually resolves itself - try again in a few minutes. [CF block on $endpoint]"
                code == 403 ->
                    "Access denied (403). Your API key may be invalid or expired. Try re-entering it in Settings. [403 on $endpoint]"
                code == 401 ->
                    "Not authorized (401). Your API key may be invalid. Try re-entering it in Settings. [401 on $endpoint]"
                code == 429 ->
                    "Too many requests (429). Please wait a minute and try again. [Rate limited on $endpoint]"
                code in 500..599 ->
                    "Giant Bomb server error ($code). The site may be having issues - try again later. [$code on $endpoint]"
                else ->
                    "Request failed ($code). Try again or check your connection. [$code on $endpoint]"
            }
            throw ApiException(
                httpCode = code,
                endpoint = endpoint,
                responseSnippet = text.take(300),
                userMessage = userMsg
            )
        }

        return JSONObject(text)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val jsonType = "application/json".toMediaType()
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""

        if (code != 200) {
            val endpoint = path.substringBefore("?")
            Log.e(TAG, "POST $endpoint error ($code): ${text.take(500)}")
            logDiagnostics("POST", path, request, response, text)
            throw ApiException(code, endpoint, text.take(300),
                "Request failed ($code) on $endpoint. Check your API key or try again.")
        }
        return JSONObject(text)
    }

    private fun delete(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .delete()
            .build()

        val response = client.newCall(request).execute()
        val code = response.code
        val text = response.body?.string() ?: ""

        if (code !in 200..299) {
            val endpoint = path.substringBefore("?")
            Log.e(TAG, "DELETE $endpoint error ($code): ${text.take(500)}")
            logDiagnostics("DELETE", path, request, response, text)
            throw ApiException(code, endpoint, text.take(300),
                "Request failed ($code) on $endpoint. Check your API key or try again.")
        }
        return if (text.isNotEmpty()) JSONObject(text) else JSONObject()
    }
}
