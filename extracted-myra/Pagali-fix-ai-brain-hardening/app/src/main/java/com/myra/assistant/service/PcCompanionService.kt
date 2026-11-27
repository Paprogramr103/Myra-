package com.myra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

class PcCompanionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

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

        val app = application as MyraApplication
        val port = app.settingsRepository.settings.value.pcCompanionPort
        app.pcCompanionServer.start(port)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        (application as? MyraApplication)?.pcCompanionServer?.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_companion_name),
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
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PcCompanionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA PC Companion Running")
            .setContentText("Starting local Wi-Fi WebSocket server; verify status in MYRA.")
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myra_pc_companion_channel"
        const val NOTIF_ID = 2007
        const val ACTION_STOP = "com.myra.STOP_PC_COMPANION"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
