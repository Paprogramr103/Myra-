package com.myra.assistant.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the random SQLCipher passphrase encrypted by an Android Keystore AES-256-GCM key.
 *
 * getOrCreateForMigration() is intentionally called only by the one-time plaintext-to-encrypted
 * migration. Normal database startup must use getExistingKey(); it must never silently create a
 * replacement key for an already encrypted database.
 */
class MyraDatabaseKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getExistingKey(): ByteArray? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
            ?: throw IllegalStateException("MYRA database key envelope is incomplete")
        return decryptEnvelope(iv, ciphertext)
    }

    @Synchronized
    fun getOrCreateForMigration(): ByteArray {
        val savedIv = preferences.getString(KEY_IV, null)
        val savedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
        if (savedIv != null || savedCiphertext != null) {
            if (savedIv == null || savedCiphertext == null) {
                throw IllegalStateException("MYRA database key envelope is incomplete")
            }
            return decryptEnvelope(savedIv, savedCiphertext)
        }

        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            val encrypted = cipher.doFinal(databaseKey)
            val saved = preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
            if (!saved) {
                throw IllegalStateException("Could not persist MYRA database key envelope")
            }
            return databaseKey
        } catch (error: Exception) {
            databaseKey.fill(0)
            throw IllegalStateException("Could not securely provision MYRA database key", error)
        }
    }

    private fun decryptEnvelope(encodedIv: String, encodedCiphertext: String): ByteArray {
        try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES) { "Invalid database key IV" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext).also {
                check(it.size == DATABASE_KEY_BYTES) { "Invalid database key length" }
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "MYRA database key could not be unlocked. Existing database data was not replaced.",
                error,
            )
        }
    }

    private fun getExistingWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: throw IllegalStateException("MYRA database wrapping key is missing; refusing to create a replacement")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "myra_room_database_wrapping_key_v1"
        const val PREFERENCES_NAME = "myra_database_key_envelope"
        const val KEY_IV = "database_key_iv"
        const val KEY_CIPHERTEXT = "database_key_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATABASE_KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
