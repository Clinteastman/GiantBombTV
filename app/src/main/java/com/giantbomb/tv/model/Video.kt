package com.giantbomb.tv.model

import java.io.Serializable

data class Video(
    val id: Int,
    val slug: String,
    val title: String,
    val description: String?,
    val publishDate: String,
    val posterUrl: String?,
    val premium: Boolean,
    val showId: Int?,
    val showTitle: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int = 0,
    val progressPercent: Int = 0,
    val isFallbackThumb: Boolean = false,
    val watched: Boolean = false,
    /** "premium" or "supporter" (Premium 64). See [Membership]. */
    val requiredTier: String = Membership.TIER_PREMIUM
) : Serializable

data class Show(
    val id: Int,
    val slug: String,
    val title: String,
    val deck: String,
    val active: Boolean,
    val posterUrl: String?,
    val logoUrl: String?
) : Serializable

data class PlaybackInfo(
    val videoId: Int,
    val title: String,
    val hlsUrl: String?,
    val mp4s: List<Mp4Source>,
    val duration: Double,
    val posterUrl: String?,
    val youtubeUrl: String? = null,
    /** "premium" or "supporter" (Premium 64). See [Membership]. */
    val requiredTier: String = Membership.TIER_PREMIUM
) : Serializable {
    /** True when the response held nothing the player can use. */
    val hasNoSource: Boolean
        get() = hlsUrl.isNullOrEmpty() && mp4s.isEmpty() && youtubeUrl.isNullOrEmpty()
}

data class Mp4Source(
    val url: String,
    val width: Int,
    val height: Int,
    val label: String
) : Serializable

data class ProgressEntry(
    val videoId: Int,
    val currentTime: Double,
    val duration: Double,
    val percentComplete: Int
) : Serializable

data class UpcomingStream(
    val type: String,
    val title: String,
    val image: String?,
    val date: String,
    val premium: Boolean,
    val isLive: Boolean = false
) : Serializable

data class UpcomingResponse(
    val liveNow: UpcomingStream?,
    val upcoming: List<UpcomingStream>
) : Serializable
