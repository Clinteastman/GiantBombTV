package com.giantbomb.tv.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon letters are drawn as separate straight lines (cheap rectangles to the
 * GPU) and a pre-blurred glow bitmap, never as a blurred path.
 */
class GlyphGeometryTest {

    @Test
    fun polylineBecomesConnectedSegmentsWithYUp() {
        // One stroke 0,0 -> 8,0 -> 8,8 on the glyph grid.
        val strokes = listOf(floatArrayOf(0f, 0f, 8f, 0f, 8f, 8f))
        val lines = GlyphGeometry.segments(strokes, sx = 2f, sy = 3f)
        assertArrayEquals(
            floatArrayOf(0f, 0f, 16f, 0f, 16f, 0f, 16f, -24f),
            lines, 0.0001f
        )
    }

    @Test
    fun separateStrokesStayApart() {
        val strokes = listOf(floatArrayOf(0f, 0f, 8f, 0f), floatArrayOf(4f, 0f, 4f, 8f))
        val lines = GlyphGeometry.segments(strokes, sx = 1f, sy = 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 8f, 0f, 4f, 0f, 4f, -8f), lines, 0.0001f)
    }

    @Test
    fun glowPadCoversTheWholeBlur() {
        // Skia turns a blur radius into sigma = 0.57735 * r + 0.5 and the
        // blur reaches about 3 sigma past the stroke edge.
        val radius = 12f
        val stroke = 3f
        val sigma = 0.57735f * radius + 0.5f
        assertTrue(GlyphGeometry.glowPad(stroke, radius) >= stroke / 2f + 3f * sigma)
    }

    @Test
    fun noBlurNeedsOnlyTheStroke() {
        assertEquals(3, GlyphGeometry.glowPad(3f, 0f))
    }
}
