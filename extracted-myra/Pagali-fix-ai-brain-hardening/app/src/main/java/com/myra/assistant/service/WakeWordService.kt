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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permission-based offline wake-word foreground service.
 *
 * A Hindi Vosk acoustic model is packaged into the CI-built APK and used for
 * offline Hindi phrase recognition. This service owns the microphone only while the app is in
 * the background. MainActivity pauses this service before Gemini Live takes the mic.
 */
class WakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shouldListen = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var cpuWakeLock: PowerManager.WakeLock? = null
    private var lastWakeTriggerAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            isRunning = false
            _statusNote.value = "Blocked: microphone permission is required."
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isRunning = true
        } catch (error: Exception) {
            isRunning = false
            _statusNote.value = "Android blocked microphone foreground service: ${error.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> startKeywordSpotting()
            else -> startKeywordSpotting()
        }
        return START_STICKY
    }

    private fun pauseListening() {
        stopListening()
        _statusNote.value = "Paused while MYRA is in foreground; Gemini may use the microphone."
        updateNotification("Wake listening paused while MYRA is open.")
        getSystemService(NotificationManager::class.java).cancel(WAKE_ALERT_NOTIF_ID)
    }

    private fun startKeywordSpotting() {
        if (shouldListen.get()) return
        if (workerThread?.isAlive == true) {
            mainHandler.postDelayed({ startKeywordSpotting() }, 350L)
            return
        }
        acquireCpuWakeLock()
        shouldListen.set(true)
        _statusNote.value = "Loading offline Hindi MYRA model..."
        updateNotification("Loading offline Hindi wake-word model...")
        workerThread = thread(name = "MYRA-Offline-WakeWord", isDaemon = true) {
            runKeywordSpotter()
        }
    }

    private fun runKeywordSpotter() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var detected = false
        try {
            val modelDirectory = ensureHindiModelDirectory()
            val activeModel = Model(modelDirectory.absolutePath)
            model = activeModel
            val grammar = org.json.JSONArray().apply {
                put("हे मायरा")
                put("सुनो मायरा")
                put("हाय मायरा")
                put("हेलो मायरा")
                put("hey myra")
                put("hi myra")
                put("hello myra")
                put("[unk]")
            }.toString()
            val activeRecognizer = Recognizer(activeModel, SAMPLE_RATE.toFloat(), grammar)
            recognizer = activeRecognizer
            activeRecognizer.setWords(false)

            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "Android could not allocate a 16 kHz microphone buffer ($minimumBuffer)" }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimumBuffer * 2, SAMPLE_RATE * 2),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                "Android microphone could not be initialized"
            }
            audioRecord = recorder
            recorder.startRecording()
            _statusNote.value = "Hindi offline wake listening active — कहें ‘हे मायरा’ या ‘सुनो मायरा’।"
            updateNotification("Hindi offline listening: हे मायरा / सुनो मायरा")

            val pcm = ShortArray(CHUNK_SAMPLES)
            val pcmBytes = ByteArray(CHUNK_SAMPLES * 2)
            while (shouldListen.get()) {
                val count = recorder.read(pcm, 0, pcm.size)
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) {
                    error("Microphone capture stopped (AudioRecord=$count)")
                }
                if (count <= 0) continue

                for (index in 0 until count) {
                    val sample = pcm[index].toInt()
                    pcmBytes[index * 2] = (sample and 0xff).toByte()
                    pcmBytes[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                }
                val isFinal = activeRecognizer.acceptWaveForm(pcmBytes, count * 2)
                val resultJson = if (isFinal) activeRecognizer.result else activeRecognizer.partialResult
                val recognized = runCatching {
                    val result = org.json.JSONObject(resultJson)
                    result.optString(if (isFinal) "text" else "partial")
                }.getOrDefault("")
                if (isHindiWakePhrase(recognized)) {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTriggerAtMs >= WAKE_TRIGGER_COOLDOWN_MS) {
                        lastWakeTriggerAtMs = now
                        detected = true
                        shouldListen.set(false)
                        break
                    }
                }
            }
        } catch (error: Exception) {
            if (shouldListen.get()) {
                _statusNote.value = "Hindi offline wake engine could not start: \${error.message ?: error.javaClass.simpleName}"
                updateNotification("Hindi wake model unavailable. Open MYRA to retry.")
            }
        } finally {
            shouldListen.set(false)
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            audioRecord?.let { recorder ->
                runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                runCatching { recorder.release() }
            }
            audioRecord = null
            releaseCpuWakeLock()
            workerThread = null
        }

        if (detected) {
            mainHandler.post { onWakePhraseDetected() }
        }
    }

    private fun ensureHindiModelDirectory(): java.io.File {
        val destination = java.io.File(filesDir, "models/$MODEL_ASSET_DIR")
        val marker = java.io.File(destination, "uuid")
        val expectedMarker = assets.open("$MODEL_ASSET_DIR/uuid").bufferedReader().use { it.readLine() }
        val installedMarker = runCatching { marker.readText().trim() }.getOrNull()
        if (installedMarker != expectedMarker) {
            destination.deleteRecursively()
            copyAssetDirectory(MODEL_ASSET_DIR, destination)
        }
        check(java.io.File(destination, "am/final.mdl").isFile) {
            "Hindi Vosk acoustic model is incomplete"
        }
        return destination
    }

    private fun copyAssetDirectory(assetPath: String, destination: java.io.File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetDirectory("$assetPath/$child", java.io.File(destination, child))
        }
    }

    private fun isHindiWakePhrase(raw: String): Boolean {
        val text = raw.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return false
        val hasName = text.contains("मायरा") || text.contains("myra") || text.contains("mayra")
        val hasCallWord = listOf("हे", "सुनो", "हाय", "हेलो", "hey", "hi", "hello").any(text::contains)
        return hasName && hasCallWord
    }

    private fun acquireCpuWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (cpuWakeLock?.isHeld != true) {
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MYRA:OfflineWakeWord",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        cpuWakeLock = null
    }

    private fun stopListening() {
        shouldListen.set(false)
        audioRecord?.let { recorder ->
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
        }
    }

    private fun onWakePhraseDetected() {
        _statusNote.value = "Hindi wake phrase detected. Opening the assistant; microphone capture has been released."
        updateNotification("Hindi wake phrase detected — opening MYRA.")
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_WAKE_TRIGGERED, true)
        }
        getSystemService(NotificationManager::class.java).notify(
            WAKE_ALERT_NOTIF_ID,
            buildWakeAlertNotification(launch),
        )
        // Android may block background Activity launches on some versions/OEM builds.
        // The high-priority notification remains the reliable user-visible fallback.
        runCatching { startActivity(launch) }
    }

    override fun onDestroy() {
        stopListening()
        releaseCpuWakeLock()
        isRunning = false
        _statusNote.value = "Stopped"
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_wake_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    WAKE_ALERT_CHANNEL_ID,
                    "MYRA Wake Phrase Alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Visible alert when the offline Hey MYRA phrase is detected."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
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
            .setContentTitle("MYRA wake phrase detected")
            .setContentText("Tap to start MYRA if Android did not open it automatically.")
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(contentText))
    }

    private fun buildNotification(
        contentText: String = "Hindi offline wake listening: हे मायरा / सुनो मायरा",
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle("MYRA Offline Wake Word")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val MODEL_ASSET_DIR = "vosk-model-small-hi-0.22"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600
        private const val WAKE_TRIGGER_COOLDOWN_MS = 4_000L

        const val CHANNEL_ID = "myra_wake_word_channel"
        const val NOTIF_ID = 2003
        private const val WAKE_ALERT_NOTIF_ID = 2004
        private const val WAKE_ALERT_CHANNEL_ID = "myra_wake_alert_channel"
        const val ACTION_STOP = "com.myra.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.myra.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.myra.RESUME_WAKE_WORD"
        const val EXTRA_WAKE_TRIGGERED = "WAKE_TRIGGERED"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val _statusNote = kotlinx.coroutines.flow.MutableStateFlow(
            "Inactive (offline Hindi wake model is loaded by the app build)",
        )
        val statusNote: kotlinx.coroutines.flow.StateFlow<String> = _statusNote.asStateFlow()
    }
}

/** Retained for compatibility with diagnostics/tests that used the earlier locale helper. */
internal fun nextWakeRecognitionLanguage(current: String): String =
    if (current == "en-IN") "hi-IN" else "en-IN"
