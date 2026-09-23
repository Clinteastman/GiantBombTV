package com.giantbomb.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.giantbomb.tv.data.PrefsManager
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/**
 * Shared glass treatment for cards and controls.
 *
 * Android 13+ draws the glass surface with AGSL. The shader is installed as the
 * view background, so it never processes the thumbnail, labels or control icon.
 * Older devices retain a lightweight translucent-gradient fallback.
 */
object GlassSurface {
    enum class Shape { ROUNDED, PILL, CIRCLE }
    enum class Emphasis { CARD, BUTTON, PLAYER_CONTROL }
    enum class Theme { SIMPLE, FROSTED, EXTREME, NEON }

    @Volatile
    var theme: Theme = Theme.FROSTED
        private set

    val usesBackdropRefraction: Boolean
        get() = theme == Theme.FROSTED || theme == Theme.EXTREME

    fun configure(themeValue: String) {
        theme = when (themeValue) {
            PrefsManager.THEME_SIMPLE -> Theme.SIMPLE
            PrefsManager.THEME_EXTREME -> Theme.EXTREME
            PrefsManager.THEME_NEON -> Theme.NEON
            else -> Theme.FROSTED
        }
    }

    private fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

    private fun transparentInputShader(): Shader = LinearGradient(
        0f,
        0f,
        1f,
        1f,
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        Shader.TileMode.CLAMP
    )

