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

    /**
     * Pinned show IDs in user-defined order. The browse screen renders these
     * as their own per-show rows at the top of the Pinned Shows section.
     * Stored as a CSV string so the order persists; the pre-existing Set-based
     * `favourite_shows` entry is migrated on first read and then cleared.
     */
    fun getPinnedShowIds(): List<Int> {
        val csv = prefs.getString("pinned_show_ids", null)
        if (csv != null) {
            return csv.split(",").mapNotNull { it.toIntOrNull() }
        }
        // One-time migration from the older Set-based prefs key.
        val legacy = prefs.getStringSet("favourite_shows", emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        if (legacy.isNotEmpty()) {
            prefs.edit()
                .putString("pinned_show_ids", legacy.joinToString(","))
                .remove("favourite_shows")
                .apply()
        }
        return legacy
    }

    fun togglePinnedShow(showId: Int): Boolean {
        val current = getPinnedShowIds().toMutableList()
        val isPinned = if (showId in current) {
            current.remove(showId)
            false
        } else {
            current.add(showId)
            true
        }
        prefs.edit()
            .putString("pinned_show_ids", current.joinToString(","))
            .apply()
        return isPinned
    }

    fun isPinnedShow(showId: Int): Boolean = showId in getPinnedShowIds()

    fun setPinnedShowOrder(ids: List<Int>) {
        prefs.edit()
            .putString("pinned_show_ids", ids.joinToString(","))
            .apply()
    }

    /**
     * Ordered list of section IDs the browse screen renders top-to-bottom. The user
     * can reorder this in Settings; new defaults are appended on upgrade so newly-
     * introduced sections don't get silently dropped.
     */
    fun getSectionOrder(): List<String> {
        val stored = prefs.getString("section_order", null)
            ?: return DEFAULT_SECTION_ORDER
        val parts = stored.split(",").filter { it.isNotEmpty() && it in ALL_SECTIONS }
        val missing = DEFAULT_SECTION_ORDER - parts.toSet()
        return parts + missing
    }

    fun setSectionOrder(order: List<String>) {
        val sanitised = order.filter { it in ALL_SECTIONS }.distinct()
        prefs.edit().putString("section_order", sanitised.joinToString(",")).apply()
    }

    /** Sections the user has chosen to hide entirely from the browse screen. */
    fun getHiddenSections(): Set<String> {
        return prefs.getStringSet("hidden_sections", emptySet())?.toSet() ?: emptySet()
    }

    fun setHiddenSections(hidden: Set<String>) {
        prefs.edit().putStringSet("hidden_sections", hidden.filter { it in ALL_SECTIONS }.toSet()).apply()
    }

    companion object {
        val QUALITY_OPTIONS = listOf("auto", "1080p", "720p", "480p", "360p")

        fun qualityLabel(value: String): String = when (value) {
            "auto" -> "Auto (HLS)"
            else -> value
        }

        // Section IDs used by the browse screen. Stored in prefs (so don't rename)
        // and surfaced in the Customize Browse settings UI.
        const val SECTION_LIVE = "live"
        const val SECTION_CONTINUE = "continue"
        const val SECTION_RECENT = "recent"
        const val SECTION_PINNED = "pinned"
        const val SECTION_ACTIVE_SHOWS = "active_shows"
        const val SECTION_PREMIUM = "premium"
        const val SECTION_LEGACY = "legacy"
        const val SECTION_WATCHLIST = "watchlist"
        const val SECTION_SETTINGS = "settings"

        // Watchlist hoisted to position 3 by default — primary-flow friction
        // per user feedback, beats burying it after the show rows.
        val DEFAULT_SECTION_ORDER: List<String> = listOf(
            SECTION_LIVE,
            SECTION_CONTINUE,
            SECTION_WATCHLIST,
            SECTION_RECENT,
            SECTION_PINNED,
            SECTION_ACTIVE_SHOWS,
            SECTION_PREMIUM,
            SECTION_LEGACY,
            SECTION_SETTINGS,
        )

        val ALL_SECTIONS: Set<String> = DEFAULT_SECTION_ORDER.toSet()

        fun sectionLabel(id: String): String = when (id) {
            SECTION_LIVE -> "Upcoming & Live"
            SECTION_CONTINUE -> "Continue Watching"
            SECTION_RECENT -> "Recent"
            SECTION_PINNED -> "Pinned Shows"
            SECTION_ACTIVE_SHOWS -> "Shows"
            SECTION_PREMIUM -> "Premium"
            SECTION_LEGACY -> "Legacy Shows"
            SECTION_WATCHLIST -> "Watchlist"
            SECTION_SETTINGS -> "Settings"
            else -> id
        }
    }
}
