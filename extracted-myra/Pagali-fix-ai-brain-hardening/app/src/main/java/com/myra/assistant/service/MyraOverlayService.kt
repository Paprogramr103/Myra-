package com.myra.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.model.OrbState
import com.myra.assistant.ui.components.OrbAnimationView
import com.myra.assistant.ui.main.MainActivity
import kotlin.math.abs

class MyraOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var powerReceiver: PowerButtonReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val receiver = PowerButtonReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        powerReceiver = receiver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isRunning = true
        } catch (_: Exception) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_HIDE_OVERLAY) {
            removeOverlayView()
        } else {
            showOverlayView()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOverlayView() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_orb, null)
        val orb = view.findViewById<OrbAnimationView>(R.id.overlayOrbView)
        orb.setOrbState(OrbState.IDLE)

        val closeBtn = view.findViewById<ImageButton>(R.id.overlayCloseBtn)
        closeBtn.setOnClickListener {
            removeOverlayView()
        }

        val sizePx = (160 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        val openIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(EXTRA_FROM_OVERLAY, true)
                        }
                        startActivity(openIntent)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
            layoutParams = params
        }
    }

    private fun removeOverlayView() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        overlayView = null
        layoutParams = null
    }

    override fun onDestroy() {
        isRunning = false
        removeOverlayView()
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerReceiver = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_overlay_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA Floating Orb")
            .setContentText("Tap orb to open MYRA or double-toggle power button")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myra_overlay_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SHOW_OVERLAY = "com.myra.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.myra.HIDE_OVERLAY"
        const val EXTRA_FROM_OVERLAY = "FROM_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
