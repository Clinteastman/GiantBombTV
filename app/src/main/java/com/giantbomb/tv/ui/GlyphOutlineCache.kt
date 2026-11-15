package com.giantbomb.tv.ui

/**
 * Keeps one built outline (or glow bitmap) per glyph and size, so each is
 * made once and reused instead of rebuilt on every draw.
 */
internal class GlyphOutlineCache<T>(private val maxEntries: Int) {
    private val outlines = HashMap<Long, T>()

    val size: Int get() = outlines.size

    /** [variant] tells apart entries of one glyph and size, e.g. glow radius. */
    fun get(glyph: Char, scale: Float, variant: Int = 0, build: () -> T): T {
        val key = (glyph.code.toLong() shl 48) or
            ((variant.toLong() and 0xFFFFL) shl 32) or
            (scale.toRawBits().toLong() and 0xFFFFFFFFL)
        outlines[key]?.let { return it }
        // Only a few text sizes are in use, so a full reset is rare and cheap.
        if (outlines.size >= maxEntries) outlines.clear()
        return build().also { outlines[key] = it }
    }
}
