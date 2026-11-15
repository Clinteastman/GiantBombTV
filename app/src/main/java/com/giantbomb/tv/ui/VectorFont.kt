package com.giantbomb.tv.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.method.TransformationMethod
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Arcade vector lettering for the Neon theme, drawn as strokes like a vector
 * monitor (and the Geometry Wars HUD) rather than as filled font outlines.
 *
 * Glyph data derived from "arcadefont"
 * https://github.com/coolbutuseless/arcadefont
 *
 * Copyright (c) 2020 mikefc@coolbutuseless.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * (Full notice also bundled in the APK at assets/licenses/arcadefont.txt and
 * in THIRD_PARTY_NOTICES.md.)
 *
 * Each glyph is defined on a 9x9 grid (0..8, y up). Pairs of digits are
 * points; strokes are separated by ':'. The font is caps-only, so lower-case
 * text is drawn in capitals. Characters without a glyph use the normal font.
 */
object VectorFont {

    /** Geometry Wars HUD green. */
    const val GW_GREEN = 0xFF7CFF3C.toInt()

    private val source = mapOf(
        'A' to "00 06 48 86 80 : 03 83",
        'B' to "00 08 58 76 54 04 : 64 82 60 00",
        'C' to "88 08 00 80",
        'D' to "00 08 58 85 83 50 00",
        'E' to "88 08 00 80 : 04 64",
        'F' to "88 08 00 : 04 64",
        'G' to "88 08 00 80 83 43",
        'H' to "00 08 : 80 88 : 04 84",
        'I' to "00 80 : 08 88 : 40 48",
        'J' to "88 80 40 03",
        'K' to "00 08 : 88 04 80",
        'L' to "08 00 80",
        'M' to "00 08 45 88 80",
        'N' to "00 08 80 88",
        'O' to "00 80 88 08 00",
        'P' to "00 08 88 84 04",
        'Q' to "00 08 88 83 40 00 : 43 80",
        'R' to "00 08 88 84 04 80",
        'S' to "00 80 84 04 08 88",
        'T' to "08 88 : 40 48",
        'U' to "08 00 80 88",
        'V' to "08 40 88",
        'W' to "08 00 43 80 88",
        'X' to "00 88 : 08 80",
        'Y' to "08 45 88 : 45 40",
        'Z' to "08 88 00 80",
        '0' to "00 80 88 08 00",
        '1' to "00 80 : 40 48 26",
        '2' to "08 88 84 04 00 80",
        '3' to "08 88 80 00 : 04 84",
        '4' to "08 04 84 : 88 80",
        '5' to "00 80 84 04 08 88",
        '6' to "08 00 80 84 04",
        '7' to "08 88 80",
        '8' to "00 08 88 80 00 : 04 84",
        '9' to "80 88 08 04 84",
        '.' to "00 01 11 10 00",
        ',' to "01 02 12 11 01 : 11 00",
        '-' to "14 74",
        '=' to "13 73 : 15 75",
        '!' to "00 01 11 10 00 : 03 08 18 13 03",
        '?' to "06 08 88 84 44 40",
        ':' to "02 03 13 12 02 : 05 06 16 15 05",
        '#' to "03 83 : 05 85 : 30 38 : 50 58",
        '\'' to "07 08 18 17 07 : 17 05",
        '"' to "07 08 18 17 07 : 17 05 : 27 28 38 37 27 : 37 25",
        '[' to "28 08 00 20",
        ']' to "08 28 20 00",
        '(' to "28 04 20",
        ')' to "08 24 00",
        '$' to "01 81 84 04 07 87 : 40 48",
        '+' to "41 47 : 14 74",
        '/' to "00 88",
        '*' to "41 47 : 14 74 : 22 66 : 26 62",
        '%' to "00 88 : 18 28 27 17 18 : 70 71 61 60 70",
        '|' to "40 48",
        '_' to "00 80",
        '<' to "87 04 81",
        '>' to "07 84 01",
        '&' to "80 47 58 67 21 30 60 82",
        '@' to "71 60 20 02 06 28 68 86 84 62 22 24 36 66 62"
    )

    /** Each glyph as a list of strokes; each stroke is x,y pairs on the 0..8 grid. */
    private val glyphs: Map<Char, List<FloatArray>> = source.mapValues { (_, spec) ->
        spec.split(':').map { stroke ->
            val digits = stroke.filter(Char::isDigit)
            FloatArray(digits.length) { digits[it].digitToInt().toFloat() }
        }.filter { it.size >= 4 }
    }

    fun hasGlyph(c: Char): Boolean = glyphs.containsKey(c.uppercaseChar())

