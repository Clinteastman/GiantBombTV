package com.giantbomb.tv.ui

import kotlin.math.ceil

/** Pure geometry for the Neon vector letters. */
internal object GlyphGeometry {

    /**
     * Turns each glyph stroke (x,y pairs on the 0..8 grid, y up) into line
     * segments for Canvas.drawLines, scaled and with y pointing down from the
     * baseline at 0.
     */
    fun segments(strokes: List<FloatArray>, sx: Float, sy: Float): FloatArray {
        var count = 0
        for (stroke in strokes) count += maxOf(0, stroke.size / 2 - 1) * 4
        val out = FloatArray(count)
        var o = 0
        for (stroke in strokes) {
            var i = 0
            while (i + 3 < stroke.size) {
                out[o++] = stroke[i] * sx
                out[o++] = -stroke[i + 1] * sy
                out[o++] = stroke[i + 2] * sx
                out[o++] = -stroke[i + 3] * sy
                i += 2
            }
        }
        return out
    }

    /** Pixels of margin a glow bitmap needs around the glyph's lines. */
    fun glowPad(strokeWidth: Float, radius: Float): Int {
        // Skia's radius to sigma conversion; a Gaussian is spent by 3 sigma.
        val sigma = if (radius > 0f) 0.57735f * radius + 0.5f else 0f
        return ceil(strokeWidth / 2f + 3f * sigma).toInt() + 1
    }
}
