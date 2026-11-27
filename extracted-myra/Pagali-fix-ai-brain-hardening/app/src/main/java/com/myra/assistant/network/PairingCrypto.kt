package com.myra.assistant.network

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PairingCrypto {

    private val random = SecureRandom()
    private const val PBKDF2_ITERATIONS = 120_000
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    fun generateSixDigitPin(): String {
        val code = 100000 + random.nextInt(900000)
        return code.toString()
    }

    fun generateRandomHexKey(bytes: Int = 32): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun deriveKeyHexFromPin(pin: String, salt: String = "MYRA_PAIR_V1"): String {
        require(pin.isNotBlank()) { "Pairing PIN is required." }
        require(salt.isNotBlank()) { "Pairing challenge salt is required." }
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
                .joinToString("") { "%02x".format(it) }
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptMessage(keyHex: String, nonce: String, plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(nonce.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decryptMessage(keyHex: String, nonce: String, payload: String): String {
        val packed = Base64.getDecoder().decode(payload)
        require(packed.size >= GCM_IV_BYTES + GCM_TAG_BYTES) { "Encrypted payload is too short." }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(nonce.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun hmacSha256Hex(keyHex: String, message: String): String {
        val keyBytes = hexToBytes(keyHex)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    fun getLocalWifiIpAddress(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress.orEmpty()
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
            "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return clean.toByteArray(Charsets.UTF_8)
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }
}