    /**
     * Applies the vector lettering to [view] when the Neon theme is active.
     * Safe to call repeatedly; later setText() calls are styled automatically.
     */
    fun applyIfNeon(view: TextView) {
        if (GlassSurface.theme != GlassSurface.Theme.NEON) return
        if (view.transformationMethod is Transformation) return
        view.transformationMethod = Transformation
        view.setTextColor(GW_GREEN)
        // A soft green bloom, like the game's HUD text.
        view.setShadowLayer(6f * view.resources.displayMetrics.density, 0f, 0f, 0xC04CFF1E.toInt())
    }

    fun isVector(view: TextView): Boolean = view.transformationMethod is Transformation

    // Spans draw on the UI thread only. About 60 glyphs at a handful of sizes.
    private val outlines = GlyphOutlineCache<FloatArray>(maxEntries = 512)
    // Alpha-only, so one bitmap serves every glow colour. A few KB each.
    private val glows = GlyphOutlineCache<Bitmap>(maxEntries = 1024)

    private object Transformation : TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: View?): CharSequence? {
            if (source.isNullOrEmpty()) return source
            val out = SpannableString(source)
            val owner = (view as? TextView)?.let { WeakReference(it) }
            for (i in source.indices) {
                val c = source[i]
                if (c != ' ' && glyphs.containsKey(c.uppercaseChar())) {
                    out.setSpan(GlyphSpan(c.uppercaseChar(), owner), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return out
        }

        override fun onFocusChanged(
            view: View?, sourceText: CharSequence?, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?
        ) = Unit
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * The glyph's glow, blurred once in software into an alpha bitmap. Drawing
     * the glyph with the view's shadow layer instead made the GPU build a new
     * blurred mask for every scale and sub-pixel position (card lift, row
     * scroll), and those masks piled up in its cache.
     */
    private fun glowBitmap(
        glyph: Char, strokes: List<FloatArray>, sx: Float, sy: Float,
        strokeWidth: Float, radius: Float
    ): Bitmap = glows.get(glyph, sy, (radius * 4f).roundToInt()) {
        val pad = GlyphGeometry.glowPad(strokeWidth, radius)
        val bitmap = Bitmap.createBitmap(
            ceil(8f * sx).toInt() + pad * 2, ceil(8f * sy).toInt() + pad * 2,
            Bitmap.Config.ALPHA_8
        )
        val path = Path()
        for (stroke in strokes) {
            path.moveTo(stroke[0] * sx, -stroke[1] * sy)
            var i = 2
            while (i + 1 < stroke.size) {
                path.lineTo(stroke[i] * sx, -stroke[i + 1] * sy)
                i += 2
            }
        }
        Canvas(bitmap).apply {
            translate(pad.toFloat(), pad + 8f * sy)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            })
        }
        bitmap
    }

    /** Draws one glyph as strokes in the current text colour (and its glow). */
    private class GlyphSpan(
        private val glyph: Char,
        private val owner: WeakReference<TextView>?
    ) : ReplacementSpan() {
        // Letters are slightly condensed so titles stay a sensible length.
        private fun scaleY(paint: Paint) = paint.textSize * 0.68f / 8f
        private fun scaleX(paint: Paint) = scaleY(paint) * 0.7f

        override fun getSize(
            paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
        ): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            // 8 units of glyph plus 2.5 units of letter spacing.
            return (10.5f * scaleX(paint)).toInt()
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val strokes = glyphs[glyph] ?: return
            val sx = scaleX(paint)
            val sy = scaleY(paint)
            val left = x + sx * 1.25f
            val strokeWidth = paint.textSize * 0.1f
            canvas.save()
            canvas.translate(left, y.toFloat())
            // The view's shadow layer is drawn here as a cached glow bitmap,
            // not by the GPU (see glowBitmap).
            val view = owner?.get()
            val radius = view?.shadowRadius ?: 0f
            if (radius > 0f) {
                val shadow = view!!.shadowColor
                glowPaint.color = shadow
                glowPaint.alpha = Color.alpha(shadow) * paint.alpha / 255
                val pad = GlyphGeometry.glowPad(strokeWidth, radius)
                canvas.drawBitmap(
                    glowBitmap(glyph, strokes, sx, sy, strokeWidth, radius),
                    view.shadowDx - pad, view.shadowDy - 8f * sy - pad, glowPaint
                )
            }
            // Plain square-capped lines: the GPU draws these as rectangles,
            // with nothing to cache. Built once per glyph and size.
            val lines = outlines.get(glyph, sy) { GlyphGeometry.segments(strokes, sx, sy) }
            corePaint.color = paint.color
            corePaint.strokeWidth = strokeWidth
            canvas.drawLines(lines, corePaint)
            canvas.restore()
        }
    }
}
