package com.myra.assistant.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Password-encrypted, versioned backup container.
 *
 * This codec deliberately handles only the archive bytes. Database reads/writes and
 * Android document-picker integration must be performed by the repository/UI layer.
 */
object MyraBackupCodec {
    private const val MAGIC = "MYRA-BACKUP"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
    private const val MAX_CONTAINER_BYTES = 28 * 1024 * 1024
    private val random = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class Archive(
        val schemaVersion: Int = VERSION,
        val exportedAt: Long,
        val chatMessages: List<ChatRecord> = emptyList(),
        val stableMemories: List<StableMemoryRecord> = emptyList(),
        val emotionalMemories: List<EmotionalMemoryRecord> = emptyList(),
        val pendingMemories: List<PendingMemoryRecord> = emptyList(),
        val actionHistory: List<ActionRecord> = emptyList(),
    )

    @Serializable
    data class ChatRecord(val text: String, val isUser: Boolean, val timestamp: Long, val source: String)

    @Serializable
    data class StableMemoryRecord(
        val category: String, val memoryKey: String, val value: String, val sourceText: String,
        val confidence: Float, val createdAt: Long, val updatedAt: Long, val userConfirmed: Boolean,
    )

    @Serializable
    data class EmotionalMemoryRecord(
        val memoryKey: String, val emotion: String, val context: String, val sourceText: String,
        val confidence: Float, val createdAt: Long,
    )

    @Serializable
    data class PendingMemoryRecord(
        val category: String, val memoryKey: String, val value: String, val sourceText: String,
        val confidence: Float, val proposedAt: Long,
    )

    @Serializable
    data class ActionRecord(
        val commandType: String, val parameterNames: String, val success: Boolean,
        val resultSummary: String, val timestamp: Long,
    )

    @Serializable
    private data class Envelope(
        val magic: String,
        val version: Int,
        val iterations: Int,
        val salt: String,
        val nonce: String,
        val ciphertext: String,
    )

    fun encrypt(archive: Archive, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters." }
        require(archive.schemaVersion == VERSION) { "Unsupported backup schema." }
        val plain = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
        try {
            require(plain.size <= MAX_ARCHIVE_BYTES) { "Backup is too large." }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipherText = crypt(Cipher.ENCRYPT_MODE, plain, passphrase, salt, nonce)
            val envelope = Envelope(
                magic = MAGIC,
                version = VERSION,
                iterations = ITERATIONS,
                salt = Base64.getEncoder().encodeToString(salt),
                nonce = Base64.getEncoder().encodeToString(nonce),
                ciphertext = Base64.getEncoder().encodeToString(cipherText),
            )
            return json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): Archive {
        require(passphrase.size >= 12) { "Backup passphrase must contain at least 12 characters." }
        require(container.isNotEmpty() && container.size <= MAX_CONTAINER_BYTES) { "Invalid backup size." }
        val envelope = runCatching {
            json.decodeFromString<Envelope>(container.toString(Charsets.UTF_8))
        }.getOrElse { throw IllegalArgumentException("Backup file is invalid or damaged.", it) }
        require(envelope.magic == MAGIC && envelope.version == VERSION) { "Unsupported backup format." }
        require(envelope.iterations == ITERATIONS) { "Unsupported backup encryption settings." }
        val salt = decodeBase64(envelope.salt, SALT_BYTES)
        val nonce = decodeBase64(envelope.nonce, NONCE_BYTES)
        val ciphertext = runCatching { Base64.getDecoder().decode(envelope.ciphertext) }
            .getOrElse { throw IllegalArgumentException("Backup file is invalid or damaged.", it) }
        require(ciphertext.size in 16..(MAX_ARCHIVE_BYTES + 16)) { "Invalid backup payload size." }
        val plain = try {
            crypt(Cipher.DECRYPT_MODE, ciphertext, passphrase, salt, nonce)
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or backup integrity check failed.", error)
        }
        try {
            require(plain.size <= MAX_ARCHIVE_BYTES) { "Backup is too large." }
            val archive = runCatching {
                json.decodeFromString<Archive>(plain.toString(Charsets.UTF_8))
            }.getOrElse { throw IllegalArgumentException("Backup contents are invalid.", it) }
            require(archive.schemaVersion == VERSION) { "Unsupported backup schema." }
            return archive
        } finally {
            plain.fill(0)
        }
    }

    private fun decodeBase64(value: String, expectedSize: Int): ByteArray {
        val bytes = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Backup file is invalid or damaged.", it) }
        require(bytes.size == expectedSize) { "Backup file is invalid or damaged." }
        return bytes
    }

    private fun crypt(mode: Int, input: ByteArray, passphrase: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("$MAGIC:$VERSION:$ITERATIONS".toByteArray(Charsets.UTF_8))
            cipher.doFinal(input)
        } finally {
            keyBytes.fill(0)
        }
    }
}
