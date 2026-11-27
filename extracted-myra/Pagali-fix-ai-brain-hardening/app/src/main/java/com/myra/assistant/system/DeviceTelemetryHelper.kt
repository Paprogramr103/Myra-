package com.myra.assistant.system

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.myra.assistant.model.PermissionSnapshot
import com.myra.assistant.model.SystemStatus
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.service.MyraNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceTelemetryHelper(private val context: Context) {

    fun readSystemStatus(isTorchOn: Boolean): SystemStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)

        val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        return SystemStatus(
            batteryPercent = batteryPct,
            isCharging = charging,
            usedRamMb = usedMb,
            totalRamMb = totalMb,
            timeLabel = timeLabel,
            isTorchOn = isTorchOn,
        )
    }

    fun readPermissionSnapshot(): PermissionSnapshot {
        return PermissionSnapshot(
            microphone = isGranted(Manifest.permission.RECORD_AUDIO),
            contacts = isGranted(Manifest.permission.READ_CONTACTS),
            phoneCall = isGranted(Manifest.permission.CALL_PHONE),
            phoneState = isGranted(Manifest.permission.READ_PHONE_STATE),
            answerCalls = isGranted(Manifest.permission.ANSWER_PHONE_CALLS),
            camera = isGranted(Manifest.permission.CAMERA),
            location = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
            notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isGranted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            },
            bluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isGranted(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                true
            },
            overlay = Settings.canDrawOverlays(context),
            accessibility = AccessibilityHelperService.isEnabled(context),
            notificationListener = isNotificationListenerEnabled(),
        )
    }

    fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val expected = ComponentName(context, MyraNotificationListenerService::class.java).flattenToString()
        val shortExpected = ComponentName(context, MyraNotificationListenerService::class.java).flattenToShortString()
        return flat.split(':').any { it.equals(expected, true) || it.equals(shortExpected, true) }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
