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

    companion object {
        val QUALITY_OPTIONS = listOf("auto", "1080p", "720p", "480p", "360p")

        fun qualityLabel(value: String): String = when (value) {
            "auto" -> "Auto (HLS)"
            else -> value
        }
    }
}
