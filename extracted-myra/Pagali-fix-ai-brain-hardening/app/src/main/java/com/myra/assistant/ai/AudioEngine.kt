package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    var onMicAudioChunk: ((ByteArray) -> Unit)? = null
    var onMicAudioStreamEnded: (() -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaybackRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    @Volatile private var audioSuspended = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState = false
    private var previousBluetoothScoState = false
    private var audioRouteConfigured = false
    private var audioDeviceCallbackRegistered = false

    var onAudioRouteChanged: ((String) -> Unit)? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (audioRouteConfigured) configureAudioRouting(force = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (audioRouteConfigured) configureAudioRouting(force = true)
        }
    }

    @Volatile
    private var suppressMicUntilMs: Long = 0L

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_AUDIO_CHUNKS)

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var recordThread: Thread? = null
    private var playbackThread: Thread? = null

    fun isCurrentlyMuted(): Boolean = isMuted.get()
    fun isCurrentlySpeaking(): Boolean = isSpeaking.get()

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        if (muted) {
            onAmplitudeChanged?.invoke(0f)
        }
    }

    @Synchronized
    fun startRecording(): Boolean {
        if (isReleased.get() || audioSuspended) return false
        if (isRecording.get()) return true
        if (recordThread?.isAlive == true) {
            onError?.invoke("Microphone is still stopping; try again in a moment.")
            return false
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            onError?.invoke("Microphone permission is not granted.")
            return false
        }

        configureAudioRouting()
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError?.invoke("Device rejected 16kHz PCM audio recording.")
            return false
        }

        val bufferSize = maxOf(minBuf, CHUNK_SIZE * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            onError?.invoke("Microphone permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            onError?.invoke("Failed to initialize AudioRecord: ${e.message}")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError?.invoke("AudioRecord could not initialize.")
            return false
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                    echoCanceler = this
                }
            }
        }

        audioRecord = recorder
        isRecording.set(true)

        recordThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(CHUNK_SIZE)
            var wasSendingAudio = false
            try {
                recorder.startRecording()
                while (isRecording.get() && !isReleased.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val now = System.currentTimeMillis()
                        val echoSuppressed = isSpeaking.get() || now < suppressMicUntilMs
                        if (!isMuted.get() && !echoSuppressed) {
                            val chunk = buffer.copyOf(read)
                            onMicAudioChunk?.invoke(chunk)
                            wasSendingAudio = true
                            val rms = calculateNormalizedRms(chunk, read)
                            onAmplitudeChanged?.invoke(rms)
                        } else {
                            if (wasSendingAudio) {
                                onMicAudioStreamEnded?.invoke()
                                wasSendingAudio = false
                            }
                            onAmplitudeChanged?.invoke(0f)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isReleased.get()) {
                    onError?.invoke("Audio recording error: ${e.message}")
                }
            } finally {
                if (wasSendingAudio) onMicAudioStreamEnded?.invoke()
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
                runCatching { echoCanceler?.release() }
                echoCanceler = null
                isRecording.set(false)
            }
        }, "MyraAudioRecord").apply { start() }

        return true
    }

    @Synchronized
    fun stopRecording() {
        isRecording.set(false)
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        val thread = recordThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(RECORD_STOP_JOIN_MS) }
        }
        if (thread?.isAlive != true) recordThread = null
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { recorder?.release() }
        if (audioRecord === recorder) audioRecord = null
    }

    @Synchronized
    fun configureAudioRouting(force: Boolean = false) {
        if (isReleased.get() || audioSuspended || (audioRouteConfigured && !force)) return
        val manager = audioManager ?: return
        if (!audioRouteConfigured) {
            previousAudioMode = manager.mode
            previousSpeakerphoneState = manager.isSpeakerphoneOn
            previousBluetoothScoState = manager.isBluetoothScoOn
            audioRouteConfigured = true
        }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = manager.availableCommunicationDevices
                val selected = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (selected != null && manager.setCommunicationDevice(selected)) {
                    onAudioRouteChanged?.invoke(audioDeviceLabel(selected.type))
                } else {
                    onAudioRouteChanged?.invoke("Android default communication route")
                }
            } else {
                @Suppress("DEPRECATION")
                val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                @Suppress("DEPRECATION")
                val bluetoothSco = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (bluetoothSco != null && manager.isBluetoothScoAvailableOffCall) {
                    @Suppress("DEPRECATION")
                    manager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    manager.isBluetoothScoOn = true
                    manager.isSpeakerphoneOn = false
                    onAudioRouteChanged?.invoke("Bluetooth headset (SCO)")
                } else {
                    @Suppress("DEPRECATION")
                    val wired = outputs.any {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    if (!wired) {
                        @Suppress("DEPRECATION")
                        manager.isSpeakerphoneOn = true
                        onAudioRouteChanged?.invoke("Built-in speaker")
                    } else {
                        @Suppress("DEPRECATION")
                        manager.isSpeakerphoneOn = false
                        onAudioRouteChanged?.invoke("Wired headset")
                    }
                }
            }
        }.onFailure {
            onError?.invoke("Could not select an audio route: ${it.message ?: "Android audio routing failed."}")
            onAudioRouteChanged?.invoke("Android default audio route")
        }

        if (!audioDeviceCallbackRegistered) {
            runCatching {
                manager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
                audioDeviceCallbackRegistered = true
            }
        }
    }

    @Synchronized
    fun resetAudioRouting() {
        val manager = audioManager ?: return
        if (!audioRouteConfigured) return
        if (audioDeviceCallbackRegistered) {
            runCatching { manager.unregisterAudioDeviceCallback(audioDeviceCallback) }
            audioDeviceCallbackRegistered = false
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = previousBluetoothScoState
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = previousSpeakerphoneState
            }
            manager.mode = previousAudioMode
        }
        audioRouteConfigured = false
        onAudioRouteChanged?.invoke("System audio routing restored")
    }

    private fun audioDeviceLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        else -> "Android communication device"
    }

    @Synchronized
    fun startPlayback() {
        if (isReleased.get() || audioSuspended || isPlaybackRunning.get() || playbackThread?.isAlive == true) return
        configureAudioRouting()

        val minBuf = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, CHUNK_SIZE * 8)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SPEAKER_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        isPlaybackRunning.set(true)

        playbackThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                track.play()
                var idleDrainCount = 0
                while (isPlaybackRunning.get() && !isReleased.get()) {
                    val chunk = playbackQueue.poll(120, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        idleDrainCount = 0
                        if (isSpeaking.compareAndSet(false, true)) {
                            onSpeakingStarted?.invoke()
                        }
                        val rms = calculateNormalizedRms(chunk, chunk.size)
                        onAmplitudeChanged?.invoke(rms)
                        var offset = 0
                        while (offset < chunk.size && isPlaybackRunning.get()) {
                            val written = track.write(chunk, offset, chunk.size - offset)
                            if (written <= 0) break
                            offset += written
                        }
                        suppressMicUntilMs = System.currentTimeMillis() + ECHO_TAIL_MS
                    } else if (isSpeaking.get()) {
                        idleDrainCount++
                        if (idleDrainCount >= 3) {
                            isSpeaking.set(false)
                            suppressMicUntilMs = System.currentTimeMillis() + ECHO_TAIL_MS
                            onAmplitudeChanged?.invoke(0f)
                            onSpeakingStopped?.invoke()
                            idleDrainCount = 0
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Expected on shutdown
            } catch (e: Exception) {
                if (!isReleased.get()) {
                    onError?.invoke("Audio playback error: ${e.message}")
                }
            } finally {
                runCatching { track.stop() }
            }
        }, "MyraAudioPlayback").apply { start() }
    }

    fun queueAudio(pcmBytes: ByteArray) {
        if (isReleased.get() || audioSuspended || pcmBytes.isEmpty()) return
        if (!isPlaybackRunning.get()) {
            startPlayback()
        }
        val chunk = pcmBytes.copyOf()
        if (!playbackQueue.offer(chunk)) {
            playbackQueue.poll()
            playbackQueue.offer(chunk)
        }
    }

    fun interruptPlayback() {
        playbackQueue.clear()
        runCatching {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
        val wasSpeaking = isSpeaking.getAndSet(false)
        suppressMicUntilMs = System.currentTimeMillis() + 150L
        onAmplitudeChanged?.invoke(0f)
        if (wasSpeaking) {
            onSpeakingStopped?.invoke()
        }
    }

    @Synchronized
    fun stopPlayback() {
        isPlaybackRunning.set(false)
        playbackQueue.clear()
        val thread = playbackThread
        val track = audioTrack
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(RECORD_STOP_JOIN_MS) }
        }
        if (thread?.isAlive != true) playbackThread = null
        if (audioTrack === track) audioTrack = null
        runCatching { track?.release() }
        if (isSpeaking.getAndSet(false)) {
            onSpeakingStopped?.invoke()
        }
    }

    fun suspendAudio() {
        audioSuspended = true
        stopRecording()
        stopPlayback()
        resetAudioRouting()
    }

    fun resumeAudio() {
        if (!isReleased.get()) audioSuspended = false
    }

    fun release() {
        isReleased.set(true)
        stopRecording()
        stopPlayback()
        resetAudioRouting()
    }

    companion object {
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1024
        private const val MAX_QUEUED_AUDIO_CHUNKS = 64
        private const val RECORD_STOP_JOIN_MS = 500L
        private const val ECHO_TAIL_MS = 320L

        fun calculateNormalizedRms(pcmBytes: ByteArray, length: Int): Float {
            if (length < 2) return 0f
            var sumSquares = 0.0
            var sampleCount = 0
            var i = 0
            while (i + 1 < length) {
                val low = pcmBytes[i].toInt() and 0xFF
                val high = pcmBytes[i + 1].toInt()
                val sample = (high shl 8) or low
                val normalized = sample / 32768.0
                sumSquares += normalized * normalized
                sampleCount++
                i += 2
            }
            if (sampleCount == 0) return 0f
            val rms = sqrt(sumSquares / sampleCount).toFloat()
            return (rms * 3.5f).coerceIn(0f, 1f)
        }
    }
}
