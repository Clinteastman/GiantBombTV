package com.giantbomb.tv.ui

/**
 * Keeps one built outline per glyph and size. Reusing the same outline keeps
 * its identity stable between draws, so the renderer's path and mask caches
 * hit instead of filling up with a new entry every time the text is drawn.
 */
internal class GlyphOutlineCache<T>(private val maxEntries: Int) {
    private val outlines = HashMap<Long, T>()

    val size: Int get() = outlines.size

    fun get(glyph: Char, scale: Float, build: () -> T): T {
        val key = (glyph.code.toLong() shl 32) or (scale.toRawBits().toLong() and 0xFFFFFFFFL)
        outlines[key]?.let { return it }
        // Only a few text sizes are in use, so a full reset is rare and cheap.
        if (outlines.size >= maxEntries) outlines.clear()
        return build().also { outlines[key] = it }
    }
}
