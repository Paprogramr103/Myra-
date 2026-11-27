package com.myra.assistant.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.myra.assistant.model.OrbState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom Canvas View rendering MYRA's 7-layer animated neural orb for the floating overlay.
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var state: OrbState = OrbState.IDLE
    private var amplitude: Float = 0f

    private var pulseScale = 1f
    private var glowAlpha = 150
    private var rotationAngle = 0f
    private var waveOffset = 0f
    private var thinkingAngle = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringRect = RectF()
    private val thinkingRect = RectF()

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            val fraction = it.animatedFraction
            glowAlpha = (120 + (100 * sin(fraction * Math.PI))).toInt().coerceIn(120, 220)
            invalidate()
        }
    }

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            waveOffset = (rotationAngle / 360f) * (2f * Math.PI.toFloat())
            invalidate()
        }
    }

    private val thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            thinkingAngle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
        rotationAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        rotationAnimator.cancel()
        thinkingAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setOrbState(newState: OrbState) {
        if (state == newState) return
        state = newState
        if (newState == OrbState.THINKING) {
            if (!thinkingAnimator.isStarted) thinkingAnimator.start()
        } else {
            thinkingAnimator.cancel()
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) * 0.27f) * (pulseScale + amplitude * 0.14f)
        if (baseRadius <= 1f) return

        val (primaryColor, secondaryColor) = when (state) {
            OrbState.IDLE -> Color.parseColor("#B71C1C") to Color.parseColor("#880E4F")
            OrbState.LISTENING -> Color.parseColor("#FF1744") to Color.parseColor("#D500F9")
            OrbState.SPEAKING -> Color.parseColor("#E040FB") to Color.parseColor("#FF1744")
            OrbState.THINKING -> Color.parseColor("#40C4FF") to Color.parseColor("#00B0FF")
        }

        // 1. Radial glow (1.6x radius)
        val glowRadius = baseRadius * 1.6f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowRadius,
            intArrayOf(
                Color.argb(glowAlpha, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Core orb
        corePaint.shader = RadialGradient(
            cx - baseRadius * 0.2f,
            cy - baseRadius * 0.2f,
            baseRadius * 1.15f,
            intArrayOf(primaryColor, secondaryColor, Color.parseColor("#180008")),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, baseRadius, corePaint)

        // 3. 3 rotating dashed rings
        for (i in 1..3) {
            val r = baseRadius * (1.12f + i * 0.14f)
            ringRect.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.color = if (i % 2 == 0) secondaryColor else primaryColor
            ringPaint.alpha = (180 - i * 35).coerceAtLeast(45)
            val dir = if (i % 2 == 0) -1f else 1f
            canvas.save()
            canvas.rotate(rotationAngle * dir * (0.7f + i * 0.25f), cx, cy)
            canvas.drawOval(ringRect, ringPaint)
            canvas.restore()
        }

        // 4. Wave rings
        val waveSpeed = if (state == OrbState.SPEAKING) 2.2f else 1f
        wavePaint.color = primaryColor
        for (ring in 0..1) {
            val waveR = baseRadius * (1.06f + ring * 0.15f + amplitude * 0.22f)
            wavePaint.alpha = (140 - ring * 45).coerceAtLeast(40)
            val step = 12
            var prevX = 0f
            var prevY = 0f
            for (deg in 0..360 step step) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val mod = sin(rad * 6f + waveOffset * waveSpeed + ring) * (3f + amplitude * 14f)
                val x = cx + (waveR + mod) * cos(rad)
                val y = cy + (waveR + mod) * sin(rad)
                if (deg > 0) canvas.drawLine(prevX, prevY, x, y, wavePaint)
                prevX = x
                prevY = y
            }
        }

        // 5. Thinking arcs
        if (state == OrbState.THINKING) {
            val tr = baseRadius * 1.28f
            thinkingRect.set(cx - tr, cy - tr, cx + tr, cy + tr)
            thinkingPaint.color = Color.parseColor("#40C4FF")
            canvas.drawArc(thinkingRect, thinkingAngle, 75f, false, thinkingPaint)
            canvas.drawArc(thinkingRect, thinkingAngle + 180f, 75f, false, thinkingPaint)
        }

        // 6. Orbiting particles (12 dots when active/speaking)
        if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
            val orbitR = baseRadius * (1.35f + amplitude * 0.2f)
            for (p in 0 until 12) {
                val angle = Math.toRadians((rotationAngle + p * 30f).toDouble())
                val px = cx + (orbitR * cos(angle)).toFloat()
                val py = cy + (orbitR * sin(angle)).toFloat()
                particlePaint.color = if (p % 2 == 0) primaryColor else secondaryColor
                particlePaint.alpha = 185
                canvas.drawCircle(px, py, 3f + amplitude * 3f, particlePaint)
            }
        }

        // 7. Inner specular highlight
        highlightPaint.shader = RadialGradient(
            cx - baseRadius * 0.32f,
            cy - baseRadius * 0.32f,
            baseRadius * 0.55f,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx - baseRadius * 0.28f, cy - baseRadius * 0.28f, baseRadius * 0.48f, highlightPaint)
    }
}
