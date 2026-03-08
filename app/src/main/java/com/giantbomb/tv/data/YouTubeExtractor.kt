package com.giantbomb.tv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Lightweight YouTube stream URL extractor.
 * Extracts direct video/audio stream URLs from YouTube video IDs or URLs.
 */
class YouTubeExtractor {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** Extract video ID from various YouTube URL formats */
        fun extractVideoId(url: String): String? {
            // Already a bare ID
            if (url.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return url

            val patterns = listOf(
                Pattern.compile("(?:v=|/v/|youtu\\.be/)([a-zA-Z0-9_-]{11})"),
                Pattern.compile("/live/([a-zA-Z0-9_-]{11})"),
                Pattern.compile("embed/([a-zA-Z0-9_-]{11})"),
                Pattern.compile("/shorts/([a-zA-Z0-9_-]{11})")
            )
            for (p in patterns) {
                val m = p.matcher(url)
                if (m.find()) return m.group(1)
            }
            return null
        }
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val quality: String,
        val qualityLabel: String?,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val isAdaptive: Boolean,
        val hasAudio: Boolean,
        val hasVideo: Boolean
    )

    data class YouTubeResult(
        val title: String,
        val duration: Long, // seconds
        val streams: List<StreamInfo>,
        val hlsUrl: String? = null
    )

    /**
     * Extract stream URLs for a YouTube video.
     * Tries multiple client types with known workarounds.
     */
    suspend fun extract(videoIdOrUrl: String): Result<YouTubeResult> = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoIdOrUrl)
            ?: return@withContext Result.failure(Exception("Invalid YouTube URL: $videoIdOrUrl"))

        Log.d(TAG, "Extracting streams for video ID: $videoId")

        // Try Android client with CgIQBg bypass params (NewPipe's workaround)
        val androidResult = tryExtract(videoId, ClientType.ANDROID)
        if (androidResult.isSuccess) return@withContext androidResult

        // Try iOS client — often less restricted
        val iosResult = tryExtract(videoId, ClientType.IOS)
        if (iosResult.isSuccess) return@withContext iosResult

        // Try WEB client — may require signature deciphering but worth trying
        val webResult = tryExtract(videoId, ClientType.WEB)
        if (webResult.isSuccess) return@withContext webResult

        // Try MWEB (mobile web) — sometimes works when others don't
        val mwebResult = tryExtract(videoId, ClientType.MWEB)
        if (mwebResult.isSuccess) return@withContext mwebResult

        Result.failure(Exception("Failed to extract streams from all client types"))
    }

    private enum class ClientType {
        ANDROID, IOS, WEB, MWEB
    }

    private fun tryExtract(videoId: String, clientType: ClientType): Result<YouTubeResult> {
        try {
            val body = buildRequestBody(videoId, clientType)
            val userAgent = when (clientType) {
                ClientType.ANDROID -> "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"
                ClientType.IOS -> "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; en_US)"
                ClientType.WEB -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                ClientType.MWEB -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }

            val clientName = when (clientType) {
                ClientType.ANDROID -> "3"
                ClientType.IOS -> "5"
                ClientType.WEB -> "1"
                ClientType.MWEB -> "2"
            }

            val clientVersion = when (clientType) {
                ClientType.ANDROID -> "20.10.38"
                ClientType.IOS -> "20.10.4"
                ClientType.WEB -> "2.20250301.00.00"
                ClientType.MWEB -> "2.20250301.00.00"
            }

            val requestBuilder = Request.Builder()
                .url(PLAYER_URL)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", clientName)
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")

            requestBuilder.post(body.toString().toRequestBody("application/json".toMediaType()))

            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val text = response.use { it.body?.string() ?: "" }

            if (code != 200) {
                Log.w(TAG, "HTTP $code from $clientType client")
                return Result.failure(Exception("HTTP $code"))
            }

            val json = JSONObject(text)

            // Check for playability errors
            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != "OK") {
                val reason = playability?.optString("reason")
                    ?: playability?.optJSONArray("messages")?.optString(0)
                    ?: "Unknown error"
                Log.w(TAG, "$clientType: Playability status=$status reason=$reason")
                return Result.failure(Exception(reason))
            }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: ""
            val duration = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
            val isLive = videoDetails?.optBoolean("isLiveContent", false) ?: false

            val streamingData = json.optJSONObject("streamingData")
                ?: return Result.failure(Exception("No streaming data"))

            val hlsUrl = streamingData.optString("hlsManifestUrl", null)
                ?.takeIf { it.isNotEmpty() }

            val streams = mutableListOf<StreamInfo>()

            // Combined (muxed) formats — have both video and audio
            streamingData.optJSONArray("formats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parseStream(arr.getJSONObject(i), adaptive = false)?.let { streams.add(it) }
                }
            }

            // Adaptive (separate audio/video) formats
            streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parseStream(arr.getJSONObject(i), adaptive = true)?.let { streams.add(it) }
                }
            }

            Log.d(TAG, "$clientType: Found ${streams.size} streams (${streams.count { !it.isAdaptive }} muxed), HLS=${hlsUrl != null}, live=$isLive")

            if (streams.isEmpty() && hlsUrl == null) {
                return Result.failure(Exception("No usable streams found"))
            }

            return Result.success(YouTubeResult(title, duration, streams, hlsUrl))

        } catch (e: Exception) {
            Log.w(TAG, "$clientType extraction failed: ${e.message}")
            return Result.failure(e)
        }
    }

    private fun buildRequestBody(videoId: String, clientType: ClientType): JSONObject {
        val clientObj = when (clientType) {
            ClientType.ANDROID -> JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "20.10.38")
                put("androidSdkVersion", 34)
                put("osName", "Android")
                put("osVersion", "14")
                put("platform", "MOBILE")
            }
            ClientType.IOS -> JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "20.10.4")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.3.2.22D82")
                put("platform", "MOBILE")
            }
            ClientType.WEB -> JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20250301.00.00")
                put("platform", "DESKTOP")
            }
            ClientType.MWEB -> JSONObject().apply {
                put("clientName", "MWEB")
                put("clientVersion", "2.20250301.00.00")
                put("platform", "MOBILE")
            }
        }
        clientObj.put("hl", "en")
        clientObj.put("gl", "US")

        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientObj)
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // CgIQBg bypass — NewPipe's workaround for Android client integrity checks
            if (clientType == ClientType.ANDROID) {
                put("params", "CgIQBg")
            }
        }
    }

    private fun parseStream(obj: JSONObject, adaptive: Boolean): StreamInfo? {
        // Need a direct URL — skip cipher-protected streams (signatureCipher field)
        val url = obj.optString("url", null)
        if (url.isNullOrEmpty()) return null

        val mimeType = obj.optString("mimeType", "")
        val hasVideo = mimeType.startsWith("video/")
        val hasAudio = mimeType.startsWith("audio/") || (!adaptive && mimeType.contains("video/"))
        val quality = obj.optString("quality", "")
        val qualityLabel = obj.optString("qualityLabel", null)
        val width = obj.optInt("width", 0)
        val height = obj.optInt("height", 0)
        val bitrate = obj.optInt("bitrate", 0)

        return StreamInfo(
            url = url,
            mimeType = mimeType,
            quality = quality,
            qualityLabel = qualityLabel,
            width = width,
            height = height,
            bitrate = bitrate,
            isAdaptive = adaptive,
            hasAudio = hasAudio,
            hasVideo = hasVideo
        )
    }
}
