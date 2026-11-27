package com.myra.assistant.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.myra.assistant.data.db.PairedDeviceEntity
import com.myra.assistant.data.repository.MyraRepository
import com.myra.assistant.data.preferences.SecureSettingsRepository
import com.myra.assistant.security.OtpSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredMyraPeer(
    val serviceName: String,
    val host: String,
    val port: Int,
)

data class MyraConnectState(
    val isHosting: Boolean = false,
    val localIp: String = "127.0.0.1",
    val localPort: Int = 8865,
    val pairingPin: String = "",
    val discoveredPeers: List<DiscoveredMyraPeer> = emptyList(),
    val lastSyncEvent: String = "",
)

/**
 * MYRA Connect: LAN-only device pairing with PBKDF2-derived keys, HMAC authentication, and AES-GCM encrypted messages.
 * Includes per-IP rate limiting against PIN brute-forcing and blocks OTP transmission.
 */
class MyraConnectManager(
    private val context: Context,
    private val repository: MyraRepository,
    private val onRemoteMessageReceived: (fromDevice: String, text: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val secureSettings = SecureSettingsRepository(context)
    private val deviceId = UUID.randomUUID().toString().take(8)
    private val serviceName = "MYRA-$deviceId"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var currentPin: String = PairingCrypto.generateSixDigitPin()

    private val _state = MutableStateFlow(MyraConnectState())
    val state: StateFlow<MyraConnectState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.pairedDevices.first()
                    .filter { it.sharedKeyHex.isNotBlank() }
                    .forEach { legacy ->
                        val stored = secureSettings.savePairedDeviceKey(legacy.deviceId, legacy.sharedKeyHex)
                        repository.upsertPairedDevice(legacy.copy(sharedKeyHex = ""))
                        if (!stored) {
                            repository.log(
                                "WARN",
                                "MyraConnect",
                                "Legacy pairing key for ${legacy.deviceId} was cleared because encrypted storage is unavailable; re-pair this device.",
                            )
                        }
                    }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        currentPin = PairingCrypto.generateSixDigitPin()
        failedAttempts.clear()

        scope.launch {
            try {
                val ip = PairingCrypto.getLocalWifiIpAddress()
                val bindAddress = runCatching { InetAddress.getByName(ip) }.getOrElse { InetAddress.getLoopbackAddress() }
                val ss = ServerSocket(0, 16, bindAddress)
                serverSocket = ss
                val port = ss.localPort
                _state.value = _state.value.copy(
                    isHosting = true,
                    localIp = ip,
                    localPort = port,
                    pairingPin = currentPin,
                )
                registerNsd(port)
                discoverNsd()
                repository.log("INFO", "MyraConnect", "Hosting on $ip:$port")

                while (running.get() && !ss.isClosed) {
                    val client = ss.accept()
                    if (activeConnections.incrementAndGet() > MAX_ACTIVE_CONNECTIONS) {
                        activeConnections.decrementAndGet()
                        runCatching { client.close() }
                        continue
                    }
                    launch {
                        try {
                            handleIncomingPeer(client)
                        } finally {
                            activeConnections.decrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    repository.log("ERROR", "MyraConnect", "Host error: ${e.message}")
                }
            } finally {
                running.set(false)
                _state.value = _state.value.copy(isHosting = false)
            }
        }
    }

    fun regeneratePin(): String {
        currentPin = PairingCrypto.generateSixDigitPin()
        _state.value = _state.value.copy(pairingPin = currentPin)
        return currentPin
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        regListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        discListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        regListener = null
        discListener = null
        _state.value = _state.value.copy(isHosting = false, discoveredPeers = emptyList())
    }

    fun pairAndSendTest(
        peerHost: String,
        peerPort: Int,
        pin: String,
        peerName: String,
        messageText: String = "MYRA Connect Ping",
        onResult: (Boolean, String) -> Unit,
    ) {
        if (messageText.isBlank() || messageText.length > MAX_MESSAGE_CHARS) {
            onResult(false, "Message must contain 1–$MAX_MESSAGE_CHARS characters.")
            return
        }
        if (OtpSanitizer.containsOtp(messageText)) {
            onResult(false, "Blocked: OTP content cannot be sent over MYRA Connect.")
            return
        }
        scope.launch {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(peerHost, peerPort), CONNECT_TIMEOUT_MS)
                    sock.soTimeout = SOCKET_READ_TIMEOUT_MS
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))

                    val challengeRaw = readBoundedLine(reader) ?: throw IllegalStateException("No challenge from peer")
                    val challenge = JSONObject(challengeRaw)
                    if (challenge.has("ok") && !challenge.optBoolean("ok", true)) {
                        onResult(false, challenge.optString("detail", "Locked by peer."))
                        return@launch
                    }
                    val nonce = challenge.getString("nonce")
                    val remoteId = challenge.optString("deviceId", "$peerHost:$peerPort")

                    val keyHex = PairingCrypto.deriveKeyHexFromPin(pin, nonce)
                    val ciphertext = PairingCrypto.encryptMessage(keyHex, nonce, messageText)
                    val senderName = android.os.Build.MODEL ?: "MYRA Phone"
                    val authPayload = "$nonce:$deviceId:$senderName:$ciphertext"
                    val signature = PairingCrypto.hmacSha256Hex(keyHex, authPayload)

                    val req = JSONObject().apply {
                        put("type", "pair_and_send")
                        put("senderId", deviceId)
                        put("senderName", senderName)
                        put("nonce", nonce)
                        put("ciphertext", ciphertext)
                        put("hmac", signature)
                    }
                    writer.write(req.toString())
                    writer.newLine()
                    writer.flush()

                    val respRaw = readBoundedLine(reader) ?: throw IllegalStateException("No ack from peer")
                    val resp = JSONObject(respRaw)
                    if (resp.optBoolean("ok", false)) {
                        val keySaved = secureSettings.savePairedDeviceKey(remoteId, keyHex)
                        repository.upsertPairedDevice(
                            PairedDeviceEntity(
                                deviceId = remoteId,
                                name = peerName.ifBlank { remoteId },
                                deviceType = "MYRA_MOBILE",
                                host = peerHost,
                                port = peerPort,
                                sharedKeyHex = "",
                            ),
                        )
                        _state.value = _state.value.copy(lastSyncEvent = "Paired & synced with $peerHost:$peerPort")
                        val detail = resp.optString("detail", "Authenticated & sent.")
                        onResult(true, if (keySaved) detail else "$detail Secure key was not saved; pair again next time.")
                    } else {
                        onResult(false, resp.optString("detail", "Authentication rejected by peer."))
                    }
                }
            } catch (e: Exception) {
                onResult(false, "MYRA Connect failed: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingPeer(socket: Socket) {
        val remoteIp = socket.inetAddress.hostAddress.orEmpty()
        try {
            socket.use { sock ->
                sock.soTimeout = 7000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))

                val now = System.currentTimeMillis()
                val attempt = failedAttempts[remoteIp]
                if (attempt != null && now < attempt.second) {
                    writer.write(JSONObject().apply {
                        put("ok", false)
                        put("detail", "Locked due to repeated invalid pairing attempts.")
                    }.toString())
                    writer.newLine()
                    writer.flush()
                    return
                }

                val nonce = PairingCrypto.generateRandomHexKey(16)
                writer.write(
                    JSONObject().apply {
                        put("deviceId", deviceId)
                        put("nonce", nonce)
                    }.toString(),
                )
                writer.newLine()
                writer.flush()

                val line = readBoundedLine(reader) ?: return
                val req = JSONObject(line)
                val senderId = req.optString("senderId", remoteIp)
                val senderName = req.optString("senderName", "MYRA Peer")
                val ciphertext = req.optString("ciphertext")
                val providedHmac = req.optString("hmac")
                if (req.optString("nonce") != nonce) {
                    writer.write(JSONObject().put("ok", false).put("detail", "Invalid pairing challenge.").toString())
                    writer.newLine()
                    writer.flush()
                    return
                }
                val authPayload = "$nonce:$senderId:$senderName:$ciphertext"

                val pinKey = PairingCrypto.deriveKeyHexFromPin(currentPin, nonce)
                val stored = repository.getPairedDevice(senderId)
                var storedKey = secureSettings.getPairedDeviceKey(senderId)
                if (
                    storedKey.isBlank() &&
                    secureSettings.isSecureStorageAvailable &&
                    !stored?.sharedKeyHex.isNullOrBlank()
                ) {
                    val legacyKey = stored?.sharedKeyHex.orEmpty()
                    if (secureSettings.savePairedDeviceKey(senderId, legacyKey)) {
                        storedKey = legacyKey
                        stored?.let { repository.upsertPairedDevice(it.copy(sharedKeyHex = "")) }
                    }
                }

                fun decryptIfAuthenticated(keyHex: String): String? {
                    if (keyHex.isBlank() || ciphertext.isBlank()) return null
                    return runCatching {
                        val expected = PairingCrypto.hmacSha256Hex(keyHex, authPayload)
                        if (!PairingCrypto.constantTimeEquals(providedHmac, expected)) {
                            null
                        } else {
                            PairingCrypto.decryptMessage(keyHex, nonce, ciphertext)
                        }
                    }.getOrNull()
                }

                val messageFromPin = decryptIfAuthenticated(pinKey)
                val messageFromStoredKey = if (messageFromPin == null) decryptIfAuthenticated(storedKey) else null
                val msg = messageFromPin ?: messageFromStoredKey
                val valid = msg != null

                if (valid && OtpSanitizer.containsOtp(msg.orEmpty())) {
                    writer.write(JSONObject().apply {
                        put("ok", false)
                        put("detail", "OTP content blocked by policy.")
                    }.toString())
                    writer.newLine()
                    writer.flush()
                    return
                }

                if (valid) {
                    failedAttempts.remove(remoteIp)
                    val activeKey = if (messageFromPin != null) pinKey else storedKey
                    val keySaved = secureSettings.savePairedDeviceKey(senderId, activeKey)
                    repository.upsertPairedDevice(
                        PairedDeviceEntity(
                            deviceId = senderId,
                            name = senderName,
                            deviceType = "MYRA_MOBILE",
                            host = remoteIp,
                            port = sock.port,
                            sharedKeyHex = "",
                        ),
                    )
                    val safeMessage = msg.orEmpty()
                    _state.value = _state.value.copy(lastSyncEvent = "Received from $senderName: $safeMessage")
                    onRemoteMessageReceived(senderName, safeMessage)
                    writer.write(JSONObject().apply {
                        put("ok", true)
                        put(
                            "detail",
                            if (keySaved) "Authenticated and AES-GCM encrypted."
                            else "Authenticated and encrypted for this session; secure storage unavailable, so pair again next time.",
                        )
                    }.toString())
                } else {
                    val isExpiredLock = attempt != null && attempt.second > 0L && now >= attempt.second
                    val prev = if (isExpiredLock) 0 else (attempt?.first ?: 0)
                    val next = prev + 1
                    val lockUntil = if (next >= MAX_ATTEMPTS) now + LOCKOUT_MS else 0L
                    failedAttempts[remoteIp] = next to lockUntil
                    writer.write(JSONObject().apply {
                        put("ok", false)
                        put("detail", if (lockUntil > 0L) "Too many failed attempts. Locked for 60s." else "Invalid pairing PIN or HMAC signature")
                    }.toString())
                }
                writer.newLine()
                writer.flush()
            }
        } catch (_: Exception) {
            // Peer disconnected
        }
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int = MAX_PROTOCOL_LINE_CHARS): String? {
        val result = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (result.isEmpty()) null else result.toString()
            if (ch == '\n'.code) return result.toString().trimEnd('\r')
            if (result.length >= maxChars) throw IllegalArgumentException("MYRA Connect frame exceeds the allowed size.")
            result.append(ch.toChar())
        }
    }

    suspend fun removePairedDevice(deviceId: String) = withContext(Dispatchers.IO) {
        secureSettings.removePairedDeviceKey(deviceId)
        repository.removePairedDevice(deviceId)
    }

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = this@MyraConnectManager.serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        runCatching { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discoverNsd() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_myra")) return
                if (service.serviceName == serviceName) return
                @Suppress("DEPRECATION")
                nsdManager?.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            val peer = DiscoveredMyraPeer(serviceInfo.serviceName, host, serviceInfo.port)
                            val updated = (_state.value.discoveredPeers.filterNot { it.serviceName == peer.serviceName } + peer)
                            _state.value = _state.value.copy(discoveredPeers = updated)
                        }
                    },
                )
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _state.value = _state.value.copy(
                    discoveredPeers = _state.value.discoveredPeers.filterNot { it.serviceName == service.serviceName },
                )
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discListener = listener
        runCatching { nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    companion object {
        private const val SERVICE_TYPE = "_myra._tcp."
        private const val MAX_ATTEMPTS = 5
        private const val MAX_ACTIVE_CONNECTIONS = 4
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 7_000
        private const val MAX_PROTOCOL_LINE_CHARS = 16_384
        private const val MAX_MESSAGE_CHARS = 4_000
        private const val LOCKOUT_MS = 60_000L
    }
}
