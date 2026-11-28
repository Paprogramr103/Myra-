package com.myra.assistant.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myra.assistant.MyraApplication
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.ChatHistoryToolRegistry
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.ToolRegistry
import com.myra.assistant.ai.WhatsAppConfirmationPolicy
import com.myra.assistant.ai.MemoryToolRegistry
import com.myra.assistant.ai.EmotionalMemoryToolRegistry
import com.myra.assistant.data.db.DiagnosticLogEntity
import com.myra.assistant.data.backup.MyraBackupCodec
import com.myra.assistant.data.db.PairedDeviceEntity
import com.myra.assistant.data.db.UserMemoryEntity
import com.myra.assistant.data.db.ParkingSpotEntity
import com.myra.assistant.model.AppCommand
import com.myra.assistant.model.ChatMessage
import com.myra.assistant.model.ConnectionMode
import com.myra.assistant.model.ConnectionState
import com.myra.assistant.model.GeminiModelOption
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.NotificationSummary
import com.myra.assistant.model.OrbState
import com.myra.assistant.model.OtpAlert
import com.myra.assistant.model.PermissionSnapshot
import com.myra.assistant.model.PersonalityMode
import com.myra.assistant.model.SystemStatus
import com.myra.assistant.model.VoiceOption
import com.myra.assistant.network.MyraConnectState
import com.myra.assistant.network.PcCompanionState
import com.myra.assistant.security.OtpSanitizer
import com.myra.assistant.system.ToolExecutionResult
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraNotificationListenerService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.service.PcCompanionService
import com.myra.assistant.service.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MainUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionMessage: String = "Tap mic or Connect to start",
    val orbState: OrbState = OrbState.IDLE,
    val amplitude: Float = 0f,
    val isMuted: Boolean = true,
    val isActiveMode: Boolean = false,
    val statusText: String = "Tap karke bolo 💬",
    val incomingCallerName: String? = null,
    val isInCallMode: Boolean = false,
    val systemStatus: SystemStatus = SystemStatus(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val wakeWordRunning: Boolean = false,
    val overlayRunning: Boolean = false,
    val callMonitorRunning: Boolean = false,
    val pcCompanionRunning: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyraApplication
    private val settingsRepo = app.settingsRepository
    private val myraRepo = app.myraRepository
    private val telemetry = app.telemetryHelper
    private val toolsExecutor = app.toolsExecutor

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputTranscriptBuffer = StringBuilder()
    @Volatile private var latestUserUtterance: String = ""
    private val outputTranscriptBuffer = StringBuilder()
    private var hasSentGreetingThisSession = false
    private var callSpeechRecognizer: SpeechRecognizer? = null
    private data class PendingWhatsAppMessage(val target: String, val message: String)
    private var pendingWhatsAppMessage: PendingWhatsAppMessage? = null
    private var lastPromptedWhatsAppMessage: PendingWhatsAppMessage? = null
    private val commandExecutionLock = Any()
    private val recentCommandExecutions = mutableMapOf<String, Long>()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val settings: StateFlow<MyraSettings> = settingsRepo.settings
    val chatMessages: StateFlow<List<ChatMessage>> = myraRepo.chatMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memories: StateFlow<List<UserMemoryEntity>> = myraRepo.memories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val emotionalMemories = myraRepo.emotionalMemories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingMemories = myraRepo.pendingMemories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentActionAudit = myraRepo.recentActionAudit
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val diagnosticLogs: StateFlow<List<DiagnosticLogEntity>> = myraRepo.diagnosticLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val latestParkingSpot: StateFlow<ParkingSpotEntity?> = myraRepo.latestParkingSpot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pairedDevices: StateFlow<List<PairedDeviceEntity>> = myraRepo.pairedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val otpAlert: StateFlow<OtpAlert?> = MyraNotificationListenerService.ephemeralOtpAlert
    val recentNotifications: StateFlow<List<NotificationSummary>> = MyraNotificationListenerService.recentNotifications
    val wakeWordStatusNote: StateFlow<String> = WakeWordService.statusNote
    val pcCompanionState: StateFlow<PcCompanionState> = app.pcCompanionServer.state
    val myraConnectState: StateFlow<MyraConnectState> = app.myraConnectManager.state

    private val _navigationRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigationRequests = _navigationRequests.asSharedFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages = _toastMessages.asSharedFlow()

    private var audioEngine: AudioEngine? = null
    private var geminiLive: GeminiLiveClient? = null

    val isSecureStorageAvailable: Boolean get() = settingsRepo.isSecureStorageAvailable

    init {
        startTelemetryLoop()
        observeIncomingCompanionCommands()
        refreshPermissionsAndServices()
    }

    private fun hasValidCredential(s: MyraSettings): Boolean = when (s.connectionMode) {
        ConnectionMode.DIRECT_USER_KEY -> s.apiKey.trim().isNotEmpty()
        ConnectionMode.BACKEND_PROXY -> s.proxyUrl.trim().isNotEmpty() && s.proxyAuthToken.trim().isNotEmpty()
    }

    fun initializeLiveSessionIfReady() {
        if (geminiLive == null) {
            setupAudioAndGemini()
        }
        val currentSettings = settingsRepo.settings.value
        if (!hasValidCredential(currentSettings)) {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.DISCONNECTED,
                connectionMessage = "API Key or Proxy URL + Bearer Token required in Settings",
                statusText = "Settings me API Key ya Proxy URL daalein",
            )
            return
        }
        // Activity recreation / returning from Settings must not replace a
        // healthy socket or interrupt an automatic retry already in progress.
        if (geminiLive?.hasActiveConnectionAttempt() != true &&
            _uiState.value.connectionState != ConnectionState.CONNECTING &&
            _uiState.value.connectionState != ConnectionState.RECONNECTING
        ) {
            connectLiveSession()
        }
    }

    private fun setupAudioAndGemini() {
        val engine = AudioEngine(app).apply {
            onAudioRouteChanged = { route ->
                viewModelScope.launch { myraRepo.log("INFO", "AudioRoute", route) }
            }
            onMicAudioChunk = { pcm ->
                if (!_uiState.value.isInCallMode) {
                    geminiLive?.sendAudio(pcm)
                }
            }
            onMicAudioStreamEnded = {
                geminiLive?.sendAudioStreamEnd()
            }
            onAmplitudeChanged = { rms ->
                _uiState.value = _uiState.value.copy(amplitude = rms)
            }
            onSpeakingStarted = {
                _uiState.value = _uiState.value.copy(
                    orbState = OrbState.SPEAKING,
                    statusText = "Bol rahi hoon...",
                    isActiveMode = true,
                )
            }
            onSpeakingStopped = {
                val listening = !_uiState.value.isMuted && geminiLive?.isConnected() == true
                _uiState.value = _uiState.value.copy(
                    orbState = if (listening) OrbState.LISTENING else OrbState.IDLE,
                    statusText = if (listening) "Sun rahi hoon..." else "Tap karke bolo 💬",
                    isActiveMode = listening,
                )
            }
            onError = { err ->
                viewModelScope.launch {
                    myraRepo.log("ERROR", "AudioEngine", err)
                    _toastMessages.tryEmit(err)
                }
            }
        }
        audioEngine = engine

        val client = GeminiLiveClient(
            settingsProvider = { settingsRepo.settings.value },
            conversationContextProvider = {
                val savedMemories = memories.value.filter { it.userConfirmed }.take(40).asReversed()
                    .joinToString("\n") {
                        if (it.category == "emotion") {
                            val eventDate = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(it.createdAt))
                            "Emotional event on $eventDate (historical, not current mood): ${it.value}; user's words: ${it.sourceText}"
                        } else {
                            "${it.category}: ${it.value}"
                        }
                    }
                val recentConversation = chatMessages.value.takeLast(12).joinToString("\n") { message ->
                    val speaker = if (message.isUser) "User" else "MYRA"
                    "$speaker: ${message.text.take(500)}"
                }
                buildString {
                    if (savedMemories.isNotBlank()) append("Persistent personal memory (stable facts and dated emotional events):\n").append(savedMemories).append("\n")
                    if (recentConversation.isNotBlank()) append("Recent conversation (not permanent memory):\n").append(recentConversation)
                }.take(6000)
            },
        ).apply {
            onConnectionStateChanged = { state, msg ->
                _uiState.value = _uiState.value.copy(
                    connectionState = state,
                    connectionMessage = msg ?: state.name,
                    orbState = when (state) {
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> OrbState.THINKING
                        ConnectionState.CONNECTED -> if (_uiState.value.isMuted) OrbState.IDLE else OrbState.LISTENING
                        else -> OrbState.IDLE
                    },
                )
            }
            onSetupComplete = {
                viewModelScope.launch(Dispatchers.Main) {
                    engine.startPlayback()
                    val hasMic = telemetry.readPermissionSnapshot().microphone
                    if (hasMic && !_uiState.value.isMuted) {
                        engine.startRecording()
                        engine.setMuted(false)
                    } else {
                        // A muted session must not keep AudioRecord open.
                        engine.setMuted(true)
                        engine.stopRecording()
                    }
                    if (!hasSentGreetingThisSession) {
                        hasSentGreetingThisSession = true
                        delay(600L)
                        sendSessionGreeting()
                    }
                }
            }
            onAudioReceived = { pcm24k ->
                engine.queueAudio(pcm24k)
            }
            onInputTranscript = { chunk ->
                synchronized(inputTranscriptBuffer) {
                    if (inputTranscriptBuffer.isEmpty()) latestUserUtterance = ""
                    inputTranscriptBuffer.append(chunk)
                }
            }
            onOutputTranscript = { chunk ->
                synchronized(outputTranscriptBuffer) {
                    outputTranscriptBuffer.append(chunk)
                }
            }
            onTurnComplete = {
                flushTranscriptBuffers()
            }
            onInterrupted = {
                engine.interruptPlayback()
            }
            onToolCall = { callId, name, args ->
                handleGeminiToolCall(callId, name, args)
            }
            onLog = { level, message ->
                viewModelScope.launch { myraRepo.log(level, "GeminiLive", message) }
            }
        }
        geminiLive = client
    }

    fun connectLiveSession() {
        // Idempotent guard covers both a healthy socket and an unfinished setup
        // handshake / scheduled automatic retry.
        if (geminiLive?.isConnected() == true) {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.CONNECTED,
                connectionMessage = "Connected (Live Voice)",
            )
            return
        }
        if (_uiState.value.connectionState == ConnectionState.CONNECTING ||
            _uiState.value.connectionState == ConnectionState.RECONNECTING ||
            geminiLive?.hasActiveConnectionAttempt() == true
        ) return

        if (geminiLive == null) setupAudioAndGemini()
        audioEngine?.resumeAudio()
        hasSentGreetingThisSession = false
        _uiState.value = _uiState.value.copy(
            orbState = OrbState.THINKING,
            statusText = "Connecting to MYRA...",
        )
        geminiLive?.connect()
    }

    fun disconnectLiveSession() {
        geminiLive?.disconnect()
        audioEngine?.setMuted(true)
        audioEngine?.suspendAudio()
        _uiState.value = _uiState.value.copy(
            isMuted = true,
            isActiveMode = false,
            orbState = OrbState.IDLE,
            statusText = "Standby — Tap mic to connect",
        )
    }

    fun reconnectWithNewSettings() {
        hasSentGreetingThisSession = false
        geminiLive?.disconnect()
        connectLiveSession()
    }

    fun toggleMic() {
        if (_uiState.value.isInCallMode) {
            _toastMessages.tryEmit("MYRA microphone stays paused while a phone call is active.")
            return
        }
        val perms = telemetry.readPermissionSnapshot()
        if (!perms.microphone) {
            _toastMessages.tryEmit("Microphone permission is required for voice chat.")
            return
        }

        if (geminiLive == null) setupAudioAndGemini()

        if (geminiLive?.isConnected() != true) {
            _uiState.value = _uiState.value.copy(isMuted = false, isActiveMode = true)
            connectLiveSession()
            return
        }

        val nextMuted = !_uiState.value.isMuted
        if (!nextMuted) {
            audioEngine?.resumeAudio()
            audioEngine?.configureAudioRouting(force = true)
            audioEngine?.startRecording()
        } else {
            audioEngine?.stopRecording()
        }
        audioEngine?.setMuted(nextMuted)
        _uiState.value = _uiState.value.copy(
            isMuted = nextMuted,
            isActiveMode = !nextMuted,
            orbState = if (nextMuted) OrbState.IDLE else OrbState.LISTENING,
            statusText = if (nextMuted) "Mic muted — Tap to speak" else "Sun rahi hoon...",
        )
    }

    fun interruptMyraSpeaking() {
        audioEngine?.interruptPlayback()
        geminiLive?.sendInterrupt()
        _uiState.value = _uiState.value.copy(
            orbState = if (_uiState.value.isMuted) OrbState.IDLE else OrbState.LISTENING,
            statusText = "Interrupted — Bolo",
        )
        _toastMessages.tryEmit("Stopped speaking")
    }

    fun onAppPaused() {
        audioEngine?.setMuted(true)
        audioEngine?.suspendAudio()
        if (
            settingsRepo.settings.value.wakeWordEnabled &&
            !_uiState.value.isInCallMode &&
            WakeWordService.isRunning
        ) {
            runCatching {
                app.startService(Intent(app, WakeWordService::class.java).setAction(WakeWordService.ACTION_RESUME))
            }
        }
    }

    fun onAppResumed() {
        refreshPermissionsAndServices()
        val currentPermissions = telemetry.readPermissionSnapshot()
        if (settingsRepo.settings.value.callMonitorEnabled) {
            val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || currentPermissions.notifications
            if (!currentPermissions.phoneState || !notificationsAllowed) {
                app.stopService(Intent(app, CallMonitorService::class.java))
                settingsRepo.setCallMonitorEnabled(false)
                _toastMessages.tryEmit("Call monitoring was stopped because phone-state or notification permission is missing.")
            }
        }
        if (settingsRepo.settings.value.wakeWordEnabled) {
            val permissions = currentPermissions
            val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissions.notifications
            if (!permissions.microphone || !notificationsAllowed) {
                app.stopService(Intent(app, WakeWordService::class.java))
                settingsRepo.setWakeWordEnabled(false)
                _toastMessages.tryEmit("Hey MYRA was stopped because microphone or notification permission is missing.")
            } else {
                val wakeIntent = Intent(app, WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE)
                runCatching {
                    if (WakeWordService.isRunning) app.startService(wakeIntent) else app.startForegroundService(wakeIntent)
                }
            }
        }
        if (!_uiState.value.isInCallMode && geminiLive?.isConnected() == true) {
            audioEngine?.resumeAudio()
            audioEngine?.configureAudioRouting(force = true)
            audioEngine?.startPlayback()
            if (!_uiState.value.isMuted) {
                audioEngine?.startRecording()
                audioEngine?.setMuted(false)
            }
        }
    }

    fun activateFromWakeWord() {
        activateAssistantFromTrigger(
            status = "Hey MYRA detected — listening...",
            permissionMessage = "Wake phrase detected, but microphone permission is required.",
        )
    }

    fun activateFromOverlay() {
        activateAssistantFromTrigger(
            status = "MYRA orb tapped — listening...",
            permissionMessage = "Microphone permission is required to use the floating orb.",
        )
    }

    private fun activateAssistantFromTrigger(status: String, permissionMessage: String) {
        if (_uiState.value.isInCallMode) return
        if (!telemetry.readPermissionSnapshot().microphone) {
            _toastMessages.tryEmit(permissionMessage)
            return
        }
        _uiState.value = _uiState.value.copy(
            isMuted = false,
            isActiveMode = true,
            orbState = OrbState.THINKING,
            statusText = status,
        )
        if (geminiLive?.isConnected() == true) {
            audioEngine?.resumeAudio()
            audioEngine?.startPlayback()
            audioEngine?.startRecording()
            audioEngine?.setMuted(false)
        } else {
            initializeLiveSessionIfReady()
        }
    }

    fun sendUserText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        if (OtpSanitizer.containsOtp(text)) {
            _toastMessages.tryEmit("Blocked: Never enter or send banking OTPs into AI chat.")
            return
        }

        latestUserUtterance = text
        viewModelScope.launch {
            myraRepo.addChatMessage(text, isUser = true, source = "text")

            if (handlePendingWhatsAppConfirmation(text)) return@launch

            // 1. Fast local command router
            val localCmd = CommandParser.parse(text)
            if (localCmd != null) {
                val res = executeCommandAndNotify(localCmd, alsoTellGemini = true)
                myraRepo.addChatMessage(res.message, isUser = false, source = "local_tool")
                return@launch
            }

            // 2. Otherwise send to Gemini Live WebSocket
            if (geminiLive?.isConnected() == true) {
                _uiState.value = _uiState.value.copy(orbState = OrbState.THINKING, statusText = "Soch rahi hoon...")
                geminiLive?.sendText(text)
            } else {
                val msg = if (!hasValidCredential(settingsRepo.settings.value)) {
                    "Gemini API Key ya Backend Proxy URL + Bearer Token set nahi hai. Settings me configure karein."
                } else {
                    "Gemini Live abhi connected nahi hai. Connect tap karein ya network check karein."
                }
                myraRepo.addChatMessage(msg, isUser = false, source = "system")
            }
        }
    }

    fun runQuickCommand(command: AppCommand) {
        viewModelScope.launch {
            val result = executeCommandAndNotify(command, alsoTellGemini = false)
            myraRepo.addChatMessage(result.message, isUser = false, source = "quick_action")
            _toastMessages.tryEmit(result.message)
        }
    }

    fun analyzeCapturedImage(jpegBytes: ByteArray) {
        if (_uiState.value.isInCallMode) {
            _toastMessages.tryEmit("Camera Vision is paused while a phone call is active.")
            return
        }
        if (jpegBytes.isEmpty() || jpegBytes.size > 4 * 1024 * 1024) {
            _toastMessages.tryEmit("Image is empty or too large. Capture again.")
            return
        }
        if (geminiLive?.isConnected() != true) {
            _toastMessages.tryEmit("Connect MYRA to Gemini Live first, then capture the image again.")
            initializeLiveSessionIfReady()
            return
        }

        val prompt = "इस कैमरा फोटो का विश्लेषण करो। केवल स्पष्ट रूप से दिखाई देने वाली वस्तुएँ, दृश्य और सामान्य लिखावट बताओ; अस्पष्ट चीज़ों का अनुमान मत लगाओ। यदि OTP, PIN, CVV, बैंकिंग या अन्य गोपनीय कोड दिखाई दे, तो उसका अंक या पाठ कभी न दोहराओ—केवल बताओ कि संवेदनशील जानकारी दिख रही है।"
        val sent = geminiLive?.sendImageWithPrompt(jpegBytes, prompt) == true
        if (sent) {
            viewModelScope.launch {
                myraRepo.addChatMessage("Camera image sent for visual analysis.", isUser = true, source = "camera")
                myraRepo.log("INFO", "CameraVision", "Sent a compressed JPEG frame to Gemini Live (${jpegBytes.size} bytes).")
            }
        } else {
            _toastMessages.tryEmit("Could not send the image. Check Gemini Live connection and try again.")
        }
    }

    fun onBarcodeScanned(rawValue: String) {
        viewModelScope.launch {
            val safe = OtpSanitizer.redactIfSensitive(rawValue)
            myraRepo.addChatMessage("Scanned Barcode/QR: $safe", isUser = true, source = "scanner")
            myraRepo.log("INFO", "Scanner", "Barcode scanned (${safe.length} chars)")
            if (geminiLive?.isConnected() == true) {
                geminiLive?.sendText("User scanned this QR/Barcode content: $safe. Summarize or help act on it concisely.")
            }
        }
    }

    private fun flushTranscriptBuffers() {
        val userText: String
        val myraText: String
        synchronized(inputTranscriptBuffer) {
            userText = inputTranscriptBuffer.toString().trim()
            inputTranscriptBuffer.setLength(0)
        }
        synchronized(outputTranscriptBuffer) {
            myraText = outputTranscriptBuffer.toString().trim()
            outputTranscriptBuffer.setLength(0)
        }

        viewModelScope.launch {
            if (userText.isNotEmpty()) {
                latestUserUtterance = userText
                myraRepo.addChatMessage(userText, isUser = true, source = "voice")
                if (!handlePendingWhatsAppConfirmation(userText)) {
                    val parsed = CommandParser.parse(userText)
                    if (parsed != null) {
                        val result = executeCommandAndNotify(parsed, alsoTellGemini = false)
                        myraRepo.addChatMessage(result.message, isUser = false, source = "local_tool")
                    }
                }
            }
            if (myraText.isNotEmpty()) {
                myraRepo.addChatMessage(myraText, isUser = false, source = "voice")
            }
        }
    }

    private fun handleGeminiToolCall(callId: String, name: String, args: JSONObject) {
        if (name == "search_myra_history") {
            val request = ChatHistoryToolRegistry.validate(args)
            if (request == null) {
                geminiLive?.sendToolResponse(
                    callId,
                    name,
                    JSONObject().put("success", false).put("error", "Invalid history search request."),
                )
                return
            }
            viewModelScope.launch {
                val matches = myraRepo.searchChatHistory(request.queries, request.limit, request.afterTimestamp, request.beforeTimestamp)
                val result = JSONObject().apply {
                    put("success", true)
                    put("count", matches.size)
                    put("queries", org.json.JSONArray(request.queries))
                    put("matches", org.json.JSONArray().apply {
                        matches.forEach { message ->
                            put(JSONObject().apply {
                                put("speaker", if (message.isUser) "User" else "MYRA")
                                put("text", message.text.take(700))
                                put("timestamp", message.timestamp)
                                put("source", message.source)
                            })
                        }
                    })
                }
                geminiLive?.sendToolResponse(callId, name, result)
            }
            return
        }

        if (name == "save_user_memory" || name == "save_emotional_memory") {
            val evidenceText = buildString {
                append(latestUserUtterance)
                val buffered = synchronized(inputTranscriptBuffer) { inputTranscriptBuffer.toString().trim() }
                if (buffered.isNotBlank() && !toString().contains(buffered)) {
                    if (isNotEmpty()) append(" ")
                    append(buffered)
                }
            }.takeLast(1000)
            val candidate = if (name == "save_emotional_memory") {
                EmotionalMemoryToolRegistry.validate(args, evidenceText)
            } else {
                MemoryToolRegistry.validate(args, evidenceText)
            }
            viewModelScope.launch {
                if (candidate == null) {
                    geminiLive?.sendToolResponse(
                        callId,
                        name,
                        JSONObject().apply {
                            put("success", false)
                            put("error", "Memory was not saved: evidence was not an exact recent user statement, confidence was too low, or the content was unsafe.")
                        },
                    )
                    myraRepo.log("WARN", "Memory", "Rejected Gemini memory proposal: provenance or safety validation failed.")
                    return@launch
                }
                myraRepo.proposeMemory(candidate)
                geminiLive?.sendToolResponse(
                    callId,
                    name,
                    JSONObject().apply {
                        put("success", true)
                        put("pendingApproval", true)
                        put("message", "Memory proposal is waiting for the user's approval in MYRA Settings. Do not use it as a saved fact yet.")
                    },
                )
                myraRepo.log("INFO", "Memory", "Queued ${candidate.category} memory proposal for user approval; confidence=${candidate.confidence}.")
            }
            return
        }

        val validationError = ToolRegistry.validationError(name, args)
        val cmd = if (validationError == null) ToolRegistry.toAppCommand(name, args) else null
        if (cmd == null) {
            geminiLive?.sendToolResponse(
                callId,
                name,
                JSONObject().apply {
                    put("success", false)
                    put("error", validationError ?: "Unknown tool: $name")
                },
            )
            return
        }

        viewModelScope.launch {
            try {
                val result = executeCommandAndNotify(
                    cmd,
                    alsoTellGemini = false,
                    promptWhatsApp = false,
                )
                geminiLive?.sendToolResponse(
                    callId,
                    name,
                    JSONObject().apply {
                        put("success", result.success)
                        put("message", result.message)
                    },
                )
                if (cmd.type == AppCommand.WHATSAPP_MSG && result.success) {
                    // Send the readback only after the tool response clears Gemini's pending call.
                    sendWhatsAppReadbackPrompt(cmd)
                }
            } catch (error: Exception) {
                val safeMessage = error.message?.take(240) ?: "Tool execution failed."
                myraRepo.log("ERROR", "Tool:${cmd.type}", safeMessage)
                geminiLive?.sendToolResponse(
                    callId,
                    name,
                    JSONObject().apply {
                        put("success", false)
                        put("error", safeMessage)
                    },
                )
            }
        }
    }

    private suspend fun executeCommandAndNotify(
        command: AppCommand,
        alsoTellGemini: Boolean,
        promptWhatsApp: Boolean = true,
    ): ToolExecutionResult {
        val commandKey = command.type + ":" + command.params.toSortedMap().entries.joinToString("|") {
            "${it.key}=${it.value.trim().lowercase()}"
        }
        val now = System.currentTimeMillis()
        val duplicate = synchronized(commandExecutionLock) {
            recentCommandExecutions.entries.removeAll { now - it.value > 2500L }
            val previous = recentCommandExecutions[commandKey]
            if (previous != null && now - previous <= 2500L) true else {
                recentCommandExecutions[commandKey] = now
                false
            }
        }
        if (duplicate) return ToolExecutionResult(true, "This command was already processed. I did not run it a second time.")

        var result = if (command.type == AppCommand.WHATSAPP_MSG) {
            stageWhatsAppMessage(command)
        } else if (
            command.type == AppCommand.SEND_TO_PC &&
            app.pcCompanionServer.state.value.authenticatedClients.isEmpty()
        ) {
            ToolExecutionResult(false, "No authenticated PC Companion is connected. Nothing was sent.")
        } else {
            try {
                toolsExecutor.execute(command)
            } catch (error: Exception) {
                ToolExecutionResult(false, error.message?.take(240) ?: "The requested Android action failed.")
            }
        }

        result.forwardToPcPayload?.let { payload ->
            val delivered = runCatching {
                app.pcCompanionServer.broadcastJson(
                    "myra_message",
                    JSONObject().apply { put("text", payload) },
                )
            }.getOrDefault(0)
            if (delivered == 0) {
                result = result.copy(
                    success = false,
                    message = "PC Companion disconnected before delivery. Nothing was confirmed as sent.",
                    forwardToPcPayload = null,
                )
            }
        }

        if (!result.success) synchronized(commandExecutionLock) { recentCommandExecutions.remove(commandKey) }
        myraRepo.log(
            if (result.success) "INFO" else "WARN",
            "Tool:${command.type}",
            result.message,
        )
        myraRepo.recordActionOutcome(
            commandType = command.type,
            parameterNames = command.params.keys.sorted().joinToString(","),
            success = result.success,
            resultSummary = result.message,
        )
        result.torchStateChanged?.let { torchOn ->
            _uiState.value = _uiState.value.copy(
                systemStatus = _uiState.value.systemStatus.copy(isTorchOn = torchOn),
            )
        }
        result.navigateRoute?.let { route ->
            _navigationRequests.tryEmit(route)
        }
        if (command.type == AppCommand.WHATSAPP_MSG && result.success) {
            if (promptWhatsApp) sendWhatsAppReadbackPrompt(command)
        } else if (alsoTellGemini && geminiLive?.isConnected() == true) {
            geminiLive?.sendText("Tool '${command.type}' result: ${result.message}. Acknowledge briefly to the user.")
        }
        return result
    }

    private fun stageWhatsAppMessage(command: AppCommand): ToolExecutionResult {
        val target = command.params["target"].orEmpty().trim()
        val message = command.params["message"].orEmpty().trim()
        if (target.isBlank() || message.isBlank()) return ToolExecutionResult(false, "किसे और क्या WhatsApp मैसेज भेजना है, यह साफ़ बताइए।")
        if (geminiLive?.isConnected() != true) return ToolExecutionResult(false, "WhatsApp मैसेज पढ़कर पुष्टि लेने के लिए MYRA को Gemini Live से कनेक्ट करें।")
        val candidate = PendingWhatsAppMessage(target, message)
        if (pendingWhatsAppMessage != candidate) {
            pendingWhatsAppMessage = candidate
            lastPromptedWhatsAppMessage = null
        }
        return ToolExecutionResult(true, "WhatsApp message prepared. Waiting for the user's explicit voice confirmation; nothing has been sent.")
    }

    private fun sendWhatsAppReadbackPrompt(command: AppCommand) {
        val target = command.params["target"].orEmpty().trim()
        val message = command.params["message"].orEmpty().trim()
        if (target.isBlank() || message.isBlank() || geminiLive?.isConnected() != true) return
        val candidate = PendingWhatsAppMessage(target, message)
        if (pendingWhatsAppMessage != candidate || lastPromptedWhatsAppMessage == candidate) return
        val queued = geminiLive?.sendText("Read this WhatsApp message aloud exactly as written. Say in Hindi: 'मैं $target को WhatsApp पर यह मैसेज भेजने जा रही हूँ: $message। क्या भेज दूँ? हाँ या नहीं बोलिए।' Do not call any tool or open WhatsApp yet; wait for explicit confirmation.") == true
        if (queued) lastPromptedWhatsAppMessage = candidate
    }

    private suspend fun handlePendingWhatsAppConfirmation(spokenText: String): Boolean {
        val pending = pendingWhatsAppMessage ?: return false
        // Do not mistake the original request transcript for a reply to a prompt
        // that has not actually been queued to Gemini Live yet.
        if (lastPromptedWhatsAppMessage != pending) return false
        if (WhatsAppConfirmationPolicy.isNegative(spokenText)) {
            pendingWhatsAppMessage = null
            lastPromptedWhatsAppMessage = null
            val reply = "ठीक है, WhatsApp मैसेज रद्द कर दिया। कुछ भी नहीं भेजा गया।"
            myraRepo.addChatMessage(reply, isUser = false, source = "local_tool")
            geminiLive?.sendText("Tell the user in Hindi: $reply")
            return true
        }
        if (!WhatsAppConfirmationPolicy.isAffirmative(spokenText)) {
            // The readback prompt is sent once when the message is staged.
            // Do not ask again for every partial/ambiguous transcript; keep the
            // pending message unchanged and wait for a clear yes/no response.
            myraRepo.addChatMessage(
                "अभी स्पष्ट पुष्टि नहीं मिली। मैसेज नहीं भेजा गया है; तैयार होने पर “हाँ भेज दो” या “नहीं” कहें।",
                isUser = false,
                source = "local_tool",
            )
            return true
        }
        pendingWhatsAppMessage = null
        lastPromptedWhatsAppMessage = null
        val result = runCatching { toolsExecutor.execute(AppCommand(AppCommand.WHATSAPP_MSG, mapOf("target" to pending.target, "message" to pending.message))) }
            .getOrElse { ToolExecutionResult(false, it.message?.take(240) ?: "WhatsApp could not be opened.") }
        val reply = if (result.success) "आपकी पुष्टि मिल गई। ${result.message}" else "आपकी पुष्टि मिली, लेकिन WhatsApp कार्रवाई पूरी नहीं हो पाई: ${result.message}"
        myraRepo.log(if (result.success) "INFO" else "WARN", "Tool:WHATSAPP_MSG", reply)
        myraRepo.recordActionOutcome(
            commandType = AppCommand.WHATSAPP_MSG,
            parameterNames = "target,message",
            success = result.success,
            resultSummary = reply,
        )
        myraRepo.addChatMessage(reply, isUser = false, source = "local_tool")
        geminiLive?.sendText("Say this exact result briefly in Hindi: $reply. Do not claim the message was sent.")
        return true
    }

    private fun sendSessionGreeting() {        val s = settingsRepo.settings.value
        val greeting = when (s.personality) {
            PersonalityMode.GF -> "Hey ${s.userName}! Main aa gayi hoon. Kya help chahiye tumhe?"
            PersonalityMode.PROFESSIONAL -> "Good day ${s.userName}. MYRA is online and ready to assist you."
            PersonalityMode.ASSISTANT -> "Hello ${s.userName}! Main MYRA hoon. Kaise help karun aapki?"
        }
        geminiLive?.sendText("Greet the user right now naturally by saying: $greeting")
    }

    // --- Incoming Call Flow ---
    fun handleIncomingCall(callerName: String) {
        audioEngine?.setMuted(true)
        audioEngine?.suspendAudio()
        _uiState.value = _uiState.value.copy(
            incomingCallerName = callerName,
            isInCallMode = true,
            isActiveMode = true,
            orbState = OrbState.SPEAKING,
            statusText = "Incoming Call: $callerName",
        )
        viewModelScope.launch {
            myraRepo.log("INFO", "CallMonitor", "Incoming call from $callerName")
        }

        if (geminiLive?.isConnected() == true) {
            geminiLive?.sendText("Sir, $callerName ka call aa raha hai. Uthau ya reject karu?")
        }

        mainHandler.postDelayed({
            if (_uiState.value.isInCallMode) {
                startCallDecisionSpeechRecognizer()
            }
        }, 4500L)
    }

    private fun startCallDecisionSpeechRecognizer() {
        if (!_uiState.value.isInCallMode) return
        if (!telemetry.readPermissionSnapshot().microphone) {
            _toastMessages.tryEmit("Microphone permission is required to answer by voice.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _toastMessages.tryEmit("Speech recognition is unavailable; use the on-screen call buttons.")
            return
        }

        audioEngine?.interruptPlayback()
        runCatching { callSpeechRecognizer?.destroy() }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
        callSpeechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _uiState.value = _uiState.value.copy(statusText = "Bolo: Uthau ya Reject?")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase().orEmpty()
                when {
                    spoken.contains("uthao") || spoken.contains("haan") || spoken.contains("accept") || spoken.contains("pick") -> {
                        acceptIncomingCall()
                    }
                    spoken.contains("reject") || spoken.contains("nahi") || spoken.contains("mat") || spoken.contains("cut") -> {
                        rejectIncomingCall()
                    }
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        runCatching { recognizer.startListening(intent) }
    }

    fun acceptIncomingCall() {
        val res = toolsExecutor.acceptRingingCall()
        _toastMessages.tryEmit(res.message)
        if (geminiLive?.isConnected() == true) {
            geminiLive?.sendText(if (res.success) "Call accepted. Confirm briefly." else res.message)
        }
        if (res.success) {
            _uiState.value = _uiState.value.copy(
                statusText = "Call in progress — MYRA microphone paused until the call ends.",
                orbState = OrbState.IDLE,
            )
        }
    }

    fun rejectIncomingCall() {
        val res = toolsExecutor.rejectCall()
        _toastMessages.tryEmit(res.message)
        if (geminiLive?.isConnected() == true) {
            geminiLive?.sendText(if (res.success) "Call rejected. Confirm briefly." else res.message)
        }
        if (res.success) {
            _uiState.value = _uiState.value.copy(
                statusText = "Call rejected — waiting for the phone to return to idle.",
                orbState = OrbState.IDLE,
            )
        }
    }

    fun onCallEnded(resumeMicrophone: Boolean = true) {
        clearCallMode(resumeMicrophone)
    }

    private fun clearCallMode(resumeMicrophone: Boolean = true) {
        runCatching { callSpeechRecognizer?.destroy() }
        callSpeechRecognizer = null
        val shouldResumeMic = resumeMicrophone && !_uiState.value.isMuted && geminiLive?.isConnected() == true
        _uiState.value = _uiState.value.copy(
            incomingCallerName = null,
            isInCallMode = false,
            isActiveMode = ! _uiState.value.isMuted,
            orbState = if (_uiState.value.isMuted) OrbState.IDLE else OrbState.LISTENING,
            statusText = if (_uiState.value.isMuted) "Tap karke bolo 💬" else "Sun rahi hoon...",
        )
        if (resumeMicrophone && !_uiState.value.isInCallMode) {
            audioEngine?.resumeAudio()
            audioEngine?.startPlayback()
            if (shouldResumeMic) {
                audioEngine?.startRecording()
                audioEngine?.setMuted(false)
            }
        } else if (settingsRepo.settings.value.wakeWordEnabled && WakeWordService.isRunning) {
            val wakeIntent = Intent(app, WakeWordService::class.java).setAction(WakeWordService.ACTION_RESUME)
            runCatching { app.startService(wakeIntent) }
        }
    }

    // --- Settings, Services & Companions ---
    fun saveSettings(
        connectionMode: ConnectionMode,
        apiKey: String,
        proxyUrl: String,
        proxyAuthToken: String,
        userName: String,
        model: GeminiModelOption,
        voice: VoiceOption,
        personality: PersonalityMode,
        pcPort: Int,
    ) {
        val previous = settingsRepo.settings.value
        val wasLiveActive = geminiLive?.isConnected() == true ||
            _uiState.value.connectionState == ConnectionState.CONNECTING ||
            _uiState.value.connectionState == ConnectionState.RECONNECTING
        val liveConfigurationChanged =
            previous.connectionMode != connectionMode ||
            previous.apiKey != apiKey ||
            previous.proxyUrl != proxyUrl ||
            previous.proxyAuthToken != proxyAuthToken ||
            previous.model != model ||
            previous.voice != voice ||
            previous.personality != personality ||
            previous.userName != userName
        val credentialsProvided = apiKey.isNotBlank() || proxyAuthToken.isNotBlank()
        settingsRepo.saveSettings(
            connectionMode = connectionMode,
            apiKey = apiKey,
            proxyUrl = proxyUrl,
            proxyAuthToken = proxyAuthToken,
            userName = userName,
            model = model,
            voice = voice,
            personality = personality,
            pcCompanionPort = pcPort,
        )

        if (!settingsRepo.isSecureStorageAvailable && credentialsProvided) {
            _toastMessages.tryEmit(
                "Secure credential storage is unavailable. Other settings were saved, but API Key/Proxy Token were NOT saved. Enable a secure Android lock screen and retry."
            )
            return
        }

        viewModelScope.launch {
            myraRepo.log(
                "INFO",
                "Settings",
                "Saved mode=${connectionMode.key}, model=${model.modelId}, voice=${voice.voiceId}, personality=${personality.key}",
            )
        }
        when {
            liveConfigurationChanged && wasLiveActive -> {
                _toastMessages.tryEmit("Live settings changed — reconnecting MYRA once...")
                reconnectWithNewSettings()
            }
            liveConfigurationChanged -> {
                _toastMessages.tryEmit("Settings saved. Connect MYRA when you're ready.")
            }
            else -> {
                _toastMessages.tryEmit("Settings saved — current Gemini connection kept.")
            }
        }
    }

    fun clearStoredCredentials() {
        settingsRepo.clearStoredCredentials()
        geminiLive?.disconnect()
        viewModelScope.launch {
            myraRepo.log("INFO", "Security", "User cleared locally stored API key and proxy token.")
        }
        _toastMessages.tryEmit("Stored API Key & Proxy Token cleared.")
    }

    fun addPrimeContact(name: String, number: String) {
        settingsRepo.addPrimeContact(name, number)
        _toastMessages.tryEmit("Prime contact saved")
    }

    fun removePrimeContact(index: Int) {
        settingsRepo.removePrimeContact(index)
    }

    fun toggleWakeWordService(enable: Boolean) {
        val perms = telemetry.readPermissionSnapshot()
        if (enable && !perms.microphone) {
            _toastMessages.tryEmit("Grant Microphone permission first.")
            return
        }
        val intent = Intent(app, WakeWordService::class.java)
        if (enable) {
            intent.action = WakeWordService.ACTION_PAUSE
            runCatching { app.startForegroundService(intent) }
                .onSuccess { settingsRepo.setWakeWordEnabled(true) }
                .onFailure {
                    settingsRepo.setWakeWordEnabled(false)
                    _toastMessages.tryEmit("Could not start wake-word service: ${it.message ?: "Android blocked microphone access."}")
                }
        } else {
            app.stopService(intent)
            settingsRepo.setWakeWordEnabled(false)
        }
        refreshPermissionsAndServices()
    }

    fun toggleOverlayService(enable: Boolean) {
        val perms = telemetry.readPermissionSnapshot()
        if (enable && !perms.overlay) {
            telemetry.openOverlaySettings()
            _toastMessages.tryEmit("Enable 'Display over other apps' first.")
            return
        }
        val intent = Intent(app, MyraOverlayService::class.java)
        if (enable) {
            intent.action = MyraOverlayService.ACTION_SHOW_OVERLAY
            runCatching { app.startForegroundService(intent) }
                .onSuccess { settingsRepo.setOverlayEnabled(true) }
                .onFailure {
                    settingsRepo.setOverlayEnabled(false)
                    _toastMessages.tryEmit("Could not start floating orb: ${it.message ?: "Android blocked the foreground service."}")
                }
        } else {
            app.stopService(intent)
            settingsRepo.setOverlayEnabled(false)
        }
        refreshPermissionsAndServices()
    }

    fun toggleCallMonitorService(enable: Boolean) {
        val perms = telemetry.readPermissionSnapshot()
        if (enable && !perms.phoneState) {
            _toastMessages.tryEmit("Grant Phone State permission first.")
            return
        }
        val intent = Intent(app, CallMonitorService::class.java)
        if (enable) {
            runCatching { app.startForegroundService(intent) }
                .onSuccess { settingsRepo.setCallMonitorEnabled(true) }
                .onFailure {
                    settingsRepo.setCallMonitorEnabled(false)
                    _toastMessages.tryEmit("Could not start call monitor: ${it.message ?: "Android blocked the service."}")
                }
        } else {
            app.stopService(intent)
            settingsRepo.setCallMonitorEnabled(false)
        }
        refreshPermissionsAndServices()
    }

    fun togglePcCompanion(enable: Boolean) {
        val intent = Intent(app, PcCompanionService::class.java)
        if (enable) {
            runCatching { app.startForegroundService(intent) }
                .onFailure { _toastMessages.tryEmit("Could not start PC Companion: ${it.message ?: "Android blocked the service."}") }
        } else {
            app.stopService(intent)
        }
        mainHandler.postDelayed({ refreshPermissionsAndServices() }, 300L)
    }

    fun regeneratePcPin() {
        app.pcCompanionServer.regeneratePin()
    }

    fun toggleMyraConnectHosting(enable: Boolean) {
        val serviceIntent = Intent(app, com.myra.assistant.service.MyraConnectService::class.java)
        if (enable) {
            serviceIntent.action = com.myra.assistant.service.MyraConnectService.ACTION_START
            runCatching { app.startForegroundService(serviceIntent) }
                .onFailure { _toastMessages.tryEmit("Could not start MYRA Connect: ${it.message ?: "Android blocked the service."}") }
        } else {
            app.stopService(serviceIntent)
        }
    }

    fun pairAndSendToMyraPeer(host: String, port: Int, pin: String, name: String, message: String) {
        app.myraConnectManager.pairAndSendTest(host, port, pin, name, message) { ok, detail ->
            _toastMessages.tryEmit(detail)
            viewModelScope.launch {
                myraRepo.log(if (ok) "INFO" else "WARN", "MyraConnect", detail)
            }
        }
    }

    fun removePairedDevice(deviceId: String) {
        viewModelScope.launch { app.myraConnectManager.removePairedDevice(deviceId) }
    }

    fun approveMemory(id: Long) {
        viewModelScope.launch {
            val approved = myraRepo.approvePendingMemory(id)
            _toastMessages.tryEmit(if (approved) "Memory saved with your approval." else "This memory proposal is no longer available.")
        }
    }

    fun rejectMemory(id: Long) {
        viewModelScope.launch {
            myraRepo.rejectPendingMemory(id)
            _toastMessages.tryEmit("Memory proposal rejected.")
        }
    }

    fun updateMemory(memoryKey: String, value: String) {
        viewModelScope.launch {
            myraRepo.updateMemory(memoryKey, value)
            _toastMessages.tryEmit("Personal memory updated.")
        }
    }

    fun updateEmotionalMemory(memoryKey: String, emotion: String, context: String) {
        viewModelScope.launch {
            myraRepo.updateEmotionalMemory(memoryKey, emotion, context)
            _toastMessages.tryEmit("Emotional memory updated.")
        }
    }

    fun deleteMemory(memoryKey: String) {
        viewModelScope.launch { myraRepo.deleteMemory(memoryKey) }
    }

    fun deleteEmotionalMemory(memoryKey: String) {
        viewModelScope.launch { myraRepo.deleteEmotionalMemory(memoryKey) }
    }

    fun clearAllMemories() {
        viewModelScope.launch { myraRepo.clearMemories() }
    }

    fun clearAllEmotionalMemories() {
        viewModelScope.launch { myraRepo.clearEmotionalMemories() }
    }


    fun createEncryptedBackup(passphrase: String, onResult: (ByteArray?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    require(passphrase.length >= 12) { "Backup password must be at least 12 characters." }
                    val chars = passphrase.toCharArray()
                    try {
                        MyraBackupCodec.encrypt(myraRepo.createBackupArchive(), chars)
                    } finally {
                        chars.fill(0.toChar())
                    }
                }
            }
            result.onFailure { _toastMessages.tryEmit(it.message ?: "Could not create backup.") }
            onResult(result.getOrNull())
        }
    }

    fun restoreEncryptedBackup(container: ByteArray, passphrase: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    require(container.size <= 28 * 1024 * 1024) { "Backup file is too large." }
                    val chars = passphrase.toCharArray()
                    try {
                        val archive = MyraBackupCodec.decrypt(container, chars)
                        myraRepo.restoreBackupArchive(archive)
                    } finally {
                        chars.fill(0.toChar())
                    }
                }
            }
            _toastMessages.tryEmit(
                if (result.isSuccess) "Backup restored. Existing MYRA data was merged safely."
                else result.exceptionOrNull()?.message ?: "Could not restore backup.",
            )
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch { myraRepo.clearChatHistory() }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch { myraRepo.clearLogs() }
    }

    fun clearParkingSpot() {
        viewModelScope.launch { myraRepo.clearParkingSpot() }
    }

    fun dismissOtpAlert() {
        MyraNotificationListenerService.dismissOtpAlert()
    }

    fun openAccessibilitySettings() = telemetry.openAccessibilitySettings()
    fun openOverlaySettings() = telemetry.openOverlaySettings()
    fun openNotificationListenerSettings() = telemetry.openNotificationListenerSettings()
    fun openAppDetailsSettings() = telemetry.openAppDetailsSettings()

    fun refreshPermissionsAndServices() {
        _uiState.value = _uiState.value.copy(
            permissions = telemetry.readPermissionSnapshot(),
            systemStatus = telemetry.readSystemStatus(_uiState.value.systemStatus.isTorchOn),
            wakeWordRunning = WakeWordService.isRunning,
            overlayRunning = MyraOverlayService.isRunning,
            callMonitorRunning = CallMonitorService.isRunning,
            pcCompanionRunning = PcCompanionService.isRunning,
        )
    }

    private fun startTelemetryLoop() {
        viewModelScope.launch {
            while (isActive) {
                val status = telemetry.readSystemStatus(_uiState.value.systemStatus.isTorchOn)
                _uiState.value = _uiState.value.copy(
                    systemStatus = status,
                    permissions = telemetry.readPermissionSnapshot(),
                    wakeWordRunning = WakeWordService.isRunning,
                    overlayRunning = MyraOverlayService.isRunning,
                    callMonitorRunning = CallMonitorService.isRunning,
                    pcCompanionRunning = PcCompanionService.isRunning,
                )
                if (app.pcCompanionServer.state.value.isRunning) {
                    app.pcCompanionServer.broadcastJson(
                        "telemetry",
                        JSONObject().apply {
                            put("battery", status.batteryPercent)
                            put("charging", status.isCharging)
                            put("ramUsedMb", status.usedRamMb)
                            put("ramTotalMb", status.totalRamMb)
                            put("time", status.timeLabel)
                        },
                    )
                }
                delay(30_000L)
            }
        }
    }

    private fun observeIncomingCompanionCommands() {
        viewModelScope.launch {
            app.incomingCompanionCommands.collect { (source, cmdText) ->
                myraRepo.log("INFO", source, "Received: $cmdText")
                sendUserText(cmdText)
            }
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { callSpeechRecognizer?.destroy() }
        geminiLive?.release()
        audioEngine?.release()
        super.onCleared()
    }
}
