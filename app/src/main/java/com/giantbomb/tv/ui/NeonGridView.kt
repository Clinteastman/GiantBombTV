package com.giantbomb.tv.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A lightweight damped-spring grid inspired by neon vector arcade fields. */
class NeonGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val isTelevision =
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    // TV emulators are particularly sensitive to full-screen vector work. A
    // slightly wider mesh and fewer, longer-lived trails preserve the hectic
    // look without asking the renderer to paint thousands of glowing strokes.
    private val spacing = (if (isTelevision) 44f else 26f) * density
    private val particleLimit = if (isTelevision) 120 else 260
    private val ambientParticleCount = if (isTelevision) 28 else 52
    private var columns = 0
    private var rows = 0
    private var dx = FloatArray(0)
    private var dy = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var ax = FloatArray(0)
    private var ay = FloatArray(0)
    private var nodePoints = FloatArray(0)
    private var gridLines = FloatArray(0)
    private var fineGridLines = FloatArray(0)
    private var energyLines = FloatArray(0)
    private var gridLineCount = 0
    private var fineGridLineCount = 0
    private var energyLineCount = 0
    private var lastFrameNanos = 0L
    private var frameNumber = 0
    private var accumulatedStep = 0f
    private var motionEnabled = true

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA52E42FF.toInt()
        strokeWidth = 0.72f * density
        style = Paint.Style.STROKE
    }
    private val fineGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38405AC8
        strokeWidth = 0.34f * density
        style = Paint.Style.STROKE
    }
    private val gridOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14264CFF
        strokeWidth = 6.4f * density
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x34345FFF
        strokeWidth = 3.1f * density
        style = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB36E7CFF.toInt()
        strokeWidth = 1.3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val energyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x753256FF
        strokeWidth = 5.2f * density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val energyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE55D88FF.toInt()
        strokeWidth = 1.25f * density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.7f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val bloomGridSourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xC43A52FF.toInt()
        strokeWidth = 1.15f * density
        style = Paint.Style.STROKE
    }
    private val bloomEnergySourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF06FD8FF.toInt()
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val bloomParticleSourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val particleHotCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val particleBandAlpha = intArrayOf(70, 145, 235)
    private val screenSpaceBloom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ScreenSpaceBloom()
    } else null

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var colour: Int,
        var decay: Float = 0.0018f,
        var x1: Float = x,
        var y1: Float = y,
        var x2: Float = x,
        var y2: Float = y,
        var x3: Float = x,
        var y3: Float = y
    )

    private val particles = ArrayList<Particle>(particleLimit)
    private val freeParticles = java.util.ArrayDeque<Particle>().apply {
        repeat(particleLimit) { add(Particle(0f, 0f, 0f, 0f, 0f, 0)) }
    }
    private var replacementIndex = 0

    private fun clearParticles() {
        freeParticles.addAll(particles)
        particles.clear()
    }

    private fun addParticle(x: Float, y: Float, vx: Float, vy: Float, life: Float,
                            colour: Int, decay: Float = 0.0018f) {
        val p = if (freeParticles.isEmpty()) {
            particles[replacementIndex].also {
                replacementIndex = (replacementIndex + 1) % particles.size
            }
        } else freeParticles.removeFirst().also { particles.add(it) }
        p.x = x; p.y = y; p.x1 = x; p.y1 = y; p.x2 = x; p.y2 = y; p.x3 = x; p.y3 = y
        p.vx = vx; p.vy = vy; p.life = life; p.colour = colour; p.decay = decay
    }
    private val colours = intArrayOf(
        0xFF42F5FF.toInt(), 0xFFFF43E6.toInt(), 0xFFFFD84A.toInt(),
        0xFF6CFF71.toInt(), 0xFFA46CFF.toInt()
    )
    // Batch particles by colour and rough lifetime instead of issuing several
    // Canvas calls per particle. This keeps hundreds of independently moving
    // particles affordable during a high-refresh scroll.
    private val particleLines = Array(colours.size * 3) { FloatArray(particleLimit * 12) }
    private val particleLineCounts = IntArray(colours.size * 3)
    private val particlePoints = Array(colours.size * 3) { FloatArray(particleLimit * 2) }
    private val particlePointCounts = IntArray(colours.size * 3)

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        if (!enabled) {
            dx.fill(0f); dy.fill(0f); vx.fill(0f); vy.fill(0f)
            clearParticles()
        } else if (width > 0 && height > 0 && particles.isEmpty()) {
            seedAmbientParticles()
        }
        lastFrameNanos = 0L
        accumulatedStep = 0f
        invalidate()
    }

    fun disturb(screenX: Float, screenY: Float, motionX: Float, motionY: Float) {
        if (!motionEnabled || columns == 0) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = screenX - location[0]
        val y = screenY - location[1]
        disturbLocalFromMotion(x, y, motionX, motionY, emit = true)
        postInvalidateOnAnimation()
    }

    /** A short, dense line-emitted burst for focus/section transitions. */
    fun burstLozenge(screenLeft: Float, screenTop: Float, screenRight: Float, screenBottom: Float) {
        if (!motionEnabled || columns == 0) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val left = screenLeft - location[0]
        val top = screenTop - location[1]
        val right = screenRight - location[0]
        val bottom = screenBottom - location[1]
        val centreX = (left + right) * 0.5f
        val centreY = (top + bottom) * 0.5f
        val perimeter = 2f * ((right - left) + (bottom - top)).coerceAtLeast(1f)
        val count = if (isTelevision) 46 else 72

        repeat(count) { index ->
            val distance = perimeter * (index + Random.nextFloat()) / count
            val width = right - left
            val height = bottom - top
            val point = when {
                distance < width -> Pair(left + distance, top)
                distance < width + height -> Pair(right, top + distance - width)
                distance < width * 2f + height -> Pair(right - (distance - width - height), bottom)
                else -> Pair(left, bottom - (distance - width * 2f - height))
            }
            val nx = point.first - centreX
            val ny = point.second - centreY
            val length = max(1f, hypot(nx, ny))
            val speed = (5.5f + Random.nextFloat() * 7.5f) * density
            emitBurstParticle(
                point.first,
                point.second,
                nx / length * speed,
                ny / length * speed
            )
        }
        disturbLocal(centreX, centreY, 4.2f * density)
        postInvalidateOnAnimation()
    }

    /**
     * Pulls the mesh from the moving object's actual perimeter. Particles are
     * shed from its trailing edge, so the object appears to plough through the
     * field rather than triggering an unrelated burst at its centre.
     */
    fun disturbLozenge(
        screenLeft: Float,
        screenTop: Float,
        screenRight: Float,
        screenBottom: Float,
        motionX: Float,
        motionY: Float
    ) {
        if (!motionEnabled || columns == 0) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val left = screenLeft - location[0]
        val top = screenTop - location[1]
        val right = screenRight - location[0]
        val bottom = screenBottom - location[1]
        val horizontal = kotlin.math.abs(motionX) >= kotlin.math.abs(motionY)
        val edge = if (horizontal) {
            if (motionX >= 0f) left else right
        } else {
            if (motionY >= 0f) top else bottom
        }
        repeat(11) { step ->
            val fraction = step / 10f
            val x = if (horizontal) edge else left + (right - left) * fraction
            val y = if (horizontal) top + (bottom - top) * fraction else edge
            disturbLocalFromMotion(x, y, motionX, motionY, emit = false)
        }
        val speed = hypot(motionX, motionY)
        val particleCount = (speed / 3.1f).toInt().coerceIn(10, if (isTelevision) 22 else 34)
        repeat(particleCount) {
            val fraction = Random.nextFloat()
            val x = if (horizontal) edge else left + (right - left) * fraction
            val y = if (horizontal) top + (bottom - top) * fraction else edge
            emitParticles(x, y, motionX, motionY, 1)
        }
        postInvalidateOnAnimation()
    }

    private fun disturbLocalFromMotion(
        x: Float,
        y: Float,
        motionX: Float,
        motionY: Float,
        emit: Boolean
    ) {
        val radius = 205f * density
        val minColumn = max(0, ((x - radius) / spacing).toInt())
        val maxColumn = min(columns - 1, ceil((x + radius) / spacing).toInt())
        val minRow = max(0, ((y - radius) / spacing).toInt())
        val maxRow = min(rows - 1, ceil((y + radius) / spacing).toInt())
        val speed = hypot(motionX, motionY).coerceIn(3f, 150f)

        for (row in minRow..maxRow) for (column in minColumn..maxColumn) {
            val index = row * columns + column
            val px = column * spacing + dx[index]
            val py = row * spacing + dy[index]
            val distance = hypot(px - x, py - y)
            if (distance >= radius) continue
            val falloff = 1f - distance / radius
            vx[index] += motionX.coerceIn(-105f, 105f) * falloff * 0.68f
            vy[index] += motionY.coerceIn(-105f, 105f) * falloff * 0.68f
            val nx = (px - x) / max(distance, 1f)
            val ny = (py - y) / max(distance, 1f)
            vx[index] += nx * speed * falloff * 0.29f
            vy[index] += ny * speed * falloff * 0.29f
        }
        if (emit) emitParticles(x, y, motionX, motionY, (speed / 7f).toInt().coerceIn(5, 18))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        columns = ceil(w / spacing).toInt() + 1
        rows = ceil(h / spacing).toInt() + 1
        val count = columns * rows
        dx = FloatArray(count); dy = FloatArray(count)
        vx = FloatArray(count); vy = FloatArray(count)
        ax = FloatArray(count); ay = FloatArray(count)
        nodePoints = FloatArray(count * 2)
        val maxSegments = rows * (columns - 1) + (rows - 1) * columns
        gridLines = FloatArray(maxSegments * 4)
        fineGridLines = FloatArray(max(0, (rows - 1) * (columns - 1) * 6 * 4))
        energyLines = FloatArray(maxSegments * 4)
        clearParticles()
        seedAmbientParticles()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xEE020412.toInt())
        if (motionEnabled) stepSimulation()
        buildGridLines()
        if (motionEnabled) prepareParticleGeometry()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && screenSpaceBloom != null) {
            screenSpaceBloom.draw(canvas)
        } else {
            drawFallbackGlow(canvas)
        }

        // The original sharp spring mesh and particle filaments are composited
        // over the blurred emissive buffer, exactly like a post-process bloom.
        canvas.drawLines(fineGridLines, 0, fineGridLineCount, fineGridPaint)
        canvas.drawLines(gridLines, 0, gridLineCount, gridPaint)
        canvas.drawLines(energyLines, 0, energyLineCount, energyPaint)
        if (motionEnabled) drawParticleCores(canvas)
        if (motionEnabled && isShown && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun stepSimulation() {
        val now = System.nanoTime()
        accumulatedStep += if (lastFrameNanos == 0L) 0f else
            ((now - lastFrameNanos) / 16_666_667f).coerceIn(0f, 4f)
        lastFrameNanos = now
        while (accumulatedStep >= 0.5f) {
            advanceSimulation(0.5f)
            accumulatedStep -= 0.5f
        }
    }

    private fun advanceSimulation(step: Float) {
        frameNumber++
        ax.fill(0f); ay.fill(0f)

        for (row in 0 until rows) for (column in 0 until columns) {
            val i = row * columns + column
            var forceX = -dx[i] * 0.042f
            var forceY = -dy[i] * 0.042f
            if (column > 0) {
                forceX += (dx[i - 1] - dx[i]) * 0.032f
                forceY += (dy[i - 1] - dy[i]) * 0.032f
            }
            if (column + 1 < columns) {
                forceX += (dx[i + 1] - dx[i]) * 0.032f
                forceY += (dy[i + 1] - dy[i]) * 0.032f
            }
            if (row > 0) {
                forceX += (dx[i - columns] - dx[i]) * 0.032f
                forceY += (dy[i - columns] - dy[i]) * 0.032f
            }
            if (row + 1 < rows) {
                forceX += (dx[i + columns] - dx[i]) * 0.032f
                forceY += (dy[i + columns] - dy[i]) * 0.032f
            }
            ax[i] = forceX
            ay[i] = forceY
        }

        for (i in dx.indices) {
            vx[i] = (vx[i] + ax[i] * step) * 0.964365f
            vy[i] = (vy[i] + ay[i] * step) * 0.964365f
            dx[i] += vx[i] * step
            dy[i] += vy[i] * step
        }

        // A few gentle field sources prevent the enabled theme looking frozen
        // when nobody is touching or scrolling it.
        if (frameNumber % 10 == 0 && width > 0 && height > 0) {
            val t = frameNumber / 120.0
            val x = (width * (0.50 + 0.34 * sin(t * 0.31))).toFloat()
            val y = (height * (0.50 + 0.30 * cos(t * 0.27))).toFloat()
            disturbLocal(x, y, 0.24f * density)
        }

        updateParticles(step)
    }

    private fun disturbLocal(x: Float, y: Float, strength: Float) {
        val radius = 105f * density
        val minColumn = max(0, ((x - radius) / spacing).toInt())
        val maxColumn = min(columns - 1, ceil((x + radius) / spacing).toInt())
        val minRow = max(0, ((y - radius) / spacing).toInt())
        val maxRow = min(rows - 1, ceil((y + radius) / spacing).toInt())
        for (row in minRow..maxRow) for (column in minColumn..maxColumn) {
            val i = row * columns + column
            val px = column * spacing + dx[i]
            val py = row * spacing + dy[i]
            val distance = hypot(px - x, py - y)
            if (distance in 1f..<radius) {
                val force = (1f - distance / radius) * strength
                vx[i] += (px - x) / distance * force
                vy[i] += (py - y) / distance * force
            }
        }
    }

    private fun buildGridLines() {
        gridLineCount = 0
        fineGridLineCount = 0
        energyLineCount = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val i = row * columns + column
            val x = column * spacing + dx[i]
            val y = row * spacing + dy[i]
            if (column + 1 < columns) {
                val right = i + 1
                val x2 = (column + 1) * spacing + dx[right]
                val y2 = y + dy[right] - dy[i]
                gridLines[gridLineCount++] = x
                gridLines[gridLineCount++] = y
                gridLines[gridLineCount++] = x2
                gridLines[gridLineCount++] = y2
                if (segmentEnergy(i, right) > 2.2f * density) {
                    energyLines[energyLineCount++] = x
                    energyLines[energyLineCount++] = y
                    energyLines[energyLineCount++] = x2
                    energyLines[energyLineCount++] = y2
                }
            }
            if (row + 1 < rows) {
                val below = i + columns
                val x2 = x + dx[below] - dx[i]
                val y2 = (row + 1) * spacing + dy[below]
                gridLines[gridLineCount++] = x
                gridLines[gridLineCount++] = y
                gridLines[gridLineCount++] = x2
                gridLines[gridLineCount++] = y2
                if (segmentEnergy(i, below) > 2.2f * density) {
                    energyLines[energyLineCount++] = x
                    energyLines[energyLineCount++] = y
                    energyLines[energyLineCount++] = x2
                    energyLines[energyLineCount++] = y2
                }
            }
        }
        buildFineGridLines()
    }

    /** Subdivides every elastic cell, so the subtle mesh follows the exact
     * same spring deformation instead of behaving like a static grid image. */
    private fun buildFineGridLines() {
        if (rows < 2 || columns < 2) return
        for (row in 0 until rows - 1) for (column in 0 until columns - 1) {
            val topLeft = row * columns + column
            val topRight = topLeft + 1
            val bottomLeft = topLeft + columns
            val bottomRight = bottomLeft + 1
            val tlX = column * spacing + dx[topLeft]
            val tlY = row * spacing + dy[topLeft]
            val trX = (column + 1) * spacing + dx[topRight]
            val trY = row * spacing + dy[topRight]
            val blX = column * spacing + dx[bottomLeft]
            val blY = (row + 1) * spacing + dy[bottomLeft]
            val brX = (column + 1) * spacing + dx[bottomRight]
            val brY = (row + 1) * spacing + dy[bottomRight]

            for (division in 1..3) {
                val fraction = division * 0.25f
                appendFineLine(
                    lerp(tlX, trX, fraction), lerp(tlY, trY, fraction),
                    lerp(blX, brX, fraction), lerp(blY, brY, fraction)
                )
                appendFineLine(
                    lerp(tlX, blX, fraction), lerp(tlY, blY, fraction),
                    lerp(trX, brX, fraction), lerp(trY, brY, fraction)
                )
            }
        }
    }

    private fun appendFineLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        fineGridLines[fineGridLineCount++] = x1
        fineGridLines[fineGridLineCount++] = y1
        fineGridLines[fineGridLineCount++] = x2
        fineGridLines[fineGridLineCount++] = y2
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun segmentEnergy(first: Int, second: Int): Float =
        (hypot(dx[first], dy[first]) + hypot(dx[second], dy[second])) * 0.5f

    private fun drawNodes(canvas: Canvas) {
        var offset = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val i = row * columns + column
            nodePoints[offset++] = column * spacing + dx[i]
            nodePoints[offset++] = row * spacing + dy[i]
        }
        canvas.drawPoints(nodePoints, 0, offset, nodePaint)
    }

    private fun seedAmbientParticles() {
        if (!motionEnabled || width == 0 || height == 0) return
        repeat((ambientParticleCount - particles.size).coerceAtLeast(0)) {
            addParticle(
                Random.nextFloat() * width,
                Random.nextFloat() * height,
                (Random.nextFloat() - 0.5f) * 1.8f * density,
                (Random.nextFloat() - 0.5f) * 1.8f * density,
                Random.nextFloat() * 1.2f + 0.6f,
                colours.random()
            )
        }
    }

    private fun emitParticles(x: Float, y: Float, mx: Float, my: Float, count: Int) {
        repeat(count) {
            val spread = 28f * density
            addParticle(
                x + (Random.nextFloat() - 0.5f) * spread,
                y + (Random.nextFloat() - 0.5f) * spread,
                mx * 0.09f + (Random.nextFloat() - 0.5f) * 7f * density,
                my * 0.09f + (Random.nextFloat() - 0.5f) * 7f * density,
                1.7f,
                colours.random()
            )
        }
    }

    private fun emitBurstParticle(x: Float, y: Float, outwardX: Float, outwardY: Float) {
        addParticle(
            x,
            y,
            outwardX + (Random.nextFloat() - 0.5f) * 4f * density,
            outwardY + (Random.nextFloat() - 0.5f) * 4f * density,
            1.25f + Random.nextFloat() * 0.35f,
            colours.random(),
            decay = 0.025f
        )
    }

    private fun updateParticles(step: Float) {
        var index = 0
        while (index < particles.size) {
            val p = particles[index]
            if (frameNumber % 2 == 0) {
                p.x3 = p.x2; p.y3 = p.y2
                p.x2 = p.x1; p.y2 = p.y1
                p.x1 = p.x; p.y1 = p.y
            }

            // The particles live in the same elastic field as the grid rather
            // than following a pre-baked straight-line animation.
            val column = (p.x / spacing).toInt().coerceIn(0, columns - 1)
            val row = (p.y / spacing).toInt().coerceIn(0, rows - 1)
            val fieldIndex = row * columns + column
            p.vx += dx[fieldIndex] * 0.0028f * step
            p.vy += dy[fieldIndex] * 0.0028f * step
            val curl = sin((p.x + p.y + frameNumber * 1.2f) / (54f * density)) * 0.075f * density
            p.vx += curl * step
            p.vy -= curl * 0.72f * step
            p.x += p.vx * step
            p.y += p.vy * step
            p.vx *= 0.998499f
            p.vy *= 0.998499f

            // Rebound from the viewport so energetic particles keep ricocheting
            // after the lozenge has stopped moving.
            if (p.x < 0f) { p.x = 0f; p.vx = abs(p.vx) * 0.88f }
            if (p.x > width) { p.x = width.toFloat(); p.vx = -abs(p.vx) * 0.88f }
            if (p.y < 0f) { p.y = 0f; p.vy = abs(p.vy) * 0.88f }
            if (p.y > height) { p.y = height.toFloat(); p.vy = -abs(p.vy) * 0.88f }

            p.life -= p.decay * step
            if (p.life <= 0f) {
                particles[index] = particles.last()
                particles.removeAt(particles.lastIndex)
                freeParticles.addLast(p)
            } else index++
        }
        if (particles.size < ambientParticleCount && frameNumber % 16 == 0) seedAmbientParticles()
    }

    private fun prepareParticleGeometry() {
        particleLineCounts.fill(0)
        particlePointCounts.fill(0)

        for (p in particles) {
            val colourIndex = colours.indexOf(p.colour).coerceAtLeast(0)
            val lifeBand = when {
                p.life < 0.34f -> 0
                p.life < 0.68f -> 1
                else -> 2
            }
            val bucket = colourIndex * 3 + lifeBand
            var lineOffset = particleLineCounts[bucket]
            val lines = particleLines[bucket]
            lines[lineOffset++] = p.x
            lines[lineOffset++] = p.y
            lines[lineOffset++] = p.x1
            lines[lineOffset++] = p.y1
            lines[lineOffset++] = p.x1
            lines[lineOffset++] = p.y1
            lines[lineOffset++] = p.x2
            lines[lineOffset++] = p.y2
            lines[lineOffset++] = p.x2
            lines[lineOffset++] = p.y2
            lines[lineOffset++] = p.x3
            lines[lineOffset++] = p.y3
            particleLineCounts[bucket] = lineOffset
            val offset = particlePointCounts[bucket]
            particlePoints[bucket][offset] = p.x
            particlePoints[bucket][offset + 1] = p.y
            particlePointCounts[bucket] = offset + 2
        }
    }

    private fun drawParticleCores(canvas: Canvas) {
        for (bucket in particleLines.indices) {
            val count = particlePointCounts[bucket]
            if (count == 0) continue
            val colour = colours[bucket / 3]
            val alpha = particleBandAlpha[bucket % 3]
            particlePaint.color = Color.argb(
                alpha, Color.red(colour), Color.green(colour), Color.blue(colour)
            )
            particlePaint.strokeWidth = 1.7f * density
            canvas.drawLines(
                particleLines[bucket], 0, particleLineCounts[bucket], particlePaint
            )

            particlePaint.color = Color.argb(
                alpha, Color.red(colour), Color.green(colour), Color.blue(colour)
            )
            particlePaint.strokeWidth = 2.3f * density
            canvas.drawPoints(particlePoints[bucket], 0, count, particlePaint)

            // Bright cores give the coloured screen-space bloom an emissive,
            // almost over-exposed centre instead of reading as pastel fog.
            if (bucket % 3 == 2) {
                particleHotCorePaint.alpha = 225
                particleHotCorePaint.strokeWidth = 1.05f * density
                canvas.drawPoints(particlePoints[bucket], 0, count, particleHotCorePaint)
            }
        }
    }

    private fun drawBloomSources(canvas: Canvas) {
        canvas.drawLines(gridLines, 0, gridLineCount, bloomGridSourcePaint)
        canvas.drawLines(energyLines, 0, energyLineCount, bloomEnergySourcePaint)
        drawParticleBloomSources(canvas, wide = false)
    }

    private fun drawParticleBloomSources(canvas: Canvas, wide: Boolean) {
        if (!motionEnabled) return

        for (bucket in particleLines.indices) {
            val count = particlePointCounts[bucket]
            if (count == 0) continue
            val colour = colours[bucket / 3]
            val alpha = particleBandAlpha[bucket % 3]
            bloomParticleSourcePaint.color = Color.argb(
                if (wide) min(255, alpha + 45) else alpha,
                Color.red(colour), Color.green(colour), Color.blue(colour)
            )
            bloomParticleSourcePaint.strokeWidth = (if (wide) 3.8f else 2.2f) * density
            canvas.drawLines(
                particleLines[bucket], 0, particleLineCounts[bucket], bloomParticleSourcePaint
            )
            bloomParticleSourcePaint.strokeWidth = (if (wide) 6.2f else 3.2f) * density
            canvas.drawPoints(
                particlePoints[bucket], 0, count, bloomParticleSourcePaint
            )
        }
    }

    private fun drawFallbackGlow(canvas: Canvas) {
        canvas.drawLines(gridLines, 0, gridLineCount, gridOuterGlowPaint)
        canvas.drawLines(gridLines, 0, gridLineCount, glowPaint)
        canvas.drawLines(energyLines, 0, energyLineCount, energyGlowPaint)
        if (!motionEnabled) return

        for (bucket in particleLines.indices) {
            val count = particlePointCounts[bucket]
            if (count == 0) continue
            val colour = colours[bucket / 3]
            val alpha = particleBandAlpha[bucket % 3]
            particlePaint.color = Color.argb(
                (alpha / 3).coerceAtLeast(18),
                Color.red(colour), Color.green(colour), Color.blue(colour)
            )
            particlePaint.strokeWidth = 6.2f * density
            canvas.drawLines(
                particleLines[bucket], 0, particleLineCounts[bucket], particlePaint
            )
            particlePaint.strokeWidth = 9f * density
            canvas.drawPoints(particlePoints[bucket], 0, count, particlePaint)
        }
    }

    /** Hardware Gaussian blur of the complete emissive scene, composited as
     * one screen-space post-process beneath the crisp vector layer. */
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ScreenSpaceBloom {
        // Bloom contains only low-frequency light, so rendering its buffers at
        // half resolution is visually equivalent after blur while cutting the
        // post-process pixel cost to roughly one quarter per pass.
        private val bufferScale = 0.5f
        private val sceneNode = RenderNode("Neon screen-space bloom").apply {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    4.5f * density,
                    4.5f * density,
                    Shader.TileMode.DECAL
                )
            )
        }
        private val particleNode = RenderNode("Neon wide particle bloom").apply {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    9f * density,
                    9f * density,
                    Shader.TileMode.DECAL
                )
            )
        }

        fun draw(target: Canvas) {
            if (width <= 0 || height <= 0) return
            val bufferWidth = max(1, (width * bufferScale).toInt())
            val bufferHeight = max(1, (height * bufferScale).toInt())

            particleNode.setPosition(0, 0, bufferWidth, bufferHeight)
            val wideCanvas = particleNode.beginRecording(bufferWidth, bufferHeight)
            wideCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            wideCanvas.scale(bufferScale, bufferScale)
            drawParticleBloomSources(wideCanvas, wide = true)
            particleNode.endRecording()
            target.save()
            target.scale(1f / bufferScale, 1f / bufferScale)
            target.drawRenderNode(particleNode)
            target.restore()

            sceneNode.setPosition(0, 0, bufferWidth, bufferHeight)
            val sceneCanvas = sceneNode.beginRecording(bufferWidth, bufferHeight)
            sceneCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            sceneCanvas.scale(bufferScale, bufferScale)
            drawBloomSources(sceneCanvas)
            sceneNode.endRecording()
            target.save()
            target.scale(1f / bufferScale, 1f / bufferScale)
            target.drawRenderNode(sceneNode)
            target.restore()
        }
    }

}
