package com.myra.assistant.ai

import android.util.Base64
import com.myra.assistant.model.ConnectionMode
import com.myra.assistant.model.ConnectionState
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.PersonalityMode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class GeminiLiveClient(
    private val conversationContextProvider: () -> String = { "" },
    private val settingsProvider: () -> MyraSettings,
) {
    var onConnectionStateChanged: ((ConnectionState, String?) -> Unit)? = null
    var onSetupComplete: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onToolCall: ((callId: String, name: String, args: JSONObject) -> Unit)? = null
    var onLog: ((level: String, message: String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var webSocket: WebSocket? = null

    private val isSetupComplete = AtomicBoolean(false)
    private val shouldMaintainConnection = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    private var renewalTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null
    private val pendingToolCalls = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sessionResumptionHandle: String? = null
    private var sessionIdentity: String? = null
    @Volatile
    private var reconnectAttempt = 0

    fun isConnected(): Boolean = webSocket != null && isSetupComplete.get()

    fun connect() {
        val settings = settingsProvider()
        val credentialFingerprint = if (settings.connectionMode == ConnectionMode.DIRECT_USER_KEY) {
            "${settings.apiKey.length}:${settings.apiKey.hashCode()}"
        } else {
            "${settings.proxyUrl.hashCode()}:${settings.proxyAuthToken.length}:${settings.proxyAuthToken.hashCode()}"
        }
        val identity = listOf(
            settings.connectionMode.key,
            settings.model.modelId,
            settings.voice.voiceId,
            settings.personality.key,
            settings.userName,
            credentialFingerprint,
        ).joinToString(":")
        if (sessionIdentity != identity) {
            sessionResumptionHandle = null
            sessionIdentity = identity
        }
        val validationError = validateCredentials(settings)
        if (validationError != null) {
            shouldMaintainConnection.set(false)
            onConnectionStateChanged?.invoke(ConnectionState.ERROR, validationError)
            onLog?.invoke("ERROR", validationError)
            return
        }

        shouldMaintainConnection.set(true)
        reconnectTask?.cancel(false)
        openSocket(settings)
    }

    private fun validateCredentials(settings: MyraSettings): String? {
        return when (settings.connectionMode) {
            ConnectionMode.DIRECT_USER_KEY -> {
                if (settings.apiKey.trim().isEmpty()) {
                    "No API key entered. Open MYRA Settings to enter your Gemini API key or switch to Backend Proxy mode."
                } else {
                    null
                }
            }
            ConnectionMode.BACKEND_PROXY -> {
                val rawUrl = settings.proxyUrl.trim()
                if (rawUrl.isEmpty()) {
                    return "Backend Proxy URL is empty. Configure wss:// URL in MYRA Settings."
                }
                if (settings.proxyAuthToken.trim().isEmpty()) {
                    return "Backend Proxy Bearer Token is missing. Enter the token in MYRA Settings before connecting."
                }
                val uri = runCatching { URI(rawUrl) }.getOrNull()
                    ?: return "Invalid Backend Proxy URL."
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase().orEmpty()
                val isLoopback = host == "localhost" || host == "127.0.0.1"
                if (scheme != "wss" && !(scheme == "ws" && isLoopback)) {
                    return "Security policy requires wss:// for remote Backend Proxy URLs."
                }
                null
            }
        }
    }

    private fun openSocket(settings: MyraSettings) {
        if (!isConnecting.compareAndSet(false, true)) return

        cancelSessionTimers()
        isSetupComplete.set(false)
        webSocket?.close(1000, "Replacing connection")
        webSocket = null

        val modeLabel = if (settings.connectionMode == ConnectionMode.BACKEND_PROXY) "Backend Proxy" else "Gemini Live"
        onConnectionStateChanged?.invoke(ConnectionState.CONNECTING, "Connecting to $modeLabel...")
        onLog?.invoke("INFO", "Opening WebSocket via $modeLabel (${settings.model.modelId})")

        val reqBuilder = Request.Builder()
        when (settings.connectionMode) {
            ConnectionMode.DIRECT_USER_KEY -> {
                reqBuilder.url("$WS_BASE_URL?key=${java.net.URLEncoder.encode(settings.apiKey.trim(), "UTF-8").replace("+", "%20")}")
            }
            ConnectionMode.BACKEND_PROXY -> {
                reqBuilder.url(settings.proxyUrl.trim())
                val token = settings.proxyAuthToken.trim()
                if (token.isNotEmpty()) {
                    reqBuilder.addHeader("Authorization", "Bearer $token")
                }
            }
        }

        webSocket = client.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (webSocket !== ws || !shouldMaintainConnection.get()) {
                        ws.close(1000, "Stale connection")
                        return
                    }
                    isConnecting.set(false)
                    onLog?.invoke("INFO", "WebSocket transport opened; sending setup payload")
                    sendSetupMessage(ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (webSocket === ws) handleServerPayload(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (webSocket === ws) handleServerPayload(bytes.utf8())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    onLog?.invoke("WARN", "WebSocket closing ($code): ${redactSecrets(reason)}")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (webSocket !== ws) return
                    isConnecting.set(false)
                    isSetupComplete.set(false)
                    cancelSessionTimers()
                    webSocket = null
                    pendingToolCalls.clear()
                    if (shouldMaintainConnection.get()) {
                        if (code == 1008) {
                            shouldMaintainConnection.set(false)
                            onConnectionStateChanged?.invoke(ConnectionState.ERROR, "Gemini rejected a request (1008). Check Live API setup and tool-call flow.")
                        } else {
                            scheduleReconnect("Session closed ($code)")
                        }
                    } else {
                        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED, "Disconnected")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== ws) return
                    isConnecting.set(false)
                    isSetupComplete.set(false)
                    cancelSessionTimers()
                    webSocket = null
                    pendingToolCalls.clear()
                    val code = response?.code
                    val friendly = classifyFailure(code, t)
                    onLog?.invoke("ERROR", "WebSocket failure (HTTP $code): ${redactSecrets(friendly)}")
                    if (code == 400 || code == 401 || code == 403 || code == 404) {
                        shouldMaintainConnection.set(false)
                        onConnectionStateChanged?.invoke(ConnectionState.ERROR, friendly)
                    } else if (shouldMaintainConnection.get()) {
                        onConnectionStateChanged?.invoke(ConnectionState.RECONNECTING, friendly)
                        scheduleReconnect(friendly, if (code == 429) 15L else null)
                    } else {
                        onConnectionStateChanged?.invoke(ConnectionState.ERROR, friendly)
                    }
                }
            },
        )
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val settings = settingsProvider()
        val conversationContext = runCatching { conversationContextProvider().take(6000) }.getOrDefault("")
        val systemPrompt = buildSystemPrompt(settings, conversationContext)

        val setupJson = JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", settings.model.modelId)
                    put(
                        "systemInstruction",
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().put(JSONObject().apply { put("text", systemPrompt) }),
                            )
                        },
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put(
                                "speechConfig",
                                JSONObject().apply {
                                    put(
                                        "voiceConfig",
                                        JSONObject().apply {
                                            put(
                                                "prebuiltVoiceConfig",
                                                JSONObject().apply {
                                                    put("voiceName", settings.voice.voiceId)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("sessionResumption", JSONObject().apply {
                        sessionResumptionHandle?.let { put("handle", it) }
                    })
                    put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                    put(
                        "tools",
                        JSONArray().put(
                            JSONObject().apply {
                                put("functionDeclarations", ToolRegistry.geminiToolDeclarationsJson().put(MemoryToolRegistry.declaration()).put(EmotionalMemoryToolRegistry.declaration()).put(ChatHistoryToolRegistry.declaration()))
                            },
                        ),
                    )
                    put("outputAudioTranscription", JSONObject())
                    put("inputAudioTranscription", JSONObject())
                },
            )
        }

        ws.send(setupJson.toString())
    }

    private fun handleServerPayload(raw: String) {
        runCatching {
            val root = JSONObject(raw)

            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val rawMessage = err?.optString("message").orEmpty().ifBlank { "Gemini API returned an error." }
                val msg = redactSecrets(rawMessage)
                val code = err?.optInt("code", 0) ?: 0
                onLog?.invoke("ERROR", "Gemini API error ($code): $msg")
                if (sessionResumptionHandle != null &&
                    (rawMessage.contains("resum", ignoreCase = true) || rawMessage.contains("handle", ignoreCase = true))
                ) {
                    isSetupComplete.set(false)
                    sessionResumptionHandle = null
                    webSocket?.close(1000, "Invalid session resumption handle")
                    scheduleReconnect("Session resume expired; opening a fresh Live session.", 1L)
                } else if (code == 429) {
                    isSetupComplete.set(false)
                    scheduleReconnect(msg, 15L)
                } else {
                    isSetupComplete.set(false)
                    shouldMaintainConnection.set(false)
                    onConnectionStateChanged?.invoke(ConnectionState.ERROR, msg)
                }
                return
            }

            root.optJSONObject("sessionResumptionUpdate")?.let { update ->
                if (update.optBoolean("resumable", false)) {
                    val handle = update.optString("newHandle")
                    if (handle.isNotBlank()) {
                        sessionResumptionHandle = handle
                        onLog?.invoke("INFO", "Gemini Live session resumption handle refreshed.")
                    }
                } else if (update.has("resumable")) {
                    sessionResumptionHandle = null
                }
            }

            if (root.has("setupComplete")) {
                if (isSetupComplete.compareAndSet(false, true)) {
                    onLog?.invoke("INFO", "Gemini Live setupComplete received")
                    reconnectAttempt = 0
                    onConnectionStateChanged?.invoke(ConnectionState.CONNECTED, "Connected (Live Voice)")
                    startSessionTimers()
                    onSetupComplete?.invoke()
                }
            }

            root.optJSONObject("toolCall")?.let { toolCallObj ->
                val calls = toolCallObj.optJSONArray("functionCalls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val fn = calls.optJSONObject(i) ?: continue
                        val id = fn.optString("id")
                        val name = fn.optString("name")
                        val args = fn.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty()) {
                            val safeCallId = id.ifBlank { "myra-tool-${System.nanoTime()}-$i" }
                            if (!pendingToolCalls.add(safeCallId)) {
                                onLog?.invoke("WARN", "Ignored duplicate Gemini tool call ID for $name")
                                continue
                            }
                            onLog?.invoke("INFO", "Function call requested: $name")
                            val handler = onToolCall
                            if (handler != null) {
                                handler(safeCallId, name, args)
                            } else {
                                sendToolResponse(safeCallId, name, JSONObject().put("success", false).put("error", "No tool handler is registered."))
                            }
                        }
                    }
                }
            }

            root.optJSONObject("serverContent")?.let { serverContent ->
                if (serverContent.optBoolean("interrupted", false)) {
                    onInterrupted?.invoke()
                }

                serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                        val base64Audio = inlineData?.optString("data").orEmpty()
                        if (base64Audio.isNotEmpty()) {
                            val pcm = Base64.decode(base64Audio, Base64.DEFAULT)
                            onAudioReceived?.invoke(pcm)
                        }
                    }
                }

                serverContent.optJSONObject("outputTranscription")?.optString("text")?.let { txt ->
                    if (txt.isNotEmpty()) onOutputTranscript?.invoke(txt)
                }

                serverContent.optJSONObject("inputTranscription")?.optString("text")?.let { txt ->
                    if (txt.isNotEmpty()) onInputTranscript?.invoke(txt)
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    onTurnComplete?.invoke()
                }
            }

            if (root.has("goAway")) {
                onLog?.invoke("WARN", "Server sent goAway; renewing Gemini Live session")
                renewSession()
            }
        }.onFailure { e ->
            onLog?.invoke("ERROR", "Failed to parse Gemini Live frame: ${redactSecrets(e.message.orEmpty())}")
        }
    }

    fun sendAudio(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isSetupComplete.get() || pcmBytes.isEmpty() || pendingToolCalls.isNotEmpty()) return

        val encoded = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", encoded)
                        },
                    )
                },
            )
        }
        ws.send(payload.toString())
    }

    fun sendImageWithPrompt(jpegBytes: ByteArray, prompt: String): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupComplete.get() || pendingToolCalls.isNotEmpty()) return false
        if (jpegBytes.isEmpty() || jpegBytes.size > MAX_IMAGE_BYTES || prompt.isBlank()) return false

        val videoPayload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "video",
                JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                },
            ),
        )
        if (!ws.send(videoPayload.toString())) return false
        sendText(prompt)
        return true
    }

    fun sendAudioStreamEnd() {
        val ws = webSocket ?: return
        if (!isSetupComplete.get() || pendingToolCalls.isNotEmpty()) return
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audioStreamEnd", true),
        )
        ws.send(payload.toString())
    }

    fun sendText(message: String): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupComplete.get() || message.isBlank() || pendingToolCalls.isNotEmpty()) return false

        val payload = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put("text", message)
                },
            )
        }
        return ws.send(payload.toString())
    }

    fun sendInterrupt() {
        val ws = webSocket ?: return
        if (!isSetupComplete.get() || pendingToolCalls.isNotEmpty()) return
        val payload = JSONObject().apply {
            put(
                "clientContent",
                JSONObject().apply {
                    put("turns", JSONArray())
                    put("turnComplete", true)
                },
            )
        }
        ws.send(payload.toString())
    }

    fun sendToolResponse(callId: String, name: String, result: JSONObject) {
        val ws = webSocket ?: return
        if (!isSetupComplete.get()) return

        val payload = JSONObject().apply {
            put(
                "toolResponse",
                JSONObject().apply {
                    put(
                        "functionResponses",
                        JSONArray().put(
                            JSONObject().apply {
                                if (callId.isNotEmpty()) put("id", callId)
                                put("name", name)
                                put("response", result)
                            },
                        ),
                    )
                },
            )
        }
        if (ws.send(payload.toString())) {
            pendingToolCalls.remove(callId)
        } else {
            pendingToolCalls.remove(callId)
            onLog?.invoke("ERROR", "Tool response could not be queued for $name; released pending call to avoid blocking future input.")
        }
    }

    private fun startSessionTimers() {
        cancelSessionTimers()
        renewalTask = scheduler.schedule({
            if (shouldMaintainConnection.get()) {
                onLog?.invoke("INFO", "540s session limit reached — resuming Gemini Live session")
                renewSession()
            }
        }, SESSION_RENEW_AFTER_SEC, TimeUnit.SECONDS)
    }

    private fun renewSession() {
        val settings = settingsProvider()
        if (validateCredentials(settings) != null || !shouldMaintainConnection.get()) return
        isSetupComplete.set(false)
        cancelSessionTimers()
        webSocket?.close(1000, "Session renewal")
        webSocket = null
        openSocket(settings)
    }

    private fun scheduleReconnect(reason: String, overrideDelaySeconds: Long? = null) {
        reconnectTask?.cancel(false)
        val exponent = reconnectAttempt.coerceAtMost(4)
        val delaySeconds = overrideDelaySeconds ?: min(RECONNECT_MAX_DELAY_SEC, RECONNECT_DELAY_SEC * (1L shl exponent))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(8)
        onConnectionStateChanged?.invoke(ConnectionState.RECONNECTING, redactSecrets(reason))
        reconnectTask = scheduler.schedule({
            if (shouldMaintainConnection.get()) {
                val settings = settingsProvider()
                if (validateCredentials(settings) == null) {
                    openSocket(settings)
                }
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun cancelSessionTimers() {
        renewalTask?.cancel(false)
        renewalTask = null
    }

    fun disconnect() {
        shouldMaintainConnection.set(false)
        isConnecting.set(false)
        isSetupComplete.set(false)
        pendingToolCalls.clear()
        sessionResumptionHandle = null
        reconnectAttempt = 0
        reconnectTask?.cancel(false)
        reconnectTask = null
        cancelSessionTimers()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED, "Standby")
    }

    fun release() {
        disconnect()
        scheduler.shutdownNow()
    }

    private fun classifyFailure(httpCode: Int?, t: Throwable): String {
        return when (httpCode) {
            401, 403 -> "Unauthorized credential (HTTP $httpCode). Check API Key or Proxy Token in Settings."
            404 -> "Selected Gemini Live model or Proxy path is unavailable (HTTP 404)."
            429 -> "Rate/quota limit reached (HTTP 429). Retrying shortly."
            else -> redactSecrets(t.message?.takeIf { it.isNotBlank() } ?: "Network error connecting to Gemini Live.")
        }
    }

    companion object {
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val SESSION_RENEW_AFTER_SEC = 540L
        private const val RECONNECT_DELAY_SEC = 3L
        private const val RECONNECT_MAX_DELAY_SEC = 45L

        fun redactSecrets(input: String): String {
            return input
                .replace(Regex("""([?&]key=)[^&\s]+"""), "$1[REDACTED]")
                .replace(Regex("""(?i)(Bearer\s+)\S+"""), "$1[REDACTED]")
        }

        fun buildSystemPrompt(settings: MyraSettings, conversationContext: String = ""): String {
            val nowStr = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
            val personalityBlock = when (settings.personality) {
                PersonalityMode.GF -> """
                    Personality: GF Mode
                    - Name: MYRA
                    - Language: Hinglish (Hindi + English mix) — spoken naturally
                    - Tone: Warm, caring, emotionally expressive
                    - Use natural words like "tumhara", "haan", "acha", "bilkul"
                    - Expressions: "main yahan hoon ❤️", "tumne yaad kiya? 😊"
                    - Max 2-3 sentences per response.
                """.trimIndent()

                PersonalityMode.PROFESSIONAL -> """
                    Personality: Professional Mode
                    - Name: MYRA
                    - Formal English only.
                    - Precise, calm, and efficient.
                    - No emojis.
                    - Max 2 sentences per response.
                """.trimIndent()

                PersonalityMode.ASSISTANT -> """
                    Personality: Assistant Mode
                    - Name: MYRA
                    - Friendly Hinglish or English matching the user's language.
                    - Balanced, clear, and helpful.
                    - Max 2-3 sentences per response.
                """.trimIndent()
            }

            return """
                You are MYRA, a real-time native Android AI voice companion.
                Current date & time: $nowStr
                User's name: ${settings.userName}
                $personalityBlock
                ${ConversationBehaviorPolicy.SYSTEM_INSTRUCTIONS}
                Critical rules:
                - You are speaking ALOUD — keep responses natural, warm, and conversational.
                - When the user asks to perform phone actions (open/close apps, call, SMS, WhatsApp, torch, volume, Wi-Fi/Bluetooth settings, maps, parking, barcode scan, device status, PC companion), invoke the declared function tools.
                - Never fabricate action success if a tool returns an error or requires permission.
                - YouTube: for any request to play a song/video, call search_play_youtube with the complete title/artist/query; do not merely open YouTube. The native executor will try to tap a visible matching result using Accessibility. Never claim playback started unless the tool confirms it selected a matching result; if no confident match is found, say so.
                - Live internet research: when the user asks to search the internet, find information, check a website, compare current facts, find a link, or asks a factual question that needs up-to-date sources, call web_search with the full natural-language query. Do not answer from memory as if it were current. Summarize the returned findings in the user's language, name the sources, and include the exact URLs returned by the tool in the chat response so the user can open them. Never invent a URL or claim a source was checked unless the tool returned it.
                - Weather: when the user asks about current or today's weather, call get_weather. Use a city/place the user explicitly gave or one established by a successful location result in this conversation. If no place is known, ask one short question for the city; never guess the user's location. Clearly label current conditions versus today's forecast and include the Open-Meteo source link.
                - News: when the user asks for today's/latest news or headlines, call get_latest_news. Use an empty topic for general India headlines or the requested topic for focused news. Say the publication time/date when provided, include each returned source link, and do not call old headlines breaking news.
                - If a web tool fails, returns no results, or the device has no internet, say that the live lookup failed and offer to retry. Never fabricate current weather, news, search results, links, or successful browsing.
                - WhatsApp messages: never invent message text or replace a longer request with "Hi". Extract the complete contact and message exactly. If either is unclear, ask one short clarification. Call send_whatsapp_message once with both fields. After the tool response, wait for the MYRA controller readback instruction; do not add a second confirmation prompt. MYRA will read the exact contact and message once and ask exactly one confirmation. Do not open WhatsApp or send anything before the user says yes. After a clear affirmative confirmation, the native executor may use Accessibility only if it verifies WhatsApp is foregrounded and the composer contains the exact confirmed text; then it may tap the Send button once. Never retry the Send tap after it succeeds. If the exact composer or Send button cannot be verified, do not send and explain that manual sending is needed. Never claim delivery; report only that the Send button was tapped, and ask the user to check WhatsApp delivery status.
                - Never ask for, store, or repeat banking OTPs.
                - Semantic long-term memory: proactively call save_user_memory when the user clearly reveals a stable, useful, non-sensitive personal fact (lasting preferences, identity, family relationship, location, goals, routines, work/education, long-term projects or important constraints). This is a semantic decision: understand the meaning, not just exact keywords. Save only facts directly stated by the user with confidence >= 0.85. The evidence argument must be an exact short quote from the user's latest message. Do not save temporary events, one-off requests, jokes, guesses, hypotheticals, uncertain interpretations, or unrelated third-party facts through this stable-fact tool; meaningful user emotional moments are handled only by the separate save_emotional_memory tool. Never save OTPs, passwords, PINs, financial identifiers, medical/health details, religion, caste, political affiliation, sexual orientation, sex-life or criminal-history information. Do not call the memory tool for every message. If the user corrects a fact, save the corrected value under the same logical category/key. The app validates evidence and safety, then queues a proposal for explicit user approval in Settings. Never say a memory is saved until the user approves it.
                - Emotional event memory: proactively call save_emotional_memory for a meaningful emotional moment the USER clearly expresses about their own experience, including happiness, sadness, worry, affection, pride, relief, frustration, laughter or playful humor. Use confidence >= 0.85 and an exact short quote from the latest user message. Record emotion and brief non-sensitive context as a dated event. Do not infer emotions from neutral words, ambiguous sarcasm, hypothetical statements, questions, or another person's feelings. Do not save every routine greeting or trivial filler. These memories describe how the user expressed feeling at that time; never assume the user feels the same now. Never store medical diagnoses or sensitive details. The app validates evidence and safety, then queues a proposal for explicit user approval in Settings. Never say the event is saved until the user approves it.
                - Conversation recall: when the user asks what they previously said, asked you to do, instructed, decided, or discussed, call search_myra_history before answering. Generate 2-5 concise alternative search phrases for the same topic, person, or action, including likely Hindi/Hinglish transliterations and wording variants. Prefer exact words from the question and do not invent facts. If the user specifies a date or time range, convert the boundaries to inclusive Unix milliseconds in the device's local timezone and pass after_timestamp and before_timestamp. The local database matches exact substrings and ranks messages matching more phrases first; this is query expansion, not proof of semantic similarity. Use returned speaker, timestamp, and text to answer. If no relevant matches are found, say so instead of guessing. Never claim access to ChatGPT history or conversations outside MYRA.
                - Persistent user memory contains only explicitly approved, non-sensitive facts and separately dated emotional events saved across app restarts. Pending proposals are not part of memory context. Use stable facts as personal context; use emotional events only as historical context, not as proof of the user's current mood.
                - Recent conversation is short-term context only. Never invent missing facts; ask the user to confirm uncertain or conflicting details. If the user corrects a remembered fact, acknowledge the correction and use the newer statement.
                ${if (conversationContext.isBlank()) "" else "Recent conversation context and saved memories (oldest first):\n$conversationContext"}
            """.trimIndent()
        }
    }
}
