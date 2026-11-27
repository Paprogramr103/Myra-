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

class MyraConnectService : Service() {

    private val manager by lazy { (application as MyraApplication).myraConnectManager }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        manager.start()
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Connect",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps user-enabled MYRA phone-to-phone pairing available on the local network."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA Connect is active")
            .setContentText("MYRA Connect service is running; confirm the local host status in the app.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.myra.MYRA_CONNECT_START"
        const val ACTION_STOP = "com.myra.MYRA_CONNECT_STOP"
        private const val CHANNEL_ID = "myra_connect_service_channel"
        private const val NOTIFICATION_ID = 2006
    }
}
