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
    val watched: Boolean = false
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
    val youtubeUrl: String? = null
) : Serializable

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
