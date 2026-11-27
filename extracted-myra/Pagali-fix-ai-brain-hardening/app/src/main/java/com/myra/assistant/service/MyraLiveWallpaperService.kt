package com.myra.assistant.service

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive MYRA Neural Orb Live Wallpaper Service.
 * Pauses frame loop automatically when wallpaper is hidden to conserve battery.
 */
class MyraLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MyraOrbEngine()

    inner class MyraOrbEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var phase = 0f
        private var touchRippleRadius = 0f
        private var touchX = -1f
        private var touchY = -1f

        private val bgPaint = Paint().apply { color = Color.parseColor("#050505") }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
        }
        private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#FF1744")
        }
        private val rect = RectF()

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) {
                    handler.postDelayed(this, 33L) // ~30fps battery-conscious
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            visible = false
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event?.action == MotionEvent.ACTION_DOWN) {
                touchX = event.x
                touchY = event.y
                touchRippleRadius = 10f
            }
            super.onTouchEvent(event)
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    render(canvas)
                }
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun render(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            phase = (phase + 0.03f) % (Math.PI.toFloat() * 200f)

            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val pulse = 1f + 0.08f * sin(phase * 1.5f)
            val baseR = minOf(w, h) * 0.19f * pulse

            // Ambient corner glows
            glowPaint.shader = RadialGradient(
                cx,
                cy,
                baseR * 2.4f,
                intArrayOf(Color.argb(120, 255, 23, 68), Color.argb(40, 213, 0, 249), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, baseR * 2.4f, glowPaint)

            // Core sphere
            corePaint.shader = RadialGradient(
                cx - baseR * 0.25f,
                cy - baseR * 0.25f,
                baseR * 1.15f,
                intArrayOf(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"), Color.parseColor("#140008")),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, baseR, corePaint)

            // Rotating dashed rings
            for (i in 1..3) {
                val r = baseR * (1.18f + i * 0.16f)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                ringPaint.color = if (i % 2 == 0) Color.parseColor("#D500F9") else Color.parseColor("#FF1744")
                ringPaint.alpha = 160 - i * 35
                canvas.save()
                val dir = if (i % 2 == 0) -1f else 1f
                canvas.rotate((phase * 25f * dir * (0.7f + i * 0.2f)) % 360f, cx, cy)
                canvas.drawOval(rect, ringPaint)
                canvas.restore()
            }

            // Orbiting particles
            val orbitR = baseR * 1.48f
            for (p in 0 until 12) {
                val a = phase * 0.9f + p * (Math.PI.toFloat() * 2f / 12f)
                val px = cx + orbitR * cos(a)
                val py = cy + orbitR * sin(a)
                particlePaint.color = if (p % 2 == 0) Color.parseColor("#FF1744") else Color.parseColor("#D500F9")
                particlePaint.alpha = 180
                canvas.drawCircle(px, py, 4.5f, particlePaint)
            }

            // Touch ripple
            if (touchRippleRadius > 0f && touchX >= 0f) {
                val alpha = (255 - (touchRippleRadius / (minOf(w, h) * 0.5f)) * 255).toInt().coerceIn(0, 255)
                if (alpha > 5) {
                    ripplePaint.alpha = alpha
                    canvas.drawCircle(touchX, touchY, touchRippleRadius, ripplePaint)
                    touchRippleRadius += 18f
                } else {
                    touchRippleRadius = 0f
                }
            }
        }
    }
}
