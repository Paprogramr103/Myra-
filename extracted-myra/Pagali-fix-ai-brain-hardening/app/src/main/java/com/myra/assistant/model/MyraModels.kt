package com.myra.assistant.model

enum class VoiceOption(val voiceId: String, val displayName: String) {
    AOEDE("Aoede", "Aoede (Female)"),
    CHARON("Charon", "Charon (Male)"),
    KORE("Kore", "Kore (Female)"),
    FENRIR("Fenrir", "Fenrir (Male)"),
    PUCK("Puck", "Puck (Male)"),
    LEDA("Leda", "Leda (Female)"),
    ORUS("Orus", "Orus (Male)"),
    ZEPHYR("Zephyr", "Zephyr (Female)");

    companion object {
        fun fromId(id: String?): VoiceOption =
            entries.firstOrNull { it.voiceId.equals(id, ignoreCase = true) } ?: AOEDE
    }
}

enum class GeminiModelOption(val modelId: String, val displayName: String) {
    NATIVE_AUDIO(
        "models/gemini-3.8-live",
        "Gemini 3.8 Live (Recommended)",
    ),
    FLASH_LIVE(
        "models/gemini-3.1-flash-live-preview",
        "Gemini 3.1 Flash Live (Preview)",
    ),
    PRO_AUDIO_DIALOG(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "Gemini 2.5 Native Audio (Legacy access)",
    );

    companion object {
        fun fromId(id: String?): GeminiModelOption =
            entries.firstOrNull { it.modelId == id } ?: NATIVE_AUDIO
    }
}

enum class PersonalityMode(val key: String, val displayName: String) {
    GF("GF", "GF Mode (Hinglish)"),
    PROFESSIONAL("PROFESSIONAL", "Professional Mode (English)"),
    ASSISTANT("ASSISTANT", "Assistant Mode (Balanced)");

    companion object {
        fun fromKey(key: String?): PersonalityMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GF
    }
}

enum class OrbState {
    IDLE,
    LISTENING,
    SPEAKING,
    THINKING,
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

enum class ConnectionMode(val key: String, val displayName: String) {
    DIRECT_USER_KEY("DIRECT_USER_KEY", "Direct User API Key (Personal / Dev)"),
    BACKEND_PROXY("BACKEND_PROXY", "Secure Backend Proxy (Production)");

    companion object {
        fun fromKey(key: String?): ConnectionMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DIRECT_USER_KEY
    }
}

data class PrimeContact(
    val name: String,
    val number: String,
)

data class ChatMessage(
    val id: Long = 0L,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "voice",
)

data class MyraSettings(
    val connectionMode: ConnectionMode = ConnectionMode.DIRECT_USER_KEY,
    val apiKey: String = "",
    val proxyUrl: String = "",
    val proxyAuthToken: String = "",
    val userName: String = "Sir",
    val model: GeminiModelOption = GeminiModelOption.NATIVE_AUDIO,
    val voice: VoiceOption = VoiceOption.AOEDE,
    val personality: PersonalityMode = PersonalityMode.GF,
    val primeContacts: List<PrimeContact> = emptyList(),
    val wakeWordEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val callMonitorEnabled: Boolean = false,
    val pcCompanionPort: Int = 8765,
)

data class SystemStatus(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val usedRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val timeLabel: String = "--:--",
    val isTorchOn: Boolean = false,
)

data class OtpAlert(
    val id: String,
    val sender: String,
    val code: String,
    val detectedAt: Long,
    val expiresAt: Long,
)

data class NotificationSummary(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

data class PermissionSnapshot(
    val microphone: Boolean = false,
    val contacts: Boolean = false,
    val phoneCall: Boolean = false,
    val phoneState: Boolean = false,
    val answerCalls: Boolean = false,
    val camera: Boolean = false,
    val location: Boolean = false,
    val notifications: Boolean = false,
    val bluetoothConnect: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
)
