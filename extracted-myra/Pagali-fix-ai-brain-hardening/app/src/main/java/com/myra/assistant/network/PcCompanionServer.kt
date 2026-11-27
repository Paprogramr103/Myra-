package com.myra.assistant.network

import com.myra.assistant.security.OtpSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PcCompanionState(
    val isRunning: Boolean = false,
    val hostIp: String = "127.0.0.1",
    val port: Int = 8765,
    val pairingPin: String = "",
    val authenticatedClients: List<String> = emptyList(),
    val lastMessage: String = "",
)

/**
 * Real embedded HTTP + RFC 6455 WebSocket server for MYRA PC Companion over local Wi-Fi.
 * Requires 6-digit PIN authentication with per-IP brute-force lockout (5 attempts -> 60s block).
 * Strictly blocks OTP content from ever leaving the phone.
 */
class PcCompanionServer(
    private val onCommandFromPc: (clientName: String, commandText: String) -> Unit,
    private val onClientAuthenticated: (clientName: String, remoteHost: String) -> Unit,
    private val onStatusLog: (String, String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<ClientSession>()
    private val activeConnections = AtomicInteger(0)
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>() // ip -> (count, lockUntilMs)

    private val _state = MutableStateFlow(PcCompanionState())
    val state: StateFlow<PcCompanionState> = _state.asStateFlow()

    @Volatile
    private var currentPin: String = PairingCrypto.generateSixDigitPin()

    fun regeneratePin(): String {
        currentPin = PairingCrypto.generateSixDigitPin()
        failedAttempts.clear()
        _state.value = _state.value.copy(pairingPin = currentPin)
        return currentPin
    }

    fun start(port: Int = 8765) {
        if (running.get()) return
        currentPin = PairingCrypto.generateSixDigitPin()
        failedAttempts.clear()
        running.set(true)

        executor.execute {
            try {
                val ip = PairingCrypto.getLocalWifiIpAddress()
                val bindAddress = runCatching { InetAddress.getByName(ip) }.getOrElse { InetAddress.getLoopbackAddress() }
                val ss = ServerSocket(port, 50, bindAddress)
                serverSocket = ss
                _state.value = PcCompanionState(
                    isRunning = true,
                    hostIp = ip,
                    port = ss.localPort,
                    pairingPin = currentPin,
                )
                onStatusLog("INFO", "PC Companion listening at http://$ip:${ss.localPort} (ws://$ip:${ss.localPort}/ws)")

                while (running.get() && !ss.isClosed) {
                    val socket = ss.accept()
                    if (activeConnections.incrementAndGet() > MAX_ACTIVE_CONNECTIONS) {
                        activeConnections.decrementAndGet()
                        runCatching { socket.close() }
                        continue
                    }
                    executor.execute {
                        try {
                            handleSocket(socket)
                        } finally {
                            activeConnections.decrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onStatusLog("ERROR", "PC Companion server error: ${e.message}")
                }
            } finally {
                running.set(false)
                _state.value = _state.value.copy(isRunning = false, authenticatedClients = emptyList())
            }
        }
    }

    fun stop() {
        running.set(false)
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = _state.value.copy(isRunning = false, authenticatedClients = emptyList())
    }

    fun broadcastJson(type: String, data: JSONObject): Int {
        val raw = data.toString()
        if (OtpSanitizer.containsOtp(raw)) {
            onStatusLog("WARN", "Blocked OTP-bearing payload from PC Companion broadcast.")
            return 0
        }
        if (raw.length > MAX_BROADCAST_CHARS) {
            onStatusLog("WARN", "Blocked oversized PC Companion broadcast.")
            return 0
        }
        val frameJson = JSONObject().apply {
            put("type", type)
            put("data", data)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        var delivered = 0
        clients.filter { it.authenticated }.forEach { session ->
            if (runCatching { session.sendTextFrame(frameJson) }.isSuccess) delivered++
        }
        return delivered
    }

    private fun handleSocket(socket: Socket) {
        val remoteIp = socket.inetAddress.hostAddress.orEmpty()
        try {
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestHeaders = readHttpHeaders(input) ?: run {
                socket.close()
                return
            }

            val headerMap = mutableMapOf<String, String>()
            requestHeaders.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headerMap[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val requestTarget = requestHeaders.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
            val wsKey = headerMap["sec-websocket-key"]
            val origin = headerMap["origin"]
            val originHost = origin?.let { runCatching { URI(it).host }.getOrNull() }
            val hostHeader = headerMap["host"]?.substringBefore(":")
            val originAllowed = originHost == null || originHost == _state.value.hostIp ||
                originHost == "localhost" || originHost == hostHeader
            val wsVersionValid = headerMap["sec-websocket-version"] == "13"
            if (
                headerMap["upgrade"].equals("websocket", ignoreCase = true) &&
                requestTarget == "/ws" &&
                !wsKey.isNullOrEmpty() &&
                wsVersionValid &&
                originAllowed
            ) {
                val acceptKey = computeWebSocketAccept(wsKey)
                val handshake = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: $acceptKey\r\n\r\n")
                }
                output.write(handshake.toByteArray(Charsets.UTF_8))
                output.flush()

                socket.soTimeout = 0
                val session = ClientSession(socket, input, output, remoteIp)
                clients.add(session)
                runWebSocketLoop(session)
            } else {
                val html = buildDashboardHtml(_state.value.hostIp, _state.value.port)
                val bodyBytes = html.toByteArray(Charsets.UTF_8)
                val httpResp = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(httpResp.toByteArray(Charsets.UTF_8))
                output.write(bodyBytes)
                output.flush()
                socket.close()
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun runWebSocketLoop(session: ClientSession) {
        try {
            session.sendTextFrame(
                JSONObject().apply {
                    put("type", "auth_required")
                    put("message", "Send the 6-digit PIN from MYRA phone screen to authenticate.")
                }.toString(),
            )
            while (running.get() && !session.socket.isClosed) {
                val message = session.readTextFrame() ?: break
                if (message.isNotEmpty()) {
                    handleClientMessage(session, message)
                }
            }
        } finally {
            clients.remove(session)
            refreshClientList()
            runCatching { session.socket.close() }
        }
    }

    private fun handleClientMessage(session: ClientSession, raw: String) {
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type")
            if (!session.authenticated) {
                val now = System.currentTimeMillis()
                val attemptInfo = failedAttempts[session.remoteIp]
                if (attemptInfo != null && now < attemptInfo.second) {
                    session.sendTextFrame(
                        JSONObject().apply {
                            put("type", "auth_locked")
                            put("message", "Too many invalid PIN attempts. Locked for 60s.")
                        }.toString(),
                    )
                    return
                }

                if (type == "auth") {
                    val pin = json.optString("pin").trim()
                    val name = json.optString("clientName", "PC Browser").trim().ifEmpty { "PC Browser" }
                    if (PairingCrypto.constantTimeEquals(pin, currentPin)) {
                        failedAttempts.remove(session.remoteIp)
                        session.authenticated = true
                        session.clientName = name
                        refreshClientList()
                        session.sendTextFrame(
                            JSONObject().apply {
                                put("type", "auth_ok")
                                put("message", "Paired with MYRA successfully.")
                            }.toString(),
                        )
                        onClientAuthenticated(name, session.remoteIp)
                        onStatusLog("INFO", "PC Companion authenticated: $name (${session.remoteIp})")
                    } else {
                        val isExpiredLock = attemptInfo != null && attemptInfo.second > 0L && now >= attemptInfo.second
                        val prevCount = if (isExpiredLock) 0 else (attemptInfo?.first ?: 0)
                        val newCount = prevCount + 1
                        val lockUntil = if (newCount >= MAX_PIN_ATTEMPTS) now + LOCKOUT_MS else 0L
                        failedAttempts[session.remoteIp] = newCount to lockUntil
                        if (lockUntil > 0L) {
                            onStatusLog("WARN", "PC Companion locked IP ${session.remoteIp} after $MAX_PIN_ATTEMPTS failed PIN attempts")
                        }
                        session.sendTextFrame(
                            JSONObject().apply {
                                put("type", "auth_error")
                                put("message", if (lockUntil > 0L) "Too many failed attempts. Locked for 60s." else "Invalid pairing PIN.")
                            }.toString(),
                        )
                    }
                } else {
                    session.sendTextFrame(
                        JSONObject().apply {
                            put("type", "auth_required")
                            put("message", "Authenticate with PIN first.")
                        }.toString(),
                    )
                }
                return
            }

            when (type) {
                "command" -> {
                    val text = json.optString("text").trim()
                    if (text.length > MAX_COMMAND_CHARS) {
                        session.sendTextFrame(
                            JSONObject().put("type", "blocked").put("message", "Command is too long.").toString(),
                        )
                    } else if (OtpSanitizer.containsOtp(text)) {
                        session.sendTextFrame(
                            JSONObject().apply {
                                put("type", "blocked")
                                put("message", "OTP content is blocked by MYRA policy.")
                            }.toString(),
                        )
                    } else if (text.isNotEmpty()) {
                        _state.value = _state.value.copy(lastMessage = "${session.clientName}: $text")
                        onCommandFromPc(session.clientName, text)
                        session.sendTextFrame(
                            JSONObject().apply {
                                put("type", "ack")
                                put("message", "Command delivered to MYRA.")
                            }.toString(),
                        )
                    }
                }
                "ping" -> {
                    session.sendTextFrame(JSONObject().apply { put("type", "pong") }.toString())
                }
            }
        }
    }

    private fun refreshClientList() {
        val names = clients.filter { it.authenticated }.map { "${it.clientName} (${it.remoteIp})" }
        _state.value = _state.value.copy(authenticatedClients = names)
    }

    private fun readHttpHeaders(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        val lineBuf = ByteArrayOutputStream()
        var totalBytes = 0
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return null
            totalBytes++
            if (totalBytes > MAX_HTTP_HEADER_BYTES) return null
            if (b == '\n'.code && prev == '\r'.code) {
                val rawBytes = lineBuf.toByteArray()
                val line = String(rawBytes, 0, (rawBytes.size - 1).coerceAtLeast(0), Charsets.UTF_8)
                lineBuf.reset()
                if (line.isEmpty()) break
                lines.add(line)
            } else {
                lineBuf.write(b)
            }
            prev = b
            if (lines.size > 64) return null
        }
        return lines
    }

    private fun computeWebSocketAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha1)
    }

    private class ClientSession(
        val socket: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
        val remoteIp: String,
    ) {
        @Volatile
        var authenticated = false

        @Volatile
        var clientName = "PC"

        private val writeLock = Any()

        fun sendTextFrame(text: String) {
            sendFrame(0x81, text.toByteArray(Charsets.UTF_8))
        }

        private fun sendPongFrame(payload: ByteArray) {
            sendFrame(0x8A, payload)
        }

        private fun sendFrame(firstByte: Int, payload: ByteArray) {
            synchronized(writeLock) {
                output.write(firstByte)
                when {
                    payload.size <= 125 -> output.write(payload.size)
                    payload.size <= 65535 -> {
                        output.write(126)
                        output.write((payload.size ushr 8) and 0xFF)
                        output.write(payload.size and 0xFF)
                    }
                    else -> {
                        output.write(127)
                        val len = payload.size.toLong()
                        for (shift in 56 downTo 0 step 8) {
                            output.write(((len ushr shift) and 0xFFL).toInt())
                        }
                    }
                }
                output.write(payload)
                output.flush()
            }
        }

        fun readTextFrame(): String? {
            val b1 = input.read()
            if (b1 == -1) return null
            val b2 = input.read()
            if (b2 == -1) return null

            val finalFrame = (b1 and 0x80) != 0
            val reservedBits = b1 and 0x70
            val opcode = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            if (!finalFrame || reservedBits != 0 || !masked) return null
            if (opcode != 0x1 && opcode != 0x8 && opcode != 0x9 && opcode != 0xA) return null
            if (opcode == 0x8) return null

            var length = (b2 and 0x7F).toLong()
            if (length == 126L) {
                val hi = input.read()
                val lo = input.read()
                if (hi == -1 || lo == -1) return null
                length = ((hi shl 8) or lo).toLong()
            } else if (length == 127L) {
                var len64 = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return null
                    len64 = (len64 shl 8) or b.toLong()
                }
                length = len64
            }

            if (length < 0 || length > MAX_WEBSOCKET_FRAME_BYTES) return null
            if (opcode >= 0x8 && length > 125L) return null
            val maskKey = ByteArray(4)
            readFully(maskKey)
            val data = ByteArray(length.toInt())
            readFully(data)
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            if (opcode == 0x9) {
                sendPongFrame(data)
                return ""
            }
            if (opcode == 0xA) return ""
            return String(data, Charsets.UTF_8)
        }

        private fun readFully(buf: ByteArray) {
            var offset = 0
            while (offset < buf.size) {
                val r = input.read(buf, offset, buf.size - offset)
                if (r == -1) throw EOFException("Stream closed")
                offset += r
            }
        }
    }

    private fun buildDashboardHtml(host: String, port: Int): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8"/>
          <title>MYRA PC Companion</title>
          <style>
            body{background:#050505;color:#eee;font-family:system-ui,sans-serif;max-width:680px;margin:40px auto;padding:20px;}
            .card{background:#111;border:1px solid #2a1018;border-radius:16px;padding:20px;margin-bottom:16px;}
            h1{color:#FF1744;letter-spacing:3px;margin:0 0 6px;}
            input,button{padding:10px 14px;border-radius:10px;border:1px solid #333;background:#191919;color:#fff;}
            button{background:linear-gradient(90deg,#FF1744,#D500F9);border:none;font-weight:600;cursor:pointer;}
            #log{height:220px;overflow-y:auto;background:#0a0a0a;padding:12px;border-radius:10px;font-family:monospace;font-size:13px;}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>MYRA PC COMPANION</h1>
            <div style="color:#999">Connected endpoint: ws://$host:$port/ws (local network only; use trusted Wi-Fi; OTP forwarding blocked)</div>
          </div>
          <div class="card">
            <input id="name" placeholder="PC Name" value="My PC"/>
            <input id="pin" placeholder="6-digit PIN"/>
            <button onclick="pair()">Connect & Pair</button>
            <div id="status" style="margin-top:8px;color:#40C4FF">Not connected</div>
          </div>
          <div class="card">
            <input id="cmd" placeholder="Send command or message to MYRA..." style="width:70%"/>
            <button onclick="sendCmd()">Send</button>
            <div id="log" style="margin-top:12px"></div>
          </div>
          <script>
            let ws;
            function log(m){
              const d=document.getElementById('log');
              const row=document.createElement('div');
              row.textContent=m;
              d.appendChild(row);
              d.scrollTop=d.scrollHeight;
            }
            function pair(){
              ws=new WebSocket('ws://'+location.host+'/ws');
              ws.onopen=()=>{ws.send(JSON.stringify({type:'auth',pin:document.getElementById('pin').value,clientName:document.getElementById('name').value}));};
              ws.onmessage=(e)=>{const j=JSON.parse(e.data);log('['+j.type+'] '+JSON.stringify(j));if(j.type==='auth_ok')document.getElementById('status').textContent='Authenticated';};
              ws.onclose=()=>{document.getElementById('status').textContent='Disconnected';};
            }
            function sendCmd(){
              const t=document.getElementById('cmd').value;
              if(ws&&t){ws.send(JSON.stringify({type:'command',text:t}));log('PC -> '+t);document.getElementById('cmd').value='';}
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_PIN_ATTEMPTS = 5
        private const val LOCKOUT_MS = 60_000L
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        private const val MAX_HTTP_HEADER_BYTES = 16_384
        private const val MAX_WEBSOCKET_FRAME_BYTES = 1_048_576L
        private const val MAX_ACTIVE_CONNECTIONS = 4
        private const val MAX_COMMAND_CHARS = 4_000
        private const val MAX_BROADCAST_CHARS = 16_384
    }
}
