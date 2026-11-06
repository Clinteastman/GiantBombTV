package com.giantbomb.tv.ui

import android.app.ActivityManager
import android.content.Context
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestManager

/**
 * Fetches thumbnails before their cards reach the screen, at low priority so
 * on-screen cards always load first.
 *
 * [warm] only downloads into Glide's disk cache: no bitmap is kept, so it is
 * safe for many images. [decode] also decodes at the card's exact size so the
 * card gets a memory-cache hit; use it only for the few cards about to show.
 * On low-RAM devices [decode] falls back to [warm].
 */
class ImagePreloader(context: Context, private val requests: RequestManager) {

    val allowDecode: Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice != true

    // Recently requested URLs, so repeated focus changes don't queue the same
    // image again. Glide would dedupe, but each call still builds a request.
    private val recent = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) =
            size > RECENT_LIMIT
    }

    private fun firstTime(tag: String): Boolean {
        if (recent.containsKey(tag)) {
            recent[tag] // refresh its place
            return false
        }
        recent[tag] = Unit
        return true
    }

    fun warm(urls: Iterable<String?>) {
        for (url in urls) {
            if (url.isNullOrEmpty() || !firstTime("w:$url")) continue
            requests.downloadOnly().load(url).priority(Priority.LOW).preload()
        }
    }

    /**
     * Decodes [url] at [width] x [height] with the same transformation the card
     * uses (centre crop, or fit centre for show-poster fallbacks).
     */
    fun decode(url: String?, width: Int, height: Int, fitCenter: Boolean = false) {
        if (url.isNullOrEmpty()) return
        if (!allowDecode) {
            warm(listOf(url))
            return
        }
        if (!firstTime("d:$width:$height:$fitCenter:$url")) return
        val request = requests.load(url).priority(Priority.LOW)
        (if (fitCenter) request.fitCenter() else request.centerCrop()).preload(width, height)
    }

    private companion object {
        const val RECENT_LIMIT = 300
    }
}
