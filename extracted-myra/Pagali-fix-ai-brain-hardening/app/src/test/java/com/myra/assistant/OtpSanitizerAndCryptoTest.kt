package com.myra.assistant

import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.ToolRegistry
import com.myra.assistant.model.AppCommand
import com.myra.assistant.network.PairingCrypto
import com.myra.assistant.security.OtpSanitizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpSanitizerAndCryptoTest {

    @Test
    fun `detects and redacts otp messages locally`() {
        val sms = "Your HDFC Bank OTP is 482910 for transaction. Do not share."
        assertTrue(OtpSanitizer.containsOtp(sms))
        assertEquals("482910", OtpSanitizer.extractOtpCode(sms))
        assertEquals("[REDACTED_OTP_CONTENT]", OtpSanitizer.redactIfSensitive(sms))

        val normal = "Hey, meeting is at 4820 Pine Street at 5pm"
        assertFalse(OtpSanitizer.containsOtp(normal))
        assertNull(OtpSanitizer.extractOtpCode(normal))
        assertEquals(normal, OtpSanitizer.redactIfSensitive(normal))
    }

    @Test
    fun `detects Hindi OTP keywords and Devanagari digits`() {
        val hindiOtp = "आपका ओटीपी १२३४५६ है।"
        assertTrue(OtpSanitizer.containsOtp(hindiOtp))
        assertEquals("123456", OtpSanitizer.extractOtpCode(hindiOtp))
        assertEquals("[REDACTED_OTP_CONTENT]", OtpSanitizer.redactIfSensitive(hindiOtp))
    }

    @Test
    fun `verifies hmac sha256 pairing signature`() {
        val pin = "654321"
        val key = PairingCrypto.deriveKeyHexFromPin(pin)
        val payload = "nonce123:Hello MYRA"
        val sig = PairingCrypto.hmacSha256Hex(key, payload)
        val expected = PairingCrypto.hmacSha256Hex(PairingCrypto.deriveKeyHexFromPin("654321"), payload)
        val wrong = PairingCrypto.hmacSha256Hex(PairingCrypto.deriveKeyHexFromPin("111111"), payload)

        assertTrue(PairingCrypto.constantTimeEquals(sig, expected))
        assertFalse(PairingCrypto.constantTimeEquals(sig, wrong))
    }

    @Test
    fun `MYRA Connect encrypts payloads and rejects tampering`() {
        val nonce = "a1b2c3d4e5f6"
        val key = PairingCrypto.deriveKeyHexFromPin("654321", nonce)
        val plaintext = "Private MYRA message"
        val encrypted = PairingCrypto.encryptMessage(key, nonce, plaintext)

        assertFalse(encrypted.contains(plaintext))
        assertEquals(plaintext, PairingCrypto.decryptMessage(key, nonce, encrypted))
        assertFalse(runCatching {
            PairingCrypto.decryptMessage(key, "different-nonce", encrypted)
        }.isSuccess)
    }

    @Test
    fun `tool registry converts gemini function calls to AppCommands`() {
        val declarations = ToolRegistry.geminiToolDeclarationsJson()
        assertTrue(declarations.length() >= 14)

        val callCmd = ToolRegistry.toAppCommand("make_call", JSONObject().put("target", "Mom"))
        assertNotNull(callCmd)
        assertEquals(AppCommand.CALL, callCmd?.type)
        assertEquals("Mom", callCmd?.params?.get("target"))

        val torchCmd = ToolRegistry.toAppCommand("set_flashlight", JSONObject().put("enabled", false))
        assertEquals(AppCommand.FLASHLIGHT_OFF, torchCmd?.type)

        val cameraCmd = ToolRegistry.toAppCommand("open_camera_vision", JSONObject())
        assertEquals(AppCommand.CAMERA_VISION, cameraCmd?.type)
    }

    @Test
    fun `audio rms computes zero for silence and positive for signal`() {
        val silence = ByteArray(1024)
        assertEquals(0f, AudioEngine.calculateNormalizedRms(silence, silence.size), 0.0001f)

        val tone = ByteArray(1024) { idx -> if (idx % 2 == 0) 0x00 else 0x20 }
        assertTrue(AudioEngine.calculateNormalizedRms(tone, tone.size) > 0f)
    }
}