    private var backdropFromInput: Shader = transparentInputShader()
    private var backdropToInput: Shader = transparentInputShader()
    private var hasBackdropFrom = false
    private var hasBackdropTo = false
    private var backdropMix = 1f
    private var backdropTransitionStartNanos = 0L
    private var backdropTransitionActive = false
    private var backdropGeneration = 0
    private const val BACKDROP_TRANSITION_DURATION_NANOS = 600_000_000L
    private val trackedCards: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val neonCardPositions = WeakHashMap<View, PointF>()
    private var neonMotionSink: ((Float, Float, Float, Float, Float, Float) -> Unit)? = null
    private var positionTrackingStarted = false
    private val positionFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            positionTrackingStarted = false
            if (!usesBackdropRefraction && (theme != Theme.NEON || neonMotionSink == null)) return
            val visible = trackedCards.any { it.isAttachedToWindow && it.isShown && it.windowVisibility == View.VISIBLE }
            if (!visible) return
            val backdropChanged = updateBackdropTransition(frameTimeNanos)
            trackedCards.forEach { card ->
                if (!card.isAttachedToWindow || !card.isShown || card.windowVisibility != View.VISIBLE) return@forEach
                val positionChanged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (card.background as? RuntimeGlassDrawable)?.updateLiveScreenPosition() == true
                if (positionChanged || backdropChanged) {
                    card.invalidate()
                }
                if (theme == Theme.NEON) updateNeonCardMotion(card)
            }
            if (backdropTransitionActive) ensurePositionTracking()
        }
    }

    fun setNeonMotionSink(sink: ((Float, Float, Float, Float, Float, Float) -> Unit)?) {
        neonMotionSink = sink
        neonCardPositions.clear()
        if (sink != null) ensurePositionTracking()
    }

    private fun updateNeonCardMotion(card: View) {
        val sink = neonMotionSink ?: return
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        val x = location[0] + card.width * 0.5f
        val y = location[1] + card.height * 0.5f
        val previous = neonCardPositions[card]
        if (previous != null) {
            val dx = x - previous.x
            val dy = y - previous.y
            if (abs(dx) + abs(dy) > 1.5f) {
                sink(
                    location[0].toFloat(),
                    location[1].toFloat(),
                    location[0] + card.width.toFloat(),
                    location[1] + card.height.toFloat(),
                    dx,
                    dy
                )
            }
            previous.set(x, y)
        } else {
            neonCardPositions[card] = PointF(x, y)
        }
    }

    private fun updateBackdropTransition(frameTimeNanos: Long): Boolean {
        if (!backdropTransitionActive) return false
        if (backdropTransitionStartNanos == 0L) backdropTransitionStartNanos = frameTimeNanos
        val elapsed = (frameTimeNanos - backdropTransitionStartNanos).coerceAtLeast(0L)
        val linear = (elapsed.toFloat() / BACKDROP_TRANSITION_DURATION_NANOS).coerceIn(0f, 1f)
        backdropMix = (0.5 - cos(linear * PI) * 0.5).toFloat()
        if (linear >= 1f) {
            backdropMix = 1f
            backdropTransitionActive = false
            backdropTransitionStartNanos = 0L
        }
        return true
    }

    private fun ensurePositionTracking() {
        if (positionTrackingStarted) return
        positionTrackingStarted = true
        Choreographer.getInstance().postFrameCallback(positionFrameCallback)
    }

    /** Supplies the same screen-space artwork used by the activity backdrop. */
    fun updateBackdrop(source: Bitmap?, targetWidth: Int, targetHeight: Int) {
        // Never keep Glide's bitmap: Glide pools or recycles it once its
        // target is cleared (e.g. after a theme change recreates the screen),
        // and this object outlives every screen. Keep a small private copy.
        val bitmap = source?.let(::ownedBackdropCopy)
        // The destination from the previous transition is the source for the
        // next one. Selection is debounced, so transitions normally complete;
        // this also gives rapid navigation a stable forward direction.
        backdropFromInput = backdropToInput
        hasBackdropFrom = hasBackdropTo
        if (bitmap == null || targetWidth <= 0 || targetHeight <= 0) {
            backdropToInput = transparentInputShader()
            hasBackdropTo = false
        } else {
            val scale = maxOf(
                targetWidth.toFloat() / bitmap.width,
                targetHeight.toFloat() / bitmap.height
            )
            val dx = (targetWidth - bitmap.width * scale) * 0.5f
            val dy = (targetHeight - bitmap.height * scale) * 0.5f
            backdropToInput = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                })
            }
            hasBackdropTo = true
        }
        backdropMix = 0f
        backdropTransitionStartNanos = 0L
        backdropTransitionActive = true
        backdropGeneration++
        if (usesBackdropRefraction) ensurePositionTracking()
        trackedCards.forEach(View::invalidate)
    }

    private const val BACKDROP_COPY_MAX_EDGE = 640

    private fun ownedBackdropCopy(source: Bitmap): Bitmap? {
        if (source.isRecycled) return null
        return runCatching {
            // Hardware bitmaps can't be scaled directly; take a software copy first.
            val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                source.config == Bitmap.Config.HARDWARE
            ) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            } ?: return null
            val scale = minOf(1f, BACKDROP_COPY_MAX_EDGE.toFloat() / maxOf(software.width, software.height))
            val width = (software.width * scale).toInt().coerceAtLeast(1)
            val height = (software.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(software, width, height, true)
            // createScaledBitmap may hand back its input when no scaling is needed.
            if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, false) else scaled
        }.getOrNull()
    }

    fun drawable(
        context: Context,
        emphasis: Emphasis,
        focused: Boolean = false,
        shape: Shape = Shape.ROUNDED,
        cornerRadiusDp: Float = 28f,
        accentColor: Int? = null,
        owner: View? = null
    ): Drawable {
        val radius = when (shape) {
            Shape.CIRCLE, Shape.PILL -> context.dp(999f)
            Shape.ROUNDED -> context.dp(cornerRadiusDp)
        }

        val activeTheme = theme
        val base = when {
            activeTheme == Theme.NEON -> neonDrawable(context, radius, focused, accentColor)
            activeTheme == Theme.SIMPLE -> fallbackDrawable(radius, emphasis, focused, accentColor, activeTheme)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                RuntimeGlassDrawable(radius, emphasis, focused, accentColor, owner, activeTheme)
            }
            else -> fallbackDrawable(radius, emphasis, focused, accentColor, activeTheme)
        }

        // Card content is clipped by its owner. A second stroked layer inside
        // the same clip creates the fringe that previously looked like a shader
        // artefact, so cards deliberately have no rim. Controls retain one for
        // legibility over moving video.
        if (emphasis == Emphasis.CARD) return base

        val borderColor = if (focused) 0xE6FFFFFF.toInt() else 0x66FFFFFF
        val borderWidth = context.dp(if (focused) 1.5f else 0.75f).toInt().coerceAtLeast(1)
        val rim = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(borderWidth, borderColor)
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(base, rim))
    }

    private fun fallbackDrawable(
        radius: Float,
        emphasis: Emphasis,
        focused: Boolean,
        accentColor: Int?,
        activeTheme: Theme
    ): Drawable {
        val fill = accentColor?.let { accent ->
            if (focused) {
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, 0xA0),
                    ColorUtils.setAlphaComponent(accent, 0x78),
                    ColorUtils.setAlphaComponent(accent, 0x58)
                )
            } else {
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, 0x78),
                    ColorUtils.setAlphaComponent(accent, 0x52),
                    ColorUtils.setAlphaComponent(accent, 0x3D)
                )
            }
        } ?: when (emphasis) {
            Emphasis.CARD -> if (focused) {
                intArrayOf(0x52FFFFFF, 0x2BFFFFFF, 0x20FFFFFF)
            } else if (activeTheme == Theme.SIMPLE) {
                intArrayOf(0x4A20232A, 0x6620232A, 0x7620232A)
            } else {
                intArrayOf(0x48FFFFFF, 0x2DFFFFFF, 0x24FFFFFF)
            }
            Emphasis.BUTTON -> if (focused) {
                intArrayOf(0x62FFFFFF, 0x36FFFFFF, 0x28FFFFFF)
            } else {
                intArrayOf(0x3DFFFFFF, 0x22FFFFFF, 0x18FFFFFF)
            }
            Emphasis.PLAYER_CONTROL -> if (focused) {
                intArrayOf(0x70FFFFFF, 0x42FFFFFF, 0x30FFFFFF)
            } else {
                intArrayOf(0x3D101018, 0x54101018, 0x68101018)
            }
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fill).apply {
            cornerRadius = radius
        }
    }

    private fun neonDrawable(
        context: Context,
        radius: Float,
        focused: Boolean,
        accentColor: Int?
    ): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xD8101324.toInt(), 0xD8160925.toInt(), 0xD8071822.toInt())
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, neonRimDrawable(context, radius, focused, accentColor)))
    }

    private fun neonRimDrawable(
        context: Context,
        radius: Float,
        focused: Boolean,
        accentColor: Int?
    ): Drawable {
        val edge = accentColor ?: if (focused) 0xFFFF3BEA.toInt() else 0xFF43E8FF.toInt()
        val glow = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(context.dp(if (focused) 5f else 3f).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(edge, if (focused) 0x8A else 0x58))
            cornerRadius = radius
        }
        val rim = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(context.dp(if (focused) 1.6f else 1f).toInt().coerceAtLeast(1), edge)
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(glow, rim))
    }

    private fun simpleRimDrawable(
        context: Context,
        radius: Float,
        focused: Boolean,
        accentColor: Int?
    ): Drawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        val edge = accentColor?.let {
            ColorUtils.setAlphaComponent(it, if (focused) 0xC0 else 0x88)
        } ?: if (focused) 0xB8FFFFFF.toInt() else 0x62FFFFFF
        setStroke(context.dp(if (focused) 1.5f else 0.85f).toInt().coerceAtLeast(1), edge)
        cornerRadius = radius
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class RuntimeGlassDrawable(
        private val radius: Float,
        emphasis: Emphasis,
        focused: Boolean,
        accentColor: Int?,
        private val owner: View?,
        activeTheme: Theme
    ) : Drawable() {
        private val shader = RuntimeShader(GLASS_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = this@RuntimeGlassDrawable.shader
        }
        private val drawingBounds = RectF()
        private val screenLocation = IntArray(2)
        private val scrollContainers = mutableListOf<RecyclerView>()
        private var appliedBackdropGeneration = -1
        private var scrollContainersResolved = false
        private var positionInitialized = false
        private var previousRawX = 0
        private var previousRawY = 0
        private var previousScrollX = 0
        private var previousScrollY = 0
        private var effectiveX = 0f
        private var effectiveY = 0f

        /**
         * Keeps the backdrop sample attached to this card while RecyclerView is
         * animating it on the render thread. getLocationOnScreen() can lag until
         * scrolling settles; the ancestor scroll deltas remain live, so they fill
         * that gap without introducing any synthetic or page-wide movement.
         */
        fun updateLiveScreenPosition(): Boolean {
            val target = owner ?: return false
            target.getLocationOnScreen(screenLocation)
            resolveScrollContainers(target)

            val rawX = screenLocation[0]
            val rawY = screenLocation[1]
            val scrollX = scrollContainers.sumOf { it.computeHorizontalScrollOffset() }
            val scrollY = scrollContainers.sumOf { it.computeVerticalScrollOffset() }

            if (!positionInitialized) {
                positionInitialized = true
                effectiveX = rawX.toFloat()
                effectiveY = rawY.toFloat()
                previousRawX = rawX
                previousRawY = rawY
                previousScrollX = scrollX
                previousScrollY = scrollY
                shader.setFloatUniform("screenOrigin", effectiveX, effectiveY)
                return true
            }

            val nextX = if (rawX != previousRawX) {
                rawX.toFloat()
            } else {
                effectiveX - (scrollX - previousScrollX)
            }
            val nextY = if (rawY != previousRawY) {
                rawY.toFloat()
            } else {
                effectiveY - (scrollY - previousScrollY)
            }
            val changed = abs(nextX - effectiveX) > 0.1f || abs(nextY - effectiveY) > 0.1f

            effectiveX = nextX
            effectiveY = nextY
            previousRawX = rawX
            previousRawY = rawY
            previousScrollX = scrollX
            previousScrollY = scrollY
            if (changed) shader.setFloatUniform("screenOrigin", effectiveX, effectiveY)
            return changed
        }

        private fun resolveScrollContainers(target: View) {
            if (scrollContainersResolved) return
            var ancestor = target.parent
            while (ancestor != null) {
                if (ancestor is RecyclerView) scrollContainers += ancestor
                ancestor = ancestor.parent
            }
            scrollContainersResolved = target.isAttachedToWindow
        }

        init {
            val tint = accentColor ?: when (emphasis) {
                Emphasis.PLAYER_CONTROL -> Color.rgb(120, 126, 142)
                else -> Color.WHITE
            }
            shader.setFloatUniform(
                "tint",
                Color.red(tint) / 255f,
                Color.green(tint) / 255f,
                Color.blue(tint) / 255f
            )
            shader.setFloatUniform("focused", if (focused) 1f else 0f)
            val extreme = activeTheme == Theme.EXTREME
            shader.setFloatUniform("effectLevel", if (extreme) 1f else 0.48f)
            shader.setFloatUniform(
                "baseAlpha",
                when (emphasis) {
                    Emphasis.CARD -> if (focused) 0.17f else if (extreme) 0.09f else 0.14f
                    Emphasis.BUTTON -> if (focused) 0.30f else 0.18f
                    Emphasis.PLAYER_CONTROL -> if (focused) 0.38f else 0.27f
                }
            )
        }

        override fun onBoundsChange(bounds: Rect) {
            drawingBounds.set(bounds)
            shader.setFloatUniform("origin", bounds.left.toFloat(), bounds.top.toFloat())
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform(
                "size",
                bounds.width().coerceAtLeast(1).toFloat(),
                bounds.height().coerceAtLeast(1).toFloat()
            )
        }

        override fun draw(canvas: Canvas) {
            if (appliedBackdropGeneration != backdropGeneration) {
                shader.setInputShader("backdropFrom", backdropFromInput)
                shader.setInputShader("backdropTo", backdropToInput)
                shader.setFloatUniform("hasBackdropFrom", if (usesBackdropRefraction && hasBackdropFrom) 1f else 0f)
                shader.setFloatUniform("hasBackdropTo", if (usesBackdropRefraction && hasBackdropTo) 1f else 0f)
                appliedBackdropGeneration = backdropGeneration
            }
            shader.setFloatUniform("backdropMix", backdropMix)
            updateLiveScreenPosition()
            canvas.drawRoundRect(drawingBounds, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Optical coating drawn above card content, like light on the front face. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class RuntimeGlassOverlayDrawable(
        private val radius: Float,
        focused: Boolean,
        accentColor: Int?,
        activeTheme: Theme
    ) : Drawable() {
        private val shader = RuntimeShader(GLASS_OVERLAY_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = this@RuntimeGlassOverlayDrawable.shader
        }
        private val drawingBounds = RectF()

        init {
            shader.setFloatUniform("focused", if (focused) 1f else 0f)
            val coating = accentColor ?: Color.WHITE
            shader.setFloatUniform(
                "coatingTint",
                Color.red(coating) / 255f,
                Color.green(coating) / 255f,
                Color.blue(coating) / 255f
            )
            val extreme = activeTheme == Theme.EXTREME
            shader.setFloatUniform("detailStrength", if (extreme) 1f else 0.42f)
            shader.setFloatUniform(
                "coatingStrength",
                when {
                    accentColor != null -> if (extreme) 0.20f else 0.25f
                    extreme -> 0f
                    else -> 0.11f
                }
            )
        }

        override fun onBoundsChange(bounds: Rect) {
            drawingBounds.set(bounds)
            shader.setFloatUniform("origin", bounds.left.toFloat(), bounds.top.toFloat())
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform(
                "size",
                bounds.width().coerceAtLeast(1).toFloat(),
                bounds.height().coerceAtLeast(1).toFloat()
            )
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(drawingBounds, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun applyState(
        view: View,
        emphasis: Emphasis,
        focused: Boolean,
        shape: Shape = Shape.ROUNDED,
        cornerRadiusDp: Float = 28f,
        accentColor: Int? = null
    ) {
        view.background = drawable(
            view.context,
            emphasis,
            focused,
            shape,
            cornerRadiusDp,
            accentColor,
            owner = view
        )
        if (emphasis == Emphasis.CARD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val radius = when (shape) {
                Shape.CIRCLE, Shape.PILL -> view.context.dp(999f)
                Shape.ROUNDED -> view.context.dp(cornerRadiusDp)
            }
            // Foreground light is what makes the whole card—including its
            // artwork—read as one glass object instead of a tinted text panel.
            view.foreground = when (theme) {
                Theme.FROSTED, Theme.EXTREME -> RuntimeGlassOverlayDrawable(
                    radius, focused, accentColor, theme
                )
                Theme.SIMPLE -> {
                    val rim = simpleRimDrawable(view.context, radius, focused, accentColor)
                    accentColor?.let { accent ->
                        val tint = GradientDrawable().apply {
                            setColor(ColorUtils.setAlphaComponent(accent, 0x24))
                            cornerRadius = radius
                        }
                        LayerDrawable(arrayOf(tint, rim))
                    } ?: rim
                }
                Theme.NEON -> neonRimDrawable(
                    view.context,
                    radius,
                    focused,
                    accentColor
                )
            }
        }
        if (emphasis == Emphasis.CARD) {
            if (trackedCards.add(view)) {
                // A traversal (scroll/layout/visibility change) wakes tracking.
                // Idle surfaces do not keep a process-wide frame loop alive.
                val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
                    if (view.isShown && view.windowVisibility == View.VISIBLE &&
                        (usesBackdropRefraction || (theme == Theme.NEON && neonMotionSink != null))) {
                        ensurePositionTracking()
                    }
                    true
                }
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.viewTreeObserver.addOnPreDrawListener(preDraw)
                        ensurePositionTracking()
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                    }
                })
                if (view.isAttachedToWindow) view.viewTreeObserver.addOnPreDrawListener(preDraw)
                view.addOnLayoutChangeListener { target, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom ->
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                        target.invalidate()
                    }
                }
            }
            if (usesBackdropRefraction || theme == Theme.NEON) ensurePositionTracking()
            applyTextHalo(view)
            val outlineRadius = when (shape) {
                Shape.CIRCLE, Shape.PILL -> view.context.dp(999f)
                Shape.ROUNDED -> view.context.dp(cornerRadiusDp)
            }
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height, outlineRadius)
                }
            }
            view.clipToOutline = true
        }
        view.elevation = view.context.dp(
            when {
                // Card elevation was the dark line visible around the cards.
                // Focus remains clear through scale and the brighter shader.
                emphasis == Emphasis.CARD -> 0f
                focused -> 10f
                emphasis == Emphasis.PLAYER_CONTROL -> 2f
                else -> 4f
            }
        )
    }

    private fun applyTextHalo(view: View) {
        if (view is TextView && ColorUtils.calculateLuminance(view.currentTextColor) > 0.25) {
            val shadowColor = if (theme == Theme.NEON) 0xB000CFE8.toInt() else 0xF0000000.toInt()
            view.setShadowLayer(view.context.dp(if (theme == Theme.NEON) 4f else 7f), 0f,
                view.context.dp(1.5f), shadowColor)
            val currentColor = view.currentTextColor
            if (ColorUtils.calculateLuminance(currentColor) < 0.8) {
                val brighter = ColorUtils.blendARGB(currentColor, Color.WHITE, 0.68f)
                view.setTextColor(ColorUtils.setAlphaComponent(brighter, maxOf(Color.alpha(currentColor), 0xEA)))
            } else if (Color.alpha(currentColor) < 0xEA) {
                view.setTextColor(ColorUtils.setAlphaComponent(currentColor, 0xEA))
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTextHalo(view.getChildAt(index))
        }
    }

    fun styleInteractive(
        view: View,
        emphasis: Emphasis = Emphasis.BUTTON,
        shape: Shape = Shape.ROUNDED,
        cornerRadiusDp: Float = 10f,
        focusedScale: Float = 1.05f,
        accentColor: Int? = null
    ) {
        applyState(view, emphasis, view.hasFocus(), shape, cornerRadiusDp, accentColor)
        view.stateListAnimator = null
        view.setOnFocusChangeListener { target, focused ->
            applyState(target, emphasis, focused, shape, cornerRadiusDp, accentColor)
            val scale = if (focused) focusedScale else 1f
            target.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(140L)
                .start()
        }
    }

    private const val GLASS_SHADER = """
        uniform shader backdropFrom;
        uniform shader backdropTo;
        uniform float2 origin;
        uniform float2 size;
        uniform float2 screenOrigin;
        uniform float3 tint;
        uniform float focused;
        uniform float baseAlpha;
        uniform float effectLevel;
        uniform float radius;
        uniform float backdropMix;
        uniform float hasBackdropFrom;
        uniform float hasBackdropTo;

        half4 main(float2 fragCoord) {
            float2 uv = clamp((fragCoord - origin) / size, 0.0, 1.0);

            float2 local = (uv - 0.5) * size;
            float2 halfSize = size * 0.5;
            float safeRadius = min(radius, min(halfSize.x, halfSize.y) - 1.0);
            float2 q = abs(local) - (halfSize - float2(safeRadius, safeRadius));
            float signedDistance = length(max(q, float2(0.0, 0.0)))
                + min(max(q.x, q.y), 0.0) - safeRadius;
            float glassDepth = max(0.0, -signedDistance);
            float lens = 1.0 - smoothstep(3.0, 52.0, glassDepth);
            lens = lens * lens * (3.0 - 2.0 * lens);
            float2 normal = local / max(length(local), 1.0);

            // Sample the real screen-space backdrop. Moving the card changes
            // screenOrigin while the texture remains anchored to the screen,
            // so objects underneath genuinely bend through the lens.
            float2 globalCoord = screenOrigin + (fragCoord - origin);
            float backdropBend = lens * (24.0 + focused * 9.0) * effectLevel;
            float2 backdropCoord = globalCoord - normal * backdropBend;
            float backdropDispersion = lens * (3.0 + focused * 1.2) * effectLevel;
            half4 scene;
            if (backdropMix <= 0.001) {
                scene = backdropFrom.eval(backdropCoord);
                scene.r = backdropFrom.eval(backdropCoord - normal * backdropDispersion).r;
                scene.b = backdropFrom.eval(backdropCoord + normal * backdropDispersion).b;
            } else if (backdropMix >= 0.999) {
                scene = backdropTo.eval(backdropCoord);
                scene.r = backdropTo.eval(backdropCoord - normal * backdropDispersion).r;
                scene.b = backdropTo.eval(backdropCoord + normal * backdropDispersion).b;
            } else {
                half4 fromScene = backdropFrom.eval(backdropCoord);
                fromScene.r = backdropFrom.eval(backdropCoord - normal * backdropDispersion).r;
                fromScene.b = backdropFrom.eval(backdropCoord + normal * backdropDispersion).b;
                half4 toScene = backdropTo.eval(backdropCoord);
                toScene.r = backdropTo.eval(backdropCoord - normal * backdropDispersion).r;
                toScene.b = backdropTo.eval(backdropCoord + normal * backdropDispersion).b;
                scene = mix(fromScene, toScene, backdropMix);
            }

            // A broad off-centre bloom gives the surface changing depth without
            // periodic waves. Periodic waves read as vertical stripes beside
            // the card edges on a large TV panel.
            float softBloom = 1.0 - smoothstep(
                0.08,
                1.05,
                distance(uv, float2(0.24, -0.10))
            );
            float topLight = pow(1.0 - uv.y, 2.6);

            float light = 0.78 + softBloom * (0.10 + focused * 0.04)
                + topLight * 0.035;
            float surfaceAlpha = baseAlpha + softBloom * (0.018 + focused * 0.012)
                + topLight * 0.015;

            // Two short, localised prismatic glints suggest spectral separation
            // in the glass. They deliberately fade well before the card sides,
            // so they cannot become another continuous inner border.
            float2 glintUv = float2(uv.x, uv.y * 4.5);
            float redGlint = 1.0 - smoothstep(
                0.035, 0.19, distance(glintUv, float2(0.12, 0.08))
            );
            float greenGlint = 1.0 - smoothstep(
                0.035, 0.19, distance(glintUv, float2(0.16, 0.08))
            );
            float blueGlint = 1.0 - smoothstep(
                0.035, 0.19, distance(glintUv, float2(0.20, 0.08))
            );
            float3 spectrum = float3(redGlint, greenGlint, blueGlint);
            float spectralStrength = (0.016 + focused * 0.016) * effectLevel;
            float glintAlpha = max(redGlint, max(greenGlint, blueGlint))
                * spectralStrength;
            surfaceAlpha += glintAlpha;

            // When a backdrop exists, the refracted sample is the transmitted
            // image, not a translucent copy laid over the unrefracted original.
            // Darkening happens in RGB so alpha remains fully opaque and there
            // is no doubled image beneath the glass.
            float transmission = 0.58 + lens * (0.08 + focused * 0.02);
            float hasBackdrop = max(hasBackdropFrom, hasBackdropTo);
            float alpha = mix(surfaceAlpha, 1.0, hasBackdrop);
            float3 surfaceLight = tint * light * surfaceAlpha
                + spectrum * spectralStrength;
            float3 premultiplied = mix(
                surfaceLight,
                scene.rgb * transmission + surfaceLight * 0.45,
                hasBackdrop
            );
            return half4(half3(min(premultiplied, float3(alpha))), half(alpha));
        }
    """

    private const val GLASS_OVERLAY_SHADER = """
        uniform float2 origin;
        uniform float2 size;
        uniform float radius;
        uniform float focused;
        uniform float3 coatingTint;
        uniform float coatingStrength;
        uniform float detailStrength;

        half4 main(float2 fragCoord) {
            float2 uv = clamp((fragCoord - origin) / size, 0.0, 1.0);

            // Signed distance to the actual rounded rectangle. Every bevel and
            // lens band follows the corners, so the card reads as a thick,
            // polished tile rather than a flat rectangle with an outline.
            float2 local = (uv - 0.5) * size;
            float2 halfSize = size * 0.5;
            float safeRadius = min(radius, min(halfSize.x, halfSize.y) - 1.0);
            float2 q = abs(local) - (halfSize - float2(safeRadius, safeRadius));
            float signedDistance = length(max(q, float2(0.0, 0.0)))
                + min(max(q.x, q.y), 0.0) - safeRadius;
            float glassDepth = max(0.0, -signedDistance);

            float outerEdge = 1.0 - smoothstep(0.25, 2.8, glassDepth);
            float thickBevel = 1.0 - smoothstep(1.0, 20.0, glassDepth);
            float innerLens = smoothstep(5.0, 10.0, glassDepth)
                * (1.0 - smoothstep(18.0, 28.0, glassDepth));
            float lightFacing = clamp(
                (1.0 - uv.x) * 0.42 + (1.0 - uv.y) * 0.58,
                0.0,
                1.0
            );
            float shadowFacing = clamp(uv.x * 0.42 + uv.y * 0.58, 0.0, 1.0);

            // Broad polished highlight, like a softbox reflected in the domed
            // top face of moulded glass.
            float2 capPoint = (uv - float2(0.32, 0.035)) * float2(0.95, 4.2);
            float capHighlight = 1.0 - smoothstep(0.08, 0.62, length(capPoint));
            capHighlight *= 1.0 - smoothstep(0.0, 25.0, glassDepth);

            // Chromatic separation follows the rounded light-facing bevel.
            float spectralWindow = smoothstep(0.42, 0.78, lightFacing);
            float red = (1.0 - smoothstep(0.35, 1.25, abs(glassDepth - 0.75)))
                * spectralWindow;
            float green = (1.0 - smoothstep(0.35, 1.25, abs(glassDepth - 1.45)))
                * spectralWindow;
            float blue = (1.0 - smoothstep(0.35, 1.25, abs(glassDepth - 2.15)))
                * spectralWindow;
            float3 spectrum = float3(red, green, blue);

            // A curved internal caustic makes the refraction obvious across the
            // artwork, inspired by light bending through a thick glass tile.
            // It is a single sweep—not a repeated texture—so it reads as optics
            // rather than a display artefact.
            float causticY = 0.22 + 0.055 * sin((uv.x - 0.06) * 3.14159265);
            float causticWindow = smoothstep(0.035, 0.16, uv.x)
                * (1.0 - smoothstep(0.72, 0.96, uv.x));
            float causticRed = (1.0 - smoothstep(
                0.012, 0.052, abs(uv.y - (causticY - 0.012))
            )) * causticWindow;
            float causticGreen = (1.0 - smoothstep(
                0.012, 0.052, abs(uv.y - causticY)
            )) * causticWindow;
            float causticBlue = (1.0 - smoothstep(
                0.012, 0.052, abs(uv.y - (causticY + 0.012))
            )) * causticWindow;
            float3 causticSpectrum = float3(causticRed, causticGreen, causticBlue);
            float causticPeak = max(
                causticRed,
                max(causticGreen, causticBlue)
            );

            float bevelLight = (outerEdge * (0.42 + focused * 0.16)
                + thickBevel * lightFacing * (0.17 + focused * 0.07)
                + innerLens * lightFacing * 0.075) * detailStrength;
            float polishedAlpha = capHighlight * (0.24 + focused * 0.10) * detailStrength;
            float spectralAlpha = max(red, max(green, blue))
                * (0.44 + focused * 0.14) * detailStrength;
            float causticAlpha = causticPeak * (0.12 + focused * 0.08) * detailStrength;
            float shadowAlpha = (thickBevel * shadowFacing * (0.20 + focused * 0.05)
                + innerLens * shadowFacing * 0.11) * detailStrength;
            float coatingAlpha = coatingStrength * (0.72 + thickBevel * 0.28);
            float alpha = min(
                0.86,
                bevelLight + polishedAlpha + spectralAlpha + causticAlpha
                    + shadowAlpha + coatingAlpha
            );
            float3 colour = float3(1.0) * (bevelLight + polishedAlpha)
                + spectrum * (0.44 + focused * 0.14) * detailStrength
                + causticSpectrum * (0.12 + focused * 0.08) * detailStrength
                + coatingTint * coatingAlpha;
            // shadowAlpha contributes opacity without light, creating the dark
            // internal lens trough that makes the clear edge look substantial.
            return half4(half3(min(colour, float3(alpha))), half(alpha));
        }
    """

}
