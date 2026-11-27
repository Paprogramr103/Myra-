# MYRA — Native Android AI Voice Assistant (`com.myra.assistant`)

MYRA एक वास्तविक Native Kotlin Android AI Voice Assistant है, जिसे **Google Gemini Live WebSocket API (`v1beta BidiGenerateContent`)**, **Native PCM 16kHz/24kHz Audio Streaming**, **MVVM + Jetpack Compose + Room + EncryptedSharedPreferences** के साथ बनाया गया है।

---

## 1. API Key Security & Setup (CRITICAL)

- **Zero Bundled Secrets in APK:** MYRA के APK और source code में कोई hardcoded या build-time API key शामिल नहीं है (`secrets-gradle-plugin` हटा दिया गया है और `.env.example` में placeholder commented है)।
- **Key Rotation Warning:** यदि पहले के किसी build या test APK में कोई वास्तविक Google/Gemini API key (`AQ.Ab8RN6...`) शामिल हुई थी, तो **तुरंत Google Cloud Console / AI Studio में जाकर उस key को Revoke/Rotate करें**।
- **Mode 1 — Direct User API Key (Personal / Dev):**
  1. ऐप खोलें → **Settings (Gear Icon)** → **Direct User API Key** चुनें।
  2. अपनी व्यक्तिगत AI Studio Gemini API Key डालें → **SAVE & APPLY CHANGES**।
  3. Key डिवाइस पर Android Keystore AES-256 `EncryptedSharedPreferences` में सेव होती है।
  4. **Security Reality:** Client APK में user द्वारा डाली गई key को rooted/compromised डिवाइस पर runtime memory inspection से 100% छिपाया नहीं जा सकता।
- **Mode 2 — Secure Backend Proxy (Production Recommended):**
  1. `backend-proxy/` फोल्डर में `GEMINI_API_KEY` और `PROXY_AUTH_TOKEN` केवल सर्वर के `.env` में सेट करें (`backend-proxy/README.md` देखें)।
  2. ऐप Settings में **Secure Backend Proxy (Production)** चुनें, `wss://...` URL और Bearer token डालें। इससे API key कभी Android डिवाइस पर नहीं आती।
- **Google Cloud / AI Studio Restrictions & Quotas:**
  - API Key को केवल **Generative Language API** तक restrict करें।
  - Per-minute / daily request quotas और Google Cloud Billing budget alerts सक्रिय करें।

---

## 2. Architecture & Honest Capability Notes

- **Gemini Live Voice (`GeminiLiveClient.kt` + `AudioEngine.kt`):** `v1beta BidiGenerateContent` WebSocket, `Aoede` default voice (+7 अन्य voices), 3 current/legacy Gemini Live model choices, 16kHz Mono PCM16 input, 24kHz Mono PCM16 output, `AcousticEchoCanceler` + echo tail suppression, WebSocket ping keepalive, `audioStreamEnd` on mic pauses, session resumption, 540s renewal, exponential reconnect, tool-call input gating, Function Calling।
- **Wake Word Service (`WakeWordService.kt`):** Permission-based Foreground Service जो OS `SpeechRecognizer` का उपयोग करता है। इसमें offline neural hotword model (`.ppn` / `.tflite`) बंडल नहीं है, इसलिए Android background mic व FGS नियमों के तहत यह foreground notification के साथ काम करता है।
- **Power Button / Overlay (`PowerButtonReceiver.kt` + `MyraOverlayService.kt`):** Dynamic receiver केवल screen on/off (`ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`) के तेज़ toggle (60–600ms) से overlay खोलता है; raw hardware `KEYCODE_POWER` को background से intercept करना Android OS द्वारा प्रतिबंधित है।
- **Wi-Fi / Bluetooth (`AndroidToolsExecutor.kt`):** Android 10+ में silent Wi-Fi/Bluetooth toggle प्रतिबंधित होने के कारण आधिकारिक `Settings.Panel` / Settings स्क्रीन खुलती है।
- **Local-Only OTP Safety (`OtpSanitizer.kt` + `MyraNotificationListenerService.kt`):** OTP केवल volatile RAM में 60s के लिए रहता है; Room DB, diagnostic logs, Gemini Live, PC Companion और MYRA Connect में OTP सख्ती से blocked/redacted रहता है।
- **PC Companion & MYRA Connect (`PcCompanionServer.kt` + `MyraConnectManager.kt`):** LAN HTTP/WebSocket + NSD (`_myra._tcp.`) TCP HMAC-SHA256 pairing, 6-digit PIN, और per-IP brute-force lockout (5 attempts -> 60s lockout)।

---

## 3. Build & Test Instructions

```bash
# First build only: create a local debug signing key and keep it for future updates
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

# Debug APK Build
./gradlew :app:assembleDebug

# Unit & Robolectric Tests
./gradlew :app:testDebugUnitTest

# Android Lint
./gradlew :app:lintDebug

# Production Backend Proxy
cd backend-proxy && npm install && npm start
```

Debug APK आउटपुट पथ: `app/build/outputs/apk/debug/app-debug.apk`
