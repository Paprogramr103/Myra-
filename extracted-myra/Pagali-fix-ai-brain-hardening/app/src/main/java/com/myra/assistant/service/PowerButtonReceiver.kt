package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Detects rapid double screen toggle (SCREEN_OFF/SCREEN_ON within 600ms)
 * and brings up the floating MYRA Orb overlay.
 */
class PowerButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SCREEN_OFF && action != Intent.ACTION_SCREEN_ON) return

        val now = System.currentTimeMillis()
        val delta = now - lastToggleTimeMs
        lastToggleTimeMs = now

        if (delta in 60L..600L) {
            if (Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, MyraOverlayService::class.java).apply {
                    this.action = MyraOverlayService.ACTION_SHOW_OVERLAY
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }

    companion object {
        @Volatile
        private var lastToggleTimeMs: Long = 0L
    }
}
