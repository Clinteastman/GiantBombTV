package com.giantbomb.tv.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("giantbomb_tv", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString("api_key", null)
        set(value) = prefs.edit().putString("api_key", value).apply()

    var isPremium: Boolean
        get() = prefs.getBoolean("is_premium", false)
        set(value) = prefs.edit().putBoolean("is_premium", value).apply()

    /** Preferred quality: "auto", "1080p", "720p", "480p", "360p" */
    var preferredQuality: String
        get() = prefs.getString("preferred_quality", "auto") ?: "auto"
        set(value) = prefs.edit().putString("preferred_quality", value).apply()

    /** Favourite show IDs - these get pinned to the top of the browse list */
    fun getFavouriteShows(): Set<Int> {
        return prefs.getStringSet("favourite_shows", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }

    fun toggleFavouriteShow(showId: Int): Boolean {
        val current = prefs.getStringSet("favourite_shows", emptySet())?.toMutableSet() ?: mutableSetOf()
        val idStr = showId.toString()
        val isFavourite = if (current.contains(idStr)) {
            current.remove(idStr)
            false
        } else {
            current.add(idStr)
            true
        }
        prefs.edit().putStringSet("favourite_shows", current).apply()
        return isFavourite
    }

    fun isFavouriteShow(showId: Int): Boolean {
        return prefs.getStringSet("favourite_shows", emptySet())
            ?.contains(showId.toString()) == true
    }

    companion object {
        val QUALITY_OPTIONS = listOf("auto", "1080p", "720p", "480p", "360p")

        fun qualityLabel(value: String): String = when (value) {
            "auto" -> "Auto (HLS)"
            else -> value
        }
    }
}
