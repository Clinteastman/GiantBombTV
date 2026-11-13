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
 * safe for many images, and the card's own load then skips the network.
 * [allowDecode] tells callers whether decoding ahead (which holds a bitmap)
 * is sensible on this device: false on low-RAM devices.
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

    private companion object {
        const val RECENT_LIMIT = 300
    }
}
