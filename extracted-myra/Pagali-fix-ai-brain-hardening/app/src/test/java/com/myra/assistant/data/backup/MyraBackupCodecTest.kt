package com.myra.assistant.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MyraBackupCodecTest {
    private val passphrase = "myra-backup-passphrase".toCharArray()

    @Test
    fun encryptedArchiveRoundTripsAllSupportedUserData() {
        val archive = MyraBackupCodec.Archive(
            exportedAt = 1_800_000_000_000L,
            chatMessages = listOf(MyraBackupCodec.ChatRecord("नमस्ते MYRA", true, 123L, "text")),
            stableMemories = listOf(
                MyraBackupCodec.StableMemoryRecord("preference", "language", "Hindi", "मुझे हिंदी पसंद है", .99f, 10L, 11L, true),
            ),
            emotionalMemories = listOf(
                MyraBackupCodec.EmotionalMemoryRecord("event-1", "happy", "आज अच्छा लगा", "मैं खुश हूँ", .95f, 12L),
            ),
            pendingMemories = listOf(
                MyraBackupCodec.PendingMemoryRecord("goal", "goal-1", "पढ़ाई", "मुझे पढ़ना है", .9f, 13L),
            ),
            actionHistory = listOf(MyraBackupCodec.ActionRecord("torch", "enabled", true, "Torch changed", 14L)),
        )

        val encrypted = MyraBackupCodec.encrypt(archive, passphrase)
        val restored = MyraBackupCodec.decrypt(encrypted, passphrase)

        assertEquals(archive, restored)
        org.junit.Assert.assertFalse(encrypted.toString(Charsets.UTF_8).contains("नमस्ते MYRA"))
        org.junit.Assert.assertFalse(encrypted.toString(Charsets.UTF_8).contains("Hindi"))
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val encrypted = MyraBackupCodec.encrypt(
            MyraBackupCodec.Archive(exportedAt = 1L),
            passphrase,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MyraBackupCodec.decrypt(encrypted, "incorrect-passphrase".toCharArray())
        }
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = MyraBackupCodec.encrypt(
            MyraBackupCodec.Archive(exportedAt = 1L),
            passphrase,
        )
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            MyraBackupCodec.decrypt(encrypted, passphrase)
        }
    }

    @Test
    fun shortPassphraseIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MyraBackupCodec.encrypt(MyraBackupCodec.Archive(exportedAt = 1L), "short".toCharArray())
        }
    }

    @Test
    fun unsupportedContainerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MyraBackupCodec.decrypt("not-a-myra-backup".toByteArray(), passphrase)
        }
    }
}
