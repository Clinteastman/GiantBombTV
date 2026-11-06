package com.giantbomb.tv.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils

/**
 * TV focus treatment shared by every card: a thick ring in the theme's focus
 * colour, a bigger lift and a light dim on the cards that are not focused, so
 * the selection reads at a glance from across a room.
 *
 * The ring lives in the view's overlay, so it sits above the glass foreground
 * drawables without replacing them. Animations use the system animator scale,
 * so "Remove animations" (or animation scale off) makes every change instant.
 */
object CardFocus {
    const val FOCUSED_SCALE = 1.10f
    private const val DURATION_MS = 150L

    /**
     * Installs the focus ring on [target] and returns a [Ring] to call from
     * the card's focus listener.
     *
     * [liftView] is the view that scales on focus. Pass the card's outermost
     * view (the row's direct child): a row clips each child to its own
     * bounds, so scaling an inner view would shave the lifted card's edges,
     * while scaling the child itself scales that clip with it.
     */
    fun install(
        target: View,
        cornerRadiusDp: Float = 28f,
        focusedScale: Float = FOCUSED_SCALE,
        liftView: View = target
    ): Ring {
        val density = target.resources.displayMetrics.density
        val drawable = FocusRingDrawable(target, density, cornerRadiusDp * density)
        target.overlay.add(drawable)
        drawable.setBounds(0, 0, target.width, target.height)
        target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, v.width, v.height)
        }
        return Ring(liftView, drawable, focusedScale)
    }

    class Ring internal constructor(
        private val liftView: View,
        private val drawable: FocusRingDrawable,
        private val focusedScale: Float
    ) {
        private var animator: ValueAnimator? = null

        fun update(focused: Boolean) {
            val scale = if (focused) focusedScale else 1f
            liftView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            animator?.cancel()
            animator = ValueAnimator.ofFloat(drawable.progress, if (focused) 1f else 0f).apply {
                duration = DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawable.progress = it.animatedValue as Float }
                start()
            }
        }
    }

    /** Ring colour per theme: white on glass and Simple, hot pink on Neon. */
    private fun ringColour(): Int =
        if (GlassSurface.theme == GlassSurface.Theme.NEON) 0xFFFF3BEA.toInt() else Color.WHITE

    /**
     * Draws, by [progress] (0 = resting, 1 = focused):
     * - resting: a soft dark veil so the focused card stands out (D-pad
     *   only: in touch mode nothing is focused, so nothing is veiled);
     * - focused: a thick bright ring on the card's edge, edged with thin
     *   dark lines so it stays clear on bright artwork and bright
     *   thumbnails alike. Neon swaps the inner line for a glow.
     *
     * Overlays are clipped to the view's bounds, so every layer sits inside
     * the rounded edge; nothing is drawn in the corners outside it.
     */
    internal class FocusRingDrawable(
        private val owner: View,
        private val density: Float,
        private val radius: Float
    ) : Drawable() {
        var progress = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidateSelf()
                }
            }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val p = progress.coerceIn(0f, 1f)

            val veil = if (owner.isInTouchMode) 0 else ((1f - p) * VEIL_ALPHA).toInt()
            if (veil > 0) {
                fill.color = ColorUtils.setAlphaComponent(Color.BLACK, veil)
                rect.set(b)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            if (p <= 0f) return

            val ring = ringColour()
            val lineWidth = EDGE_LINE_DP * density
            val ringWidth = RING_DP * density
            val darkLine = ColorUtils.setAlphaComponent(Color.BLACK, (EDGE_LINE_ALPHA * p).toInt())
            strokeRing(canvas, b, lineWidth / 2f, lineWidth, darkLine)
            strokeRing(canvas, b, lineWidth + ringWidth / 2f, ringWidth,
                ColorUtils.setAlphaComponent(ring, (255 * p).toInt()))
            val inner = lineWidth + ringWidth
            if (GlassSurface.theme == GlassSurface.Theme.NEON) {
                val glowWidth = GLOW_STEP_DP * density
                GLOW.forEachIndexed { index, alpha ->
                    strokeRing(canvas, b, inner + glowWidth * (index + 0.5f), glowWidth,
                        ColorUtils.setAlphaComponent(ring, (alpha * p).toInt()))
                }
            } else {
                strokeRing(canvas, b, inner + lineWidth / 2f, lineWidth, darkLine)
            }
        }

        /** Strokes a rounded ring whose centre line is [inset] px inside the bounds. */
        private fun strokeRing(canvas: Canvas, b: Rect, inset: Float, width: Float, colour: Int) {
            rect.set(b)
            rect.inset(inset, inset)
            val r = (radius - inset).coerceAtLeast(0f)
            stroke.strokeWidth = width
            stroke.color = colour
            canvas.drawRoundRect(rect, r, r, stroke)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val VEIL_ALPHA = 0x40
            const val RING_DP = 4f
            const val EDGE_LINE_DP = 1.5f
            const val EDGE_LINE_ALPHA = 0xA0
            const val GLOW_STEP_DP = 2.5f
            val GLOW = intArrayOf(0x80, 0x48, 0x1C)
        }
    }
}
