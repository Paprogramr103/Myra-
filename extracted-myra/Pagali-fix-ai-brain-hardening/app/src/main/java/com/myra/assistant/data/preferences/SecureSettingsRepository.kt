package com.myra.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.myra.assistant.model.ConnectionMode
import com.myra.assistant.model.GeminiModelOption
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.PersonalityMode
import com.myra.assistant.model.PrimeContact
import com.myra.assistant.model.VoiceOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local storage for MYRA configuration.
 * Uses Android Keystore AES-256 EncryptedSharedPreferences strictly for user-provided credentials
 * (never pre-seeded with bundled APK API keys).
 */
class SecureSettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var secureStorageOperational = false
    val isSecureStorageAvailable: Boolean get() = secureStorageOperational
    private val prefs: SharedPreferences
    private val securePrefs: SharedPreferences?

    init {
        val encryptedPrefsCandidate = runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()

        val fallbackPrefs = appContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
        val usableEncryptedPrefs = encryptedPrefsCandidate?.takeIf { candidate ->
            runCatching { migrateLegacyNonSecretPreferences(candidate, fallbackPrefs) }.isSuccess
        }

        securePrefs = usableEncryptedPrefs
        secureStorageOperational = usableEncryptedPrefs != null
        prefs = usableEncryptedPrefs ?: fallbackPrefs

        // Never read or import credentials from the unencrypted fallback store.
        appContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE).edit()
            .remove(KEY_API_KEY)
            .remove(KEY_PROXY_TOKEN)
            .apply()
        migrateLegacyPrimeContactIfNeeded()
    }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<MyraSettings> = _settings.asStateFlow()

    fun getUserEnteredApiKey(): String = getSecureString(KEY_API_KEY).trim()

    private fun getSecureString(key: String): String {
        if (!secureStorageOperational) return ""
        return runCatching { securePrefs?.getString(key, "").orEmpty() }
            .onFailure { secureStorageOperational = false }
            .getOrDefault("")
    }

    fun saveSettings(
        connectionMode: ConnectionMode,
        apiKey: String,
        proxyUrl: String,
        proxyAuthToken: String,
        userName: String,
        model: GeminiModelOption,
        voice: VoiceOption,
        personality: PersonalityMode,
        pcCompanionPort: Int,
    ) {
        // Non-secret preferences may use the app-private fallback. Credentials may not.
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, connectionMode.key)
            .putString(KEY_PROXY_URL, proxyUrl.trim())
            .putString(KEY_USER_NAME, userName.trim().ifEmpty { "Sir" })
            .putString(KEY_GEMINI_MODEL, model.modelId)
            .putString(KEY_GEMINI_VOICE, voice.voiceId)
            .putString(KEY_PERSONALITY_MODE, personality.key)
            .putInt(KEY_PC_COMPANION_PORT, pcCompanionPort.coerceIn(1024, 65535))
            .apply()

        // If Android Keystore encryption is unavailable, do not persist either secret.
        val secureStore = securePrefs
        if (secureStorageOperational && secureStore != null) {
            val saved = runCatching {
                secureStore.edit()
                    .putString(KEY_API_KEY, apiKey.trim())
                    .putString(KEY_PROXY_TOKEN, proxyAuthToken.trim())
                    .commit()
            }.getOrDefault(false)
            if (!saved) {
                secureStorageOperational = false
                runCatching { secureStore.edit().remove(KEY_API_KEY).remove(KEY_PROXY_TOKEN).commit() }
            }
        } else {
            runCatching { secureStore?.edit()?.remove(KEY_API_KEY)?.remove(KEY_PROXY_TOKEN)?.commit() }
        }
        _settings.value = readSettings()
    }

    fun savePairedDeviceKey(deviceId: String, keyHex: String): Boolean {
        val store = securePrefs?.takeIf { secureStorageOperational } ?: return false
        if (deviceId.isBlank() || keyHex.isBlank()) return false
        val saved = runCatching {
            store.edit()
                .putString("$KEY_PAIRED_DEVICE_KEY_PREFIX$deviceId", keyHex)
                .commit()
        }.getOrDefault(false)
        if (!saved) secureStorageOperational = false
        return saved
    }

    fun getPairedDeviceKey(deviceId: String): String {
        if (deviceId.isBlank() || !secureStorageOperational) return ""
        return getSecureString("$KEY_PAIRED_DEVICE_KEY_PREFIX$deviceId")
    }

    fun removePairedDeviceKey(deviceId: String): Boolean {
        val store = securePrefs ?: return false
        if (deviceId.isBlank()) return false
        val removed = runCatching {
            store.edit().remove("$KEY_PAIRED_DEVICE_KEY_PREFIX$deviceId").commit()
        }.getOrDefault(false)
        if (!removed) secureStorageOperational = false
        return removed
    }

    fun clearStoredCredentials() {
        securePrefs?.let { store ->
            val cleared = runCatching {
                store.edit().remove(KEY_API_KEY).remove(KEY_PROXY_TOKEN).commit()
            }.getOrDefault(false)
            if (!cleared) secureStorageOperational = false
        }
        // Also scrub any credentials left by a previous unencrypted fallback build.
        appContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE).edit()
            .remove(KEY_API_KEY)
            .remove(KEY_PROXY_TOKEN)
            .apply()
        _settings.value = readSettings()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        _settings.value = readSettings()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        _settings.value = readSettings()
    }

    fun setCallMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_MONITOR_ENABLED, enabled).apply()
        _settings.value = readSettings()
    }

    fun addPrimeContact(name: String, number: String) {
        val trimmedName = name.trim()
        val trimmedNumber = number.trim()
        if (trimmedName.isEmpty() || trimmedNumber.isEmpty()) return
        val current = getPrimeContacts().toMutableList()
        current.add(PrimeContact(trimmedName, trimmedNumber))
        savePrimeContacts(current)
    }

    fun removePrimeContact(index: Int) {
        val current = getPrimeContacts().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            savePrimeContacts(current)
        }
    }

    fun getPrimeContacts(): List<PrimeContact> {
        val json = prefs.getString(KEY_PRIME_CONTACTS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name").trim()
                    val number = obj.optString("number").trim()
                    if (name.isNotEmpty() && number.isNotEmpty()) {
                        add(PrimeContact(name, number))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun savePrimeContacts(contacts: List<PrimeContact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(
                JSONObject().apply {
                    put("name", contact.name)
                    put("number", contact.number)
                }
            )
        }
        prefs.edit().putString(KEY_PRIME_CONTACTS_JSON, array.toString()).apply()
        _settings.value = readSettings()
    }

    private fun migrateLegacyNonSecretPreferences(
        target: SharedPreferences,
        legacy: SharedPreferences,
    ) {
        val editor = target.edit()
        val stringKeys = listOf(
            KEY_CONNECTION_MODE,
            KEY_PROXY_URL,
            KEY_USER_NAME,
            KEY_GEMINI_MODEL,
            KEY_GEMINI_VOICE,
            KEY_PERSONALITY_MODE,
            KEY_PRIME_CONTACTS_JSON,
            KEY_LEGACY_PRIME_NAME,
            KEY_LEGACY_PRIME_NUMBER,
        )
        stringKeys.forEach { key ->
            if (!target.contains(key) && legacy.contains(key)) {
                editor.putString(key, legacy.getString(key, null))
            }
        }
        val booleanKeys = listOf(
            KEY_WAKE_WORD_ENABLED,
            KEY_OVERLAY_ENABLED,
            KEY_CALL_MONITOR_ENABLED,
        )
        booleanKeys.forEach { key ->
            if (!target.contains(key) && legacy.contains(key)) {
                editor.putBoolean(key, legacy.getBoolean(key, false))
            }
        }
        if (!target.contains(KEY_PC_COMPANION_PORT) && legacy.contains(KEY_PC_COMPANION_PORT)) {
            editor.putInt(KEY_PC_COMPANION_PORT, legacy.getInt(KEY_PC_COMPANION_PORT, 8765))
        }
        editor.apply()
    }

    private fun migrateLegacyPrimeContactIfNeeded() {
        if (prefs.contains(KEY_PRIME_CONTACTS_JSON)) return
        val legacyName = prefs.getString(KEY_LEGACY_PRIME_NAME, null)?.trim().orEmpty()
        val legacyNumber = prefs.getString(KEY_LEGACY_PRIME_NUMBER, null)?.trim().orEmpty()
        if (legacyName.isNotEmpty() && legacyNumber.isNotEmpty()) {
            savePrimeContacts(listOf(PrimeContact(legacyName, legacyNumber)))
        }
    }

    private fun readSettings(): MyraSettings {
        return MyraSettings(
            connectionMode = ConnectionMode.fromKey(prefs.getString(KEY_CONNECTION_MODE, null)),
            apiKey = getSecureString(KEY_API_KEY),
            proxyUrl = prefs.getString(KEY_PROXY_URL, "").orEmpty(),
            proxyAuthToken = getSecureString(KEY_PROXY_TOKEN),
            userName = prefs.getString(KEY_USER_NAME, "Sir").orEmpty().ifBlank { "Sir" },
            model = GeminiModelOption.fromId(prefs.getString(KEY_GEMINI_MODEL, null)),
            voice = VoiceOption.fromId(prefs.getString(KEY_GEMINI_VOICE, null)),
            personality = PersonalityMode.fromKey(prefs.getString(KEY_PERSONALITY_MODE, null)),
            primeContacts = getPrimeContacts(),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false),
            overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, false),
            callMonitorEnabled = prefs.getBoolean(KEY_CALL_MONITOR_ENABLED, false),
            pcCompanionPort = prefs.getInt(KEY_PC_COMPANION_PORT, 8765),
        )
    }

    companion object {
        private const val PREFS_NAME = "myra_secure_prefs"
        private const val PREFS_NAME_FALLBACK = "myra_prefs_fallback"

        const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_PAIRED_DEVICE_KEY_PREFIX = "paired_device_key_"

        const val KEY_API_KEY = "api_key"
        const val KEY_PROXY_URL = "proxy_url"
        const val KEY_PROXY_TOKEN = "proxy_token"
        const val KEY_USER_NAME = "user_name"
        const val KEY_PERSONALITY_MODE = "personality_mode"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_GEMINI_VOICE = "gemini_voice"
        const val KEY_PRIME_CONTACTS_JSON = "prime_contacts_json"
        const val KEY_LEGACY_PRIME_NAME = "prime_name"
        const val KEY_LEGACY_PRIME_NUMBER = "prime_number"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_CALL_MONITOR_ENABLED = "call_monitor_enabled"
        const val KEY_PC_COMPANION_PORT = "pc_companion_port"
    }
}
