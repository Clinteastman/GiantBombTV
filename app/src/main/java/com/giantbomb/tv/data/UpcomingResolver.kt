package com.giantbomb.tv.data

import com.giantbomb.tv.model.UpcomingStream

/**
 * Pure functions that translate the raw `/upcoming_json` feed + Twitch live
 * signal into the final UpcomingResponse the UI consumes. Lives outside of
 * [GiantBombApi] so it can be exercised without OkHttp / coroutines / Android
 * SimpleDateFormat in unit tests.
 */
internal object UpcomingResolver {

    /**
     * Reconcile the GB feed's `liveNow` field with Twitch's authoritative live
     * signal for the giantbomb channel:
     *   - Twitch unknown (null) → fall back to whatever the GB feed claims.
     *   - Twitch live           → keep liveNow but prefer Twitch's title +
     *                              preview image (the GB feed is sometimes
     *                              still showing the previous show).
     *   - Twitch offline        → drop liveNow regardless of what the GB feed
     *                              says, so finished streams stop sticking.
     */
    fun resolveLiveNow(
        apiLiveNow: UpcomingStream?,
        twitchStatus: TwitchExtractor.LiveStatus?
    ): UpcomingStream? = when {
        twitchStatus == null -> apiLiveNow
        twitchStatus.isLive -> {
            val base = apiLiveNow ?: UpcomingStream(
                type = "live", title = "", image = null, date = "",
                premium = false, isLive = true
            )
            base.copy(
                title = twitchStatus.title?.takeIf { it.isNotBlank() }
                    ?: base.title.ifBlank { "Giant Bomb Live" },
                image = twitchStatus.previewImageUrl ?: base.image,
                isLive = true
            )
        }
        else -> null
    }

    /**
     * Filter retired / duplicated upcoming entries:
     *   - When liveNow is set: drop anything scheduled before `nowMs` (the
     *     live show plus any other slightly-past entries the API hasn't
     *     cleaned up).
     *   - When liveNow is null: keep a 30-minute grace so a stream scheduled
     *     to start at the top of the hour doesn't disappear during the few
     *     minutes before Twitch flips on.
     *   - In either case, dedup by lowercased trimmed title against the live
     *     show so the "starting soon" duplicate doesn't appear alongside the
     *     live card when the feed's scheduled time is wrong.
     *
     * `parseDate` is injected so tests can supply a deterministic clock /
     * format-independent stub instead of depending on
     * `UpcomingCardView.parseDate` (which wraps Android's SimpleDateFormat).
     */
    fun filterUpcoming(
        upcoming: List<UpcomingStream>,
        resolvedLiveNow: UpcomingStream?,
        nowMs: Long,
        parseDate: (String) -> Long
    ): List<UpcomingStream> {
        val cutoff = if (resolvedLiveNow != null) nowMs else (nowMs - 30 * 60 * 1000)
        val liveTitle = resolvedLiveNow?.title?.trim()?.lowercase().orEmpty()
        return upcoming.filter {
            val dateMs = parseDate(it.date)
            if (dateMs != 0L && dateMs < cutoff) return@filter false
            if (liveTitle.isNotEmpty() && it.title.trim().lowercase() == liveTitle) return@filter false
            true
        }
    }
}
