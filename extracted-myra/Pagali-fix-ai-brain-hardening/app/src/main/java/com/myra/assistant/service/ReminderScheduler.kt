package com.myra.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.myra.assistant.ui.main.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local one-shot reminders. Uses inexact idle-capable alarms so MYRA does not
 * request the special exact-alarm access. Android may defer delivery.
 */
object ReminderScheduler {
    private const val PREFS = "myra_local_reminders"
    private const val ITEMS = "items"
    private const val CHANNEL_ID = "myra_reminders"

    @Synchronized
    fun schedule(context: Context, title: String, delayMinutes: Int): Boolean {
        val cleanTitle = title.trim().take(180)
        if (cleanTitle.isBlank() || delayMinutes !in 1..10_080) return false
        val id = (UUID.randomUUID().hashCode() and 0x7fffffff).coerceAtLeast(1)
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        val item = JSONObject().put("id", id).put("title", cleanTitle).put("at", triggerAt)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readItems(prefs.getString(ITEMS, "[]"))
        items.put(item)
        if (!prefs.edit().putString(ITEMS, items.toString()).commit()) return false
        scheduleAlarm(context, id, triggerAt)
        return true
    }

    @Synchronized
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldItems = readItems(prefs.getString(ITEMS, "[]"))
        val validItems = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until oldItems.length()) {
            val item = oldItems.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            val at = item.optLong("at", 0L)
            if (id > 0 && at > now && item.optString("title").isNotBlank()) {
                validItems.put(item)
                scheduleAlarm(context, id, at)
            }
        }
        prefs.edit().putString(ITEMS, validItems.toString()).apply()
    }

    @Synchronized
    internal fun deliver(context: Context, id: Int, title: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldItems = readItems(prefs.getString(ITEMS, "[]"))
        val remaining = JSONArray()
        for (i in 0 until oldItems.length()) {
            val item = oldItems.optJSONObject(i) ?: continue
            if (item.optInt("id") != id) remaining.put(item)
        }
        prefs.edit().putString(ITEMS, remaining.toString()).apply()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "MYRA Reminders", android.app.NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MYRA Reminder")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun scheduleAlarm(context: Context, id: Int, at: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction("com.myra.assistant.REMINDER_$id")
            .putExtra("reminder_id", id)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun readItems(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra("reminder_id", 0) ?: 0
        if (id <= 0) return
        val prefs = context.getSharedPreferences("myra_local_reminders", Context.MODE_PRIVATE)
        val raw = prefs.getString("items", "[]")
        val title = runCatching {
            val items = JSONArray(raw ?: "[]")
            (0 until items.length()).asSequence()
                .mapNotNull { items.optJSONObject(it) }
                .firstOrNull { it.optInt("id") == id }
                ?.optString("title")
        }.getOrNull().orEmpty()
        if (title.isNotBlank()) ReminderScheduler.deliver(context, id, title)
    }
}
