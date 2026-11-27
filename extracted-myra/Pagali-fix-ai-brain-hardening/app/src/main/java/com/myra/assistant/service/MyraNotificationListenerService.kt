package com.myra.assistant.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myra.assistant.model.NotificationSummary
import com.myra.assistant.model.OtpAlert
import com.myra.assistant.security.OtpSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Notification Listener with strict local-only OTP detection.
 * OTPs are kept ONLY in volatile RAM for 60 seconds and are NEVER written to disk or sent over any network.
 */
class MyraNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val combined = listOf(title, bigText.ifEmpty { text }).filter { it.isNotEmpty() }.joinToString(" — ")
        if (combined.isBlank()) return

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        // Check for OTP locally in volatile memory only
        val otpCode = OtpSanitizer.extractOtpCode(combined)
        if (otpCode != null) {
            val now = System.currentTimeMillis()
            val alert = OtpAlert(
                id = UUID.randomUUID().toString(),
                sender = title.ifEmpty { appLabel },
                code = otpCode,
                detectedAt = now,
                expiresAt = now + OTP_TTL_MS,
            )
            _ephemeralOtpAlert.value = alert
            scope.launch {
                delay(OTP_TTL_MS)
                if (_ephemeralOtpAlert.value?.id == alert.id) {
                    _ephemeralOtpAlert.value = null
                }
            }
            // IMPORTANT: Do NOT add OTP notifications into recentNotifications!
            return
        }

        val summary = NotificationSummary(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text.ifEmpty { bigText },
            timestamp = sbn.postTime,
        )
        val current = _recentNotifications.value.filterNot { it.key == summary.key }
        _recentNotifications.value = (listOf(summary) + current).take(20)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        _recentNotifications.value = _recentNotifications.value.filterNot { it.key == sbn.key }
    }

    companion object {
        private const val OTP_TTL_MS = 60_000L

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _ephemeralOtpAlert = MutableStateFlow<OtpAlert?>(null)
        val ephemeralOtpAlert: StateFlow<OtpAlert?> = _ephemeralOtpAlert.asStateFlow()

        private val _recentNotifications = MutableStateFlow<List<NotificationSummary>>(emptyList())
        val recentNotifications: StateFlow<List<NotificationSummary>> = _recentNotifications.asStateFlow()

        fun dismissOtpAlert() {
            _ephemeralOtpAlert.value = null
        }
    }
}
