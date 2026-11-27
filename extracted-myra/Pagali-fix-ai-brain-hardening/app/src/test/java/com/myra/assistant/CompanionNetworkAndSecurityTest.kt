package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.preferences.SecureSettingsRepository
import com.myra.assistant.data.repository.MyraRepository
import com.myra.assistant.model.ConnectionMode
import com.myra.assistant.model.ConnectionState
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.network.MyraConnectManager
import com.myra.assistant.network.PairingCrypto
import com.myra.assistant.network.PcCompanionServer
import com.myra.assistant.security.OtpSanitizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionNetworkAndSecurityTest {

    private fun createInMemoryDb(context: Context): MyraDatabase {
        return Room.inMemoryDatabaseBuilder(context, MyraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun awaitPcServerPort(server: PcCompanionServer): Pair<Int, String> {
        server.start(0)
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            val st = server.state.value
            if (st.isRunning && st.port > 0 && st.pairingPin.length == 6) {
                return st.port to st.pairingPin
            }
            Thread.sleep(25L)
        }
        throw AssertionError("PcCompanionServer failed to start within 5s")
    }

    @Test
    fun `pc companion websocket enforces pin auth delivers commands blocks otp and serves safe dashboard`() {
        val receivedCommands = CopyOnWriteArrayList<Pair<String, String>>()
        val authedClients = CopyOnWriteArrayList<String>()
        val server = PcCompanionServer(
            onCommandFromPc = { name, cmd -> receivedCommands.add(name to cmd) },
            onClientAuthenticated = { name, _ -> authedClients.add(name) },
            onStatusLog = { _, _ -> },
        )

        val okClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        var ws: WebSocket? = null
        try {
            val (port, pin) = awaitPcServerPort(server)

            // 1. Verify HTTP Dashboard HTML has no unsafe innerHTML sink
            val conn = (URL("http://${server.state.value.hostIp}:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            assertEquals(200, conn.responseCode)
            val html = conn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("MYRA PC COMPANION"))
            assertFalse("Dashboard HTML must not use innerHTML", html.contains("innerHTML"))
            assertTrue(html.contains("row.textContent=m"))
            conn.disconnect()

            // 2. Connect real WebSocket client
            val messages = CopyOnWriteArrayList<JSONObject>()
            val authRequiredLatch = CountDownLatch(1)
            val rejectedUnauthLatch = CountDownLatch(1)
            val authOkLatch = CountDownLatch(1)
            val cmdAckLatch = CountDownLatch(1)
            val otpBlockedLatch = CountDownLatch(1)
            val broadcastLatch = CountDownLatch(1)

            ws = okClient.newWebSocket(
                Request.Builder().url("ws://${server.state.value.hostIp}:$port/ws").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = JSONObject(text)
                        messages.add(json)
                        when (json.optString("type")) {
                            "auth_required" -> {
                                if (authRequiredLatch.count > 0) authRequiredLatch.countDown()
                                else rejectedUnauthLatch.countDown()
                            }
                            "auth_ok" -> authOkLatch.countDown()
                            "ack" -> cmdAckLatch.countDown()
                            "blocked" -> otpBlockedLatch.countDown()
                            "telemetry" -> broadcastLatch.countDown()
                        }
                    }
                },
            )

            assertTrue("Expected auth_required banner", authRequiredLatch.await(4, TimeUnit.SECONDS))

            // Unauthenticated command must be rejected
            ws.send(JSONObject().put("type", "command").put("text", "torch on karo").toString())
            assertTrue("Expected unauthenticated command rejection", rejectedUnauthLatch.await(4, TimeUnit.SECONDS))
            assertTrue(receivedCommands.isEmpty())

            // Valid 6-digit PIN auth succeeds
            ws.send(
                JSONObject()
                    .put("type", "auth")
                    .put("pin", pin)
                    .put("clientName", "AuditWorkstation")
                    .toString(),
            )
            assertTrue("Expected auth_ok", authOkLatch.await(4, TimeUnit.SECONDS))
            // The WebSocket auth_ok frame can reach the client just before the
            // authentication callback finishes on the server thread. Wait for
            // that callback instead of asserting immediately (avoids a race).
            val authCallbackDeadline = System.currentTimeMillis() + 2000L
            while (!authedClients.contains("AuditWorkstation") && System.currentTimeMillis() < authCallbackDeadline) {
                Thread.sleep(10L)
            }
            assertTrue("Expected authentication callback", authedClients.contains("AuditWorkstation"))

            // Valid command delivers & receives ack
            ws.send(JSONObject().put("type", "command").put("text", "battery kitna hai").toString())
            assertTrue("Expected command ack", cmdAckLatch.await(4, TimeUnit.SECONDS))
            assertTrue(receivedCommands.any { it.first == "AuditWorkstation" && it.second == "battery kitna hai" })

            // OTP command is blocked
            ws.send(JSONObject().put("type", "command").put("text", "My login code is 918273").toString())
            assertTrue("Expected OTP command block", otpBlockedLatch.await(4, TimeUnit.SECONDS))
            assertFalse(receivedCommands.any { it.second.contains("918273") })

            // Outbound broadcast blocks OTP payloads and delivers telemetry
            server.broadcastJson("myra_message", JSONObject().put("text", "Your OTP is 445566"))
            server.broadcastJson("telemetry", JSONObject().put("battery", 87))
            assertTrue("Expected telemetry broadcast", broadcastLatch.await(4, TimeUnit.SECONDS))
            assertFalse(messages.any { it.toString().contains("445566") })
        } finally {
            ws?.close(1000, "done")
            okClient.dispatcher.executorService.shutdown()
            server.stop()
        }
    }

    @Test
    fun `pc companion locks out ip after 5 failed pin attempts`() {
        val server = PcCompanionServer(
            onCommandFromPc = { _, _ -> },
            onClientAuthenticated = { _, _ -> },
            onStatusLog = { _, _ -> },
        )
        val okClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        var ws: WebSocket? = null
        try {
            val (port, pin) = awaitPcServerPort(server)
            val wrongPin = if (pin == "000000") "111111" else "000000"

            val readyLatch = CountDownLatch(1)
            val lockedLatch = CountDownLatch(1)
            val attemptResponses = CopyOnWriteArrayList<String>()

            ws = okClient.newWebSocket(
                Request.Builder().url("ws://${server.state.value.hostIp}:$port/ws").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val t = JSONObject(text).optString("type")
                        attemptResponses.add(t)
                        if (t == "auth_required" && readyLatch.count > 0) {
                            readyLatch.countDown()
                        } else if (t == "auth_locked") {
                            lockedLatch.countDown()
                        }
                    }
                },
            )
            assertTrue(readyLatch.await(4, TimeUnit.SECONDS))

            repeat(5) {
                ws.send(JSONObject().put("type", "auth").put("pin", wrongPin).put("clientName", "Attacker").toString())
                Thread.sleep(30L)
            }
            // 6th attempt even with VALID PIN must be locked out
            ws.send(JSONObject().put("type", "auth").put("pin", pin).put("clientName", "Attacker").toString())
            assertTrue("Expected IP lockout after 5 bad PIN attempts", lockedLatch.await(4, TimeUnit.SECONDS))
            assertFalse(attemptResponses.contains("auth_ok"))
        } finally {
            ws?.close(1000, "done")
            okClient.dispatcher.executorService.shutdown()
            server.stop()
        }
    }

    @Test
    fun `myra connect tcp hmac challenge response pairs valid pin and rejects tampered hmac`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = createInMemoryDb(context)
        val repo = MyraRepository(db)
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        val hostManager = MyraConnectManager(
            context = context,
            repository = repo,
            onRemoteMessageReceived = { from, txt -> received.add(from to txt) },
        )

        try {
            hostManager.start()
            val deadline = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline) {
                val st = hostManager.state.value
                if (st.isHosting && st.localPort > 0 && st.pairingPin.length == 6) break
                Thread.sleep(25L)
            }
            val hostState = hostManager.state.value
            assertTrue("MyraConnectManager must bind local socket", hostState.isHosting && hostState.localPort > 0)

            // 1. Tampered HMAC attempt fails
            Socket(hostState.localIp, hostState.localPort).use { sock ->
                sock.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val challenge = JSONObject(reader.readLine())
                val nonce = challenge.getString("nonce")
                assertTrue(nonce.isNotEmpty())

                writer.write(
                    JSONObject().apply {
                        put("type", "pair_and_send")
                        put("senderId", "rogue-peer")
                        put("senderName", "Rogue")
                        put("nonce", nonce)
                        put("message", "Hello")
                        put("hmac", "deadbeef".repeat(8))
                    }.toString(),
                )
                writer.newLine()
                writer.flush()
                val resp = JSONObject(reader.readLine())
                assertFalse(resp.optBoolean("ok", true))
            }

            // 2. OTP payload is rejected
            val pairDone = CountDownLatch(1)
            var otpOk = true
            var otpDetail = ""
            hostManager.pairAndSendTest(
                peerHost = hostState.localIp,
                peerPort = hostState.localPort,
                pin = hostState.pairingPin,
                peerName = "LocalPeer",
                messageText = "Bank OTP 739104 do not share",
            ) { ok, detail ->
                otpOk = ok
                otpDetail = detail
                pairDone.countDown()
            }
            assertTrue(pairDone.await(4, TimeUnit.SECONDS))
            assertFalse(otpOk)
            assertTrue(otpDetail.contains("OTP", ignoreCase = true))

            // 3. Valid PIN + HMAC succeeds & persists PairedDeviceEntity
            val okLatch = CountDownLatch(1)
            var validOk = false
            hostManager.pairAndSendTest(
                peerHost = hostState.localIp,
                peerPort = hostState.localPort,
                pin = hostState.pairingPin,
                peerName = "TrustedPhone",
                messageText = "Sync hello from second device",
            ) { ok, _ ->
                validOk = ok
                okLatch.countDown()
            }
            assertTrue(okLatch.await(4, TimeUnit.SECONDS))
            assertTrue(validOk)
            assertTrue(received.any { it.second == "Sync hello from second device" })
            runBlocking {
                val paired = repo.pairedDevices.first()
                assertTrue(paired.isNotEmpty())
            }
        } finally {
            hostManager.stop()
            db.close()
        }
    }

    @Test
    fun `myra connect locks out ip after 5 failed hmac attempts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = createInMemoryDb(context)
        val repo = MyraRepository(db)
        val hostManager = MyraConnectManager(
            context = context,
            repository = repo,
            onRemoteMessageReceived = { _, _ -> },
        )
        try {
            hostManager.start()
            val deadline = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline) {
                val st = hostManager.state.value
                if (st.isHosting && st.localPort > 0 && st.pairingPin.length == 6) break
                Thread.sleep(25L)
            }
            val hostState = hostManager.state.value

            repeat(5) {
                Socket(hostState.localIp, hostState.localPort).use { sock ->
                    sock.soTimeout = 3000
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                    val challenge = JSONObject(reader.readLine())
                    val nonce = challenge.getString("nonce")
                    writer.write(
                        JSONObject().apply {
                            put("type", "pair_and_send")
                            put("senderId", "brute-peer")
                            put("senderName", "Brute")
                            put("nonce", nonce)
                            put("message", "Hello")
                            put("hmac", "bad0".repeat(16))
                        }.toString(),
                    )
                    writer.newLine()
                    writer.flush()
                    val resp = JSONObject(reader.readLine())
                    assertFalse(resp.optBoolean("ok", true))
                }
            }

            // 6th attempt even with valid PIN must be locked out immediately
            val lockedLatch = CountDownLatch(1)
            var ok6 = true
            var detail6 = ""
            hostManager.pairAndSendTest(
                peerHost = hostState.localIp,
                peerPort = hostState.localPort,
                pin = hostState.pairingPin,
                peerName = "LockedClient",
                messageText = "Should be locked",
            ) { ok, detail ->
                ok6 = ok
                detail6 = detail
                lockedLatch.countDown()
            }
            assertTrue(lockedLatch.await(4, TimeUnit.SECONDS))
            assertFalse(ok6)
            assertTrue(detail6.contains("Locked", ignoreCase = true))
        } finally {
            hostManager.stop()
            db.close()
        }
    }

    @Test
    fun `gemini live client redacts secrets validates wss proxy policy and sends setup over proxy`() {
        val raw = "wss://generativelanguage.googleapis.com/ws?key=AIzaSySecret12345 Authorization: Bearer tok_abc_999"
        val clean = GeminiLiveClient.redactSecrets(raw)
        assertFalse(clean.contains("AIzaSySecret12345"))
        assertFalse(clean.contains("tok_abc_999"))
        assertTrue(clean.contains("?key=[REDACTED]"))
        assertTrue(clean.contains("Bearer [REDACTED]"))

        // Rejects empty direct key
        var directErr: String? = null
        val directClient = GeminiLiveClient { MyraSettings(connectionMode = ConnectionMode.DIRECT_USER_KEY, apiKey = "") }
        directClient.onConnectionStateChanged = { state, msg ->
            if (state == ConnectionState.ERROR) directErr = msg
        }
        directClient.connect()
        assertNotNull(directErr)
        directClient.release()

        // Rejects unencrypted ws:// on remote host
        var proxySchemeErr: String? = null
        val insecureProxyClient = GeminiLiveClient {
            MyraSettings(connectionMode = ConnectionMode.BACKEND_PROXY, proxyUrl = "ws://remote.myra.app/ws", proxyAuthToken = "test-token-for-policy-check")
        }
        insecureProxyClient.onConnectionStateChanged = { state, msg ->
            if (state == ConnectionState.ERROR) proxySchemeErr = msg
        }
        insecureProxyClient.connect()
        assertTrue(proxySchemeErr.orEmpty().contains("wss://"))
        insecureProxyClient.release()

        // Connects to local loopback WebSocket proxy with Bearer token & sends setup payload (Aoede default)
        val receivedSetupLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        var capturedAuthHeader = ""
        var capturedPath = ""
        var capturedSetupJson: JSONObject? = null

        val mockProxySocket = ServerSocket(0)
        val proxyThread = Thread {
            try {
                mockProxySocket.accept().use { sock ->
                    val input = sock.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    capturedPath = headers.firstOrNull().orEmpty()
                    capturedAuthHeader = headers.firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }.orEmpty()
                    val wsKey = headers.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                        .substringAfter(":").trim()
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                            .digest("${wsKey}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
                    )
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                    )
                    out.flush()

                    // Read first masked text frame (setup JSON)
                    val rawStream = sock.getInputStream()
                    val b1 = rawStream.read()
                    val b2 = rawStream.read()
                    if (b1 != -1 && b2 != -1) {
                        var len = (b2 and 0x7F)
                        if (len == 126) {
                            len = ((rawStream.read() shl 8) or rawStream.read())
                        }
                        val mask = ByteArray(4)
                        rawStream.readNBytes(mask, 0, 4)
                        val data = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val r = rawStream.read(data, off, len - off)
                            if (r <= 0) break
                            off += r
                        }
                        for (i in data.indices) {
                            data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }
                        capturedSetupJson = JSONObject(String(data, Charsets.UTF_8))
                        receivedSetupLatch.countDown()

                        // Send setupComplete back
                        val respBytes = """{"setupComplete":{}}""".toByteArray()
                        out.write(0x81)
                        out.write(respBytes.size)
                        out.write(respBytes)
                        out.flush()
                        Thread.sleep(300L)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { start() }

        val proxyClient = GeminiLiveClient {
            MyraSettings(
                connectionMode = ConnectionMode.BACKEND_PROXY,
                proxyUrl = "ws://127.0.0.1:${mockProxySocket.localPort}/ws",
                proxyAuthToken = "proxy_secret_token_test",
            )
        }
        proxyClient.onSetupComplete = { connectedLatch.countDown() }

        try {
            proxyClient.connect()
            assertTrue("Proxy server should receive setup payload", receivedSetupLatch.await(4, TimeUnit.SECONDS))
            assertTrue("Client should receive setupComplete", connectedLatch.await(4, TimeUnit.SECONDS))
            assertFalse("Proxy path must NOT contain API key", capturedPath.contains("key="))
            assertTrue(capturedAuthHeader.contains("Bearer proxy_secret_token_test"))

            val setup = capturedSetupJson!!.getJSONObject("setup")
            val generationConfig = setup.getJSONObject("generationConfig")
            val voiceName = generationConfig.getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
            assertEquals("Aoede", voiceName)
            assertEquals("models/gemini-3.8-live", setup.getString("model"))
            assertTrue(setup.has("sessionResumption"))
            assertTrue(setup.has("contextWindowCompression"))
            assertFalse(generationConfig.has("temperature"))
            assertTrue(setup.getJSONArray("tools").length() == 1)
        } finally {
            proxyClient.release()
            runCatching { mockProxySocket.close() }
            proxyThread.join(1000)
        }
    }

    @Test
    fun `room repository redacts otp before persistence and secure settings starts without bundled key`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = SecureSettingsRepository(context)
        assertEquals("", settingsRepo.getUserEnteredApiKey())
        assertEquals("", settingsRepo.settings.value.apiKey)

        val db = createInMemoryDb(context)
        val repo = MyraRepository(db)
        try {
            repo.addChatMessage("My security code is 654321 please read", isUser = true, source = "text")
            repo.log("INFO", "Audit", "Detected one time password 987654")

            val msgs = repo.chatMessages.first()
            val logs = repo.diagnosticLogs.first()
            assertTrue(msgs.none { it.text.contains("654321") })
            assertTrue(logs.none { it.message.contains("987654") })
            assertTrue(OtpSanitizer.containsOtp("Your confirmation code is 441122"))
        } finally {
            db.close()
        }
    }
}
