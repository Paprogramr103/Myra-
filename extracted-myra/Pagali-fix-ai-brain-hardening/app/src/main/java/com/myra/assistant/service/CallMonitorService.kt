package com.myra.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

class CallMonitorService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isRunning = true
        } catch (_: Exception) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        unregisterCallStateListener()
        if (!registerCallStateListener()) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        unregisterCallStateListener()
        getSystemService(NotificationManager::class.java).cancel(INCOMING_CALL_NOTIF_ID)
        super.onDestroy()
    }

    private fun registerCallStateListener(): Boolean {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return false

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        telephonyManager = tm
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleStateTransition(state, null)
                }
            }
            telephonyCallback = callback
            runCatching { tm.registerTelephonyCallback(mainExecutor, callback) }.isSuccess
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleStateTransition(state, phoneNumber)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }.isSuccess
        }
    }

    private fun unregisterCallStateListener() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
            legacyListener = null
        }
    }

    private fun handleStateTransition(state: Int, rawNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    val callerName = resolveCallerName(rawNumber)
                    sendBroadcast(
                        Intent(ACTION_INCOMING_CALL)
                            .setPackage(packageName)
                            .putExtra(EXTRA_INCOMING_CALL, true)
                            .putExtra(EXTRA_CALLER_NAME, callerName),
                    )
                    showIncomingCallNotification(callerName)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                    getSystemService(NotificationManager::class.java).cancel(INCOMING_CALL_NOTIF_ID)
                    sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(packageName))
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> Unit
        }
        lastState = state
    }

    private fun resolveCallerName(incomingNumber: String?): String {
        val clean = incomingNumber?.filter { it.isDigit() }.orEmpty()
        if (clean.length < 7) return "Unknown caller"

        val hasContacts = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return incomingNumber ?: "Unknown caller"

        val tail = clean.takeLast(7)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val matchedName = runCatching {
            var found: String? = null
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).orEmpty()
                    val number = cursor.getString(1).orEmpty().filter { it.isDigit() }
                    if (number.endsWith(tail) && name.isNotBlank()) {
                        found = name
                        break
                    }
                }
            }
            found
        }.getOrNull()
        return matchedName ?: incomingNumber ?: "Unknown caller"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_call_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val incomingChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "MYRA Incoming Call Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when MYRA detects an incoming cellular call."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(incomingChannel)
        }
    }

    private fun showIncomingCallNotification(callerName: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            INCOMING_CALL_NOTIF_ID,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_INCOMING_CALL, true)
                putExtra(EXTRA_CALLER_NAME, callerName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("Incoming call")
            .setContentText("$callerName — Tap to open MYRA")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_myra_notif, "Open MYRA", openIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(INCOMING_CALL_NOTIF_ID, notification)
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
            .setContentTitle("MYRA Call Monitor Active")
            .setContentText("Ready to announce incoming calls")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myra_call_monitor_channel"
        const val INCOMING_CALL_CHANNEL_ID = "myra_incoming_call_channel"
        const val NOTIF_ID = 2002
        const val INCOMING_CALL_NOTIF_ID = 2005
        const val ACTION_INCOMING_CALL = "com.myra.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        const val EXTRA_INCOMING_CALL = "INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
