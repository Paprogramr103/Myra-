package com.myra.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permission-based Foreground Voice-Trigger Service.
 *
 * IMPORTANT TRANSPARENCY NOTE:
 * Android does not provide a built-in always-on offline neural hotword engine without bundling
 * a proprietary wake-word model (.ppn / .tflite). This foreground service uses Android's
 * SpeechRecognizer while the persistent notification is active and RECORD_AUDIO is granted,
 * and clearly reports its true mode in status and Diagnostics.
 */
class WakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var usingOnDeviceRecognizer = false
    private var recognitionLanguage = "en-IN"
    private var isListeningLoopActive = false
    private var lastWakeTriggerAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            isRunning = false
            _statusNote.value = "Blocked: RECORD_AUDIO permission required."
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isRunning = true
        } catch (e: Exception) {
            isRunning = false
            _statusNote.value = "Foreground service start blocked by OS (${e.javaClass.simpleName})."
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> startVoiceTriggerLoop()
            else -> startVoiceTriggerLoop()
        }
        return START_STICKY
    }

    private fun pauseListening() {
        isListeningLoopActive = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        _statusNote.value = "Paused while MYRA app is in foreground."
        getSystemService(NotificationManager::class.java).cancel(WAKE_ALERT_NOTIF_ID)
        updateNotification("Wake listening paused while MYRA is open.")
    }

    private fun startVoiceTriggerLoop() {
        if (isListeningLoopActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _statusNote.value = "OS SpeechRecognizer unavailable on this device (offline hotword model not bundled)."
            updateNotification("Speech recognition unavailable on this device.")
            return
        }
        isListeningLoopActive = true
        _statusNote.value = "Starting Android speech recognition for 'Hey MYRA'."
        updateNotification("Starting wake phrase recognition...")
        mainHandler.post { initAndStartRecognizer() }
    }

    private fun initAndStartRecognizer() {
        if (!isListeningLoopActive) return
        runCatching { speechRecognizer?.destroy() }
        val recognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }
                .onSuccess { usingOnDeviceRecognizer = true }
                .getOrElse {
                    usingOnDeviceRecognizer = false
                    SpeechRecognizer.createSpeechRecognizer(this)
                }
        } else {
            usingOnDeviceRecognizer = false
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer = recognizer
        _statusNote.value = if (usingOnDeviceRecognizer) {
            "On-device SpeechRecognizer active; say 'Hey MYRA'. Not a dedicated hotword model."
        } else {
            "OS SpeechRecognizer active; this device may use network recognition. Not an offline hotword model."
        }
        updateNotification(
            if (usingOnDeviceRecognizer) "On-device recognition active for 'Hey MYRA'."
            else "OS speech recognition active for 'Hey MYRA'.",
        )

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                checkMatches(partialResults)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val triggered = checkMatches(results)
                if (!triggered && isListeningLoopActive) {
                    scheduleRestart(350L)
                }
            }

            override fun onError(error: Int) {
                if (isListeningLoopActive) {
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 600L
                    scheduleRestart(delay)
                }
            }
        })

        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
        }
        runCatching { recognizer.startListening(listenIntent) }
            .onFailure { scheduleRestart(1500L) }
    }

    private fun checkMatches(bundle: Bundle?): Boolean {
        if (!isListeningLoopActive) return false
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val wakePhrases = listOf(
            "hey myra", "hi myra", "hello myra", "ok myra", "okay myra",
            "hey mira", "hi mira", "hello mira", "ok mira", "okay mira",
            "hey मायरा", "hi मायरा", "hello मायरा", "ओके मायरा", "हे मायरा",
            "सुनो मायरा", "हाय मायरा", "हेलो मायरा",
        )
        val matched = matches.any { phrase ->
            val normalized = phrase.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            wakePhrases.any { wake ->
                normalized == wake || normalized.startsWith("$wake ")
            } || normalized == "myra" || normalized == "mira" || normalized == "मायरा"
        }
        val now = System.currentTimeMillis()
        if (!matched || now - lastWakeTriggerAtMs < WAKE_TRIGGER_COOLDOWN_MS) return false

        lastWakeTriggerAtMs = now
        isListeningLoopActive = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        _statusNote.value = "Wake phrase detected — tap the MYRA notification if the app did not open."
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_WAKE_TRIGGERED, true)
        }
        updateNotification("Wake phrase heard — tap the MYRA alert to open.")
        getSystemService(NotificationManager::class.java).notify(
            WAKE_ALERT_NOTIF_ID,
            buildWakeAlertNotification(launch),
        )
        runCatching { startActivity(launch) }
        return true
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isListeningLoopActive) return
        mainHandler.removeCallbacksAndMessages(null)
        recognitionLanguage = nextWakeRecognitionLanguage(recognitionLanguage)
        mainHandler.postDelayed({ initAndStartRecognizer() }, delayMs)
    }

    override fun onDestroy() {
        isRunning = false
        isListeningLoopActive = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        _statusNote.value = "Stopped"
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_wake_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val wakeAlertChannel = NotificationChannel(
                WAKE_ALERT_CHANNEL_ID,
                "MYRA Wake Phrase Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Visible alert when Hey MYRA is detected."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(wakeAlertChannel)
        }
    }

    private fun buildWakeAlertNotification(launchIntent: Intent): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            WAKE_ALERT_NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WAKE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("Hey MYRA detected")
            .setContentText("Tap to start MYRA voice assistant.")
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(contentText))
    }

    private fun buildNotification(
        contentText: String = "Listening via OS SpeechRecognizer for 'Hey MYRA'",
        launchIntent: Intent = Intent(this, MainActivity::class.java),
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA Voice Trigger (Foreground)")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "myra_wake_word_channel"
        const val NOTIF_ID = 2003
        private const val WAKE_ALERT_NOTIF_ID = 2004
        private const val WAKE_ALERT_CHANNEL_ID = "myra_wake_alert_channel"
        const val ACTION_STOP = "com.myra.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.myra.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.myra.RESUME_WAKE_WORD"
        private const val WAKE_TRIGGER_COOLDOWN_MS = 4_000L
        const val EXTRA_WAKE_TRIGGERED = "WAKE_TRIGGERED"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val _statusNote = MutableStateFlow("Inactive (Offline hotword model not bundled)")
        val statusNote: StateFlow<String> = _statusNote.asStateFlow()
    }
}

/** Alternates recognizer locale so Hindi and English wake phrases both get listening turns. */
internal fun nextWakeRecognitionLanguage(current: String): String =
    if (current == "en-IN") "hi-IN" else "en-IN"
