package com.giantbomb.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Artwork embedded inside a glass card. The source is drawn normally through
 * the centre, then genuinely resampled at a slightly different magnification
 * inside a narrow perimeter ring. This bends only the part beneath the curved
 * glass edge and never introduces a translucent second image through the face.
 */
class EmbeddedGlassImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val radius = 12f * density
    private val ringPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val shadowRect = RectF()
    private val highlightRect = RectF()
    private var preparedWidth = -1
    private var preparedHeight = -1
    private var preparedTheme: GlassSurface.Theme? = null
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0x36000000
    }
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }

    override fun onDraw(canvas: Canvas) {
        // The embedded artwork's sharp, undisturbed centre plane.
        super.onDraw(canvas)
        if (drawable == null || width <= 0 || height <= 0) return
        val activeTheme = GlassSurface.theme
        if (activeTheme == GlassSurface.Theme.SIMPLE || activeTheme == GlassSurface.Theme.NEON) return
        val extreme = activeTheme == GlassSurface.Theme.EXTREME
        val edgeBand = (if (extreme) 10f else 4f) * density

        if (preparedWidth != width || preparedHeight != height || preparedTheme != activeTheme) {
        preparedWidth = width
        preparedHeight = height
        preparedTheme = activeTheme
        outerRect.set(0f, 0f, width.toFloat(), height.toFloat())
        innerRect.set(edgeBand, edgeBand, width - edgeBand, height - edgeBand)
        ringPath.reset()
        ringPath.fillType = Path.FillType.EVEN_ODD
        ringPath.addRoundRect(outerRect, radius, radius, Path.Direction.CW)
        ringPath.addRoundRect(
            innerRect,
            (radius - edgeBand * 0.45f).coerceAtLeast(0f),
            (radius - edgeBand * 0.45f).coerceAtLeast(0f),
            Path.Direction.CW
        )
        shadowRect.set(edgeBand * 0.42f, edgeBand * 0.42f, width - edgeBand * 0.42f, height - edgeBand * 0.42f)
        highlightRect.set(0.8f * density, 0.8f * density, width - 0.8f * density, height - 0.8f * density)
        causticPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(if (extreme) 0x886EDCFF.toInt() else 0x406EDCFF,
                Color.TRANSPARENT, if (extreme) 0x88FFD27A.toInt() else 0x40FFD27A),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        }

        // Opaque edge-only resampling. Scaling about the optical centre bends
        // image features under the curved perimeter without moving the centre.
        val save = canvas.save()
        canvas.clipPath(ringPath)
        val edgeScale = if (extreme) 1.045f else 1.016f
        canvas.scale(edgeScale, edgeScale, width * 0.5f, height * 0.5f)
        super.onDraw(canvas)
        canvas.restoreToCount(save)

        // A restrained internal shadow and spectral caustic make the image
        // plane feel seated below the polished glass face.
        shadowPaint.alpha = if (extreme) 0x42 else 0x28
        canvas.drawRoundRect(
            shadowRect,
            radius,
            radius,
            shadowPaint
        )
        canvas.drawRoundRect(
            highlightRect,
            radius,
            radius,
            causticPaint
        )
    }
}
