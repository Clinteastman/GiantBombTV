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

/**
 * Extracts HLS stream URLs from Twitch channels using the public GQL API.
 */
class TwitchExtractor {

    companion object {
        private const val TAG = "TwitchExtractor"
        private const val GQL_URL = "https://gql.twitch.tv/gql"
        private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        // Twitch login rules: 4–25 chars, alnum + underscore. We use plain string
        // interpolation into an inline GQL query, so anything outside this set
        // could break out and inject a different operation. Match the Twitch
        // login constraint exactly — see https://help.twitch.tv/s/article/twitch-username-policy
        private val LOGIN_REGEX = Regex("^[a-zA-Z0-9_]{4,25}$")

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private fun requireValidLogin(channel: String) {
            require(LOGIN_REGEX.matches(channel)) {
                "Invalid Twitch login: $channel (expected 4–25 alphanumeric/underscore chars)"
            }
        }
    }

    data class TwitchStream(
        val title: String,
        val hlsUrl: String
    )

    data class LiveStatus(
        val isLive: Boolean,
        val title: String?,
        val previewImageUrl: String?
    )

    /**
     * Returns the live state of [channel] using Twitch's public GQL StreamMetadata query.
     * Returns null if the check itself failed (network error, etc.) — callers should
     * treat this as "unknown" and fall back to whatever the upstream feed claims.
     * When [LiveStatus.isLive] is true, [previewImageUrl] points at Twitch's public
     * live preview frame, cache-busted per minute so Glide refetches the JPG.
     */
    suspend fun getLiveStatus(channel: String): LiveStatus? = withContext(Dispatchers.IO) {
        requireValidLogin(channel)
        try {
            // Inline query rather than a persisted-query hash: Twitch periodically
            // rotates persisted hashes and returns PersistedQueryNotFound, which used
            // to silently flip the channel to "not live." Inline queries don't rotate.
            // Channel is regex-validated above; the interpolation here is safe.
            val body = JSONObject().apply {
                put("query", "query { user(login: \"$channel\") { stream { id title type } } }")
            }
            val request = Request.Builder()
                .url(GQL_URL)
                .header("Client-ID", CLIENT_ID)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val text = response.use { it.body?.string() ?: "" }
            parseLiveStatusResponse(
                channel = channel,
                httpCode = httpCode,
                body = text,
                nowMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Live status check failed for $channel: ${e.message}")
            null
        }
    }

    /**
     * Pure GQL-response → LiveStatus translation, factored out for unit testing
     * (no OkHttp, no system clock). Mirrors the contract documented on
     * [getLiveStatus]: null = unknown (network/GQL errors), false = offline,
     * true = live with title + preview URL.
     */
    internal fun parseLiveStatusResponse(
        channel: String,
        httpCode: Int,
        body: String,
        nowMs: Long
    ): LiveStatus? {
        if (httpCode != 200) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        if (root.has("errors")) {
            Log.w(TAG, "Live status GQL errors for $channel: ${root.optJSONArray("errors")}")
            return null
        }
        val stream = root.optJSONObject("data")
            ?.optJSONObject("user")
            ?.optJSONObject("stream")
        return if (stream == null || stream.toString() == "null") {
            LiveStatus(isLive = false, title = null, previewImageUrl = null)
        } else {
            val title = stream.optString("title", "").ifBlank { null }
            val minuteBucket = nowMs / 60_000
            val preview = "https://static-cdn.jtvnw.net/previews-ttv/live_user_$channel-1280x720.jpg?t=$minuteBucket"
            LiveStatus(isLive = true, title = title, previewImageUrl = preview)
        }
    }

    /**
     * Get the HLS stream URL for a live Twitch channel.
     * Returns failure if the channel is not live.
     */
    suspend fun extract(channel: String): Result<TwitchStream> = withContext(Dispatchers.IO) {
        try {
            requireValidLogin(channel)
            // Step 1: Get a playback access token via GQL
            val tokenBody = JSONObject().apply {
                put("operationName", "PlaybackAccessToken")
                put("extensions", JSONObject().apply {
                    put("persistedQuery", JSONObject().apply {
                        put("version", 1)
                        put("sha256Hash", "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9")
                    })
                })
                put("variables", JSONObject().apply {
                    put("login", channel)
                    put("isLive", true)
                    put("isVod", false)
                    put("vodID", "")
                    put("playerType", "site")
                    put("platform", "web")
                })
            }

            val tokenRequest = Request.Builder()
                .url(GQL_URL)
                .header("Client-ID", CLIENT_ID)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .post(tokenBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val tokenResponse = client.newCall(tokenRequest).execute()
            val tokenText = tokenResponse.use { it.body?.string() ?: "" }

            if (tokenResponse.code != 200) {
                Log.e(TAG, "Token request failed: HTTP ${tokenResponse.code}")
                return@withContext Result.failure(Exception("HTTP ${tokenResponse.code}"))
            }

            val tokenJson = JSONObject(tokenText)
            val tokenData = tokenJson.optJSONObject("data")
                ?.optJSONObject("streamPlaybackAccessToken")
                ?: return@withContext Result.failure(Exception("Channel not live or not found"))

            val token = tokenData.getString("value")
            val sig = tokenData.getString("signature")

            // Step 2: Get stream title via a separate GQL query
            val title = getStreamTitle(channel)

            // Step 3: Build the usher HLS URL
            val hlsUrl = buildString {
                append("https://usher.ttvnw.net/api/channel/hls/")
                append(channel)
                append(".m3u8")
                append("?sig=").append(sig)
                append("&token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
                append("&allow_source=true")
                append("&allow_audio_only=true")
                append("&fast_bread=true")
                append("&p=").append((Math.random() * 999999).toInt())
                append("&player_backend=mediaplayer")
                append("&playlist_include_framerate=true")
                append("&reassignments_supported=true")
                append("&supported_codecs=avc1")
                append("&cdm=wv")
            }

            Log.d(TAG, "Got HLS URL for channel: $channel")
            Result.success(TwitchStream(title = title, hlsUrl = hlsUrl))

        } catch (e: Exception) {
            Log.e(TAG, "Extract failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun getStreamTitle(channel: String): String {
        try {
            requireValidLogin(channel)
            val body = JSONObject().apply {
                put("query", "query { user(login: \"$channel\") { stream { title } } }")
            }

            val request = Request.Builder()
                .url(GQL_URL)
                .header("Client-ID", CLIENT_ID)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val text = response.use { it.body?.string() ?: "" }

            if (response.code == 200) {
                val stream = JSONObject(text).optJSONObject("data")
                    ?.optJSONObject("user")
                    ?.optJSONObject("stream")
                if (stream != null) {
                    return stream.optString("title", "Giant Bomb Live")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get stream title: ${e.message}")
        }
        return "Giant Bomb Live"
    }
}
