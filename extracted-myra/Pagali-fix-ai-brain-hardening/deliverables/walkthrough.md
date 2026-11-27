# Security Summary

**Reviewed Files**: 42
**Issues Resolved**: 6

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| API Key Packaged in APK | `app/build.gradle.kts`, `.env.example`, `SecureSettingsRepository.kt` | 5, 19 | The build-time Secrets Gradle Plugin read `.env` and compiled a real Gemini API key into `BuildConfig.GEMINI_API_KEY` inside the first-turn `app-debug.apk`. | Critical | Needs Follow-Up | Removed `secrets-gradle-plugin`, commented out `.env.example`, removed `BuildConfig.GEMINI_API_KEY` references, added Backend Proxy mode, and verified `BuildConfig.java` and `app-debug.apk` DEX contain zero keys. **Follow-Up:** Rotate the previously exposed `AQ.Ab8RN6...` key in Google Cloud / AI Studio immediately. |
| Missing Production Backend Proxy | `GeminiLiveClient.kt`, `SettingsScreen.kt`, `backend-proxy/server.js` | 26 | The app only supported connecting directly from the mobile device with a client-side API key. | High | Fixed | Added `ConnectionMode.BACKEND_PROXY` (`wss://` enforced for non-localhost endpoints, `Authorization: Bearer` header) and created a reference Node.js WebSocket relay (`backend-proxy/server.js`) that keeps `GEMINI_API_KEY` strictly on the server. |
| PIN Brute-Force Counter Reset Bug | `PcCompanionServer.kt`, `MyraConnectManager.kt` | 225, 260 | When `lockUntil` was `0L` (before reaching 5 failed attempts), `now >= attemptInfo.second` evaluated to true on every request and reset the failure counter to `0`, preventing lockout from ever activating. | High | Fixed | Fixed the check to only reset the failure count after an active lockout (`lockUntil > 0L`) has expired, and added automated loopback socket tests verifying 5-attempt IP lockout on both servers. |
| Lockout Frame Handling in Peer Client | `MyraConnectManager.kt` | 141 | When a locked host rejected an incoming peer before issuing a challenge nonce, `pairAndSendTest` threw a JSON parsing error instead of surfacing the lockout message. | Low | Fixed | Added an explicit `ok == false` check on the initial peer frame before reading `nonce`. |
| Unsanitized DOM Log Insertion | `PcCompanionServer.kt` | 444 | The embedded PC Companion HTML dashboard needed guaranteed safe DOM rendering for incoming WebSocket payloads. | Medium | Fixed | Verified and enforced `document.createElement('div')` with `.textContent` instead of `innerHTML` so remote frames cannot execute scripts in the browser. |
| Service State Sync on OS Rejection | `WakeWordService.kt`, `MyraOverlayService.kt`, `CallMonitorService.kt`, `PcCompanionService.kt` | 45, 41, 33, 21 | Services marked `isRunning = true` in `onCreate()` before permission checks and `startForeground()` succeeded, which could misreport running state if the OS blocked a foreground service start. | Medium | Fixed | Moved `isRunning = true` to run only after `startForeground()` succeeds inside `try/catch`, resetting `isRunning = false` and calling `stopSelf()` on failure. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| Local User Key Storage | `SecureSettingsRepository.kt` | Uses Android Keystore AES-256 `EncryptedSharedPreferences` (`AES256_SIV` keys, `AES256_GCM` values) strictly for user-entered credentials, plus a one-tap "Clear Keys" wipe action. |
| Log & Error Redaction | `GeminiLiveClient.kt` | `redactSecrets()` strips `?key=...` query strings and `Bearer ...` tokens from all diagnostic logs, closing reasons, and UI error messages. |
| Local-Only OTP Guard | `OtpSanitizer.kt`, `MyraNotificationListenerService.kt`, `MyraRepository.kt` | Banking OTPs stay only in volatile RAM for 60 seconds and are blocked or redacted before Room DB persistence, diagnostic logging, Gemini Live transmission, PC Companion, or MYRA Connect. |
| Device-to-Device Auth | `MyraConnectManager.kt`, `PairingCrypto.kt` | Uses random 16-byte nonces with HMAC-SHA256 signatures derived from a 6-digit `SecureRandom` PIN, constant-time comparison, and 5-attempt per-IP lockout. |

---

## PoC Verification

### 1. API Key Removal & Zero-Secret APK Build
#### Summary
| Field | Value |
|---|---|
| Issue Type | Build-Time Secret Embedded in Client APK |
| Impact | Critical |
| Affected File | `app/build.gradle.kts`, `SecureSettingsRepository.kt`, `GeminiLiveClient.kt` |
| Test Scenario | Rebuild `app-debug.apk` and scan `BuildConfig.java`, all DEX files, `.arsc`, `.xml`, and `.json` entries inside the APK for Google/Gemini key patterns (`AIza...`, `AQ.Ab8RN...`), plus verify `SecureSettingsRepository` returns an empty key on clean install. |

#### How It Was Fixed
Removed the Secrets Gradle Plugin from `app/build.gradle.kts`, commented out `.env.example`, removed `BuildConfig.GEMINI_API_KEY` from `SecureSettingsRepository`, and added `ConnectionMode.BACKEND_PROXY` backed by `backend-proxy/server.js`.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Gradle generates `BuildConfig.java` (`app/build/generated/source/buildConfig/debug/com/myra/assistant/BuildConfig.java`) without `secrets-gradle-plugin`. | Only `DEBUG`, `APPLICATION_ID`, `BUILD_TYPE`, `VERSION_CODE`, and `VERSION_NAME` are generated. |
| 2 | Python ZIP/DEX scanner inspects every `.dex`, `.arsc`, `.xml`, `.json`, and `.properties` entry inside `app/build/outputs/apk/debug/app-debug.apk`. | `APK secret hits: []` (0 matches). |
| 3 | `CompanionNetworkAndSecurityTest` checks `SecureSettingsRepository.getUserEnteredApiKey()` and `GeminiLiveClient.connect()` with no user key. | Returns `""` and blocks connection with `ConnectionState.ERROR` prompting user configuration or Backend Proxy setup. |

#### Conclusion
**Fix Verified** - No Gemini API key is compiled into `BuildConfig.java` or `app-debug.apk`. (The user must still rotate the key that was packaged into the earlier pre-audit APK.)

---

### 2. PC Companion & MYRA Connect PIN Brute-Force Lockout
#### Summary
| Field | Value |
|---|---|
| Issue Type | Authentication Rate-Limit Bypass |
| Impact | High |
| Affected File | `PcCompanionServer.kt:225`, `MyraConnectManager.kt:260` |
| Test Scenario | Send 5 invalid 6-digit PINs / bad HMACs from `127.0.0.1` followed immediately by a 6th attempt carrying the real valid PIN / valid HMAC. |

#### How It Was Fixed
Changed the expiration guard to `attempt != null && attempt.second > 0L && now >= attempt.second` so attempts 1 through 4 increment cleanly until attempt 5 sets `lockUntil = now + 60_000L`.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Attempts 1–4 arrive with an invalid PIN (`lockUntil == 0L`). | `isExpiredLock` is `false`; counter increments `1 -> 2 -> 3 -> 4`. |
| 2 | Attempt 5 arrives with an invalid PIN. | Counter hits `5`, setting `lockUntil = now + 60_000L`. |
| 3 | Attempt 6 arrives carrying the valid PIN/HMAC from the same IP (`127.0.0.1`). | Guard at line `198` (`PcCompanionServer.kt`) / `195` (`MyraConnectManager.kt`) sees `now < lockUntil` and rejects with `auth_locked` / `"Locked"`. |

#### Conclusion
**Fix Verified** - Verified by live loopback socket tests in `CompanionNetworkAndSecurityTest` (both `pc companion locks out ip after 5 failed pin attempts` and `myra connect locks out ip after 5 failed hmac attempts` pass).

---

### 3. Production Backend Proxy TLS Policy & Log Redaction
#### Summary
| Field | Value |
|---|---|
| Issue Type | Credential Transport & Log Exposure |
| Impact | High |
| Affected File | `GeminiLiveClient.kt:85`, `GeminiLiveClient.kt:490` |
| Test Scenario | Attempt `BACKEND_PROXY` connection to `ws://remote.myra.app/ws` (plaintext remote WebSocket) and pass `?key=AIzaSySecret12345` + `Bearer tok_abc_999` through `GeminiLiveClient.redactSecrets()`. |

#### How It Was Fixed
`validateCredentials()` permits `ws://` only for loopback testing (`localhost` / `127.0.0.1`) and requires `wss://` for all remote hosts; `redactSecrets()` masks `?key=` and `Bearer` tokens before logging or error reporting.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | `GeminiLiveClient.connect()` calls `validateCredentials(settings)` with `ws://remote.myra.app/ws`. | Rejected immediately with `ConnectionState.ERROR` ("Security policy requires wss://"). |
| 2 | `GeminiLiveClient.redactSecrets(raw)` filters error/log text. | Secrets are replaced with `?key=[REDACTED]` and `Bearer [REDACTED]`. |
| 3 | Loopback proxy test connects `GeminiLiveClient` to a real local WebSocket server with `Authorization: Bearer proxy_secret_token_test`. | Request headers contain the Bearer token and zero `key=` query parameter; `setup` JSON specifies `voice_name: Aoede`. |

#### Conclusion
**Fix Verified** - Verified by automated Robolectric socket tests in `CompanionNetworkAndSecurityTest`.

---

## Actual Test, Build & Lint Results

- **Gradle Debug Build (`compile_applet` / `:app:assembleDebug`):** `BUILD SUCCESSFUL`
  - Output APK: `app/build/outputs/apk/debug/app-debug.apk` (`45,021,501` bytes)
  - SHA-256: `bc7bbec286eb31c3ae0a812dc33197827908887f98ef3190bf03f0c63ea55fb0`
  - Secret scan across `BuildConfig.java`, DEX, `.arsc`, XML, and JSON: **0 secrets found**
- **Android Lint (`gradle :app:lintDebug`):** `BUILD SUCCESSFUL` (`0 errors, 120 warnings`; warnings are upstream library version hints / targetSdk 34 / non-critical layout hints; report saved in `app/build/reports/lint-results-debug.html`)
- **Unit & Robolectric JVM Tests (`gradle :app:testDebugUnitTest`):** **19 / 19 PASSED (0 failures, 0 errors, 0 skipped)**

| Test Suite | Test Name | Result | Time | What Was Actually Verified |
|---|---|---|---|---|
| `CommandParserTest` | `parses open app in hinglish and english` | PASS | 0.001s | Hinglish/English app launch intents |
| `CommandParserTest` | `parses close app and navigation commands` | PASS | 0.000s | Accessibility Home/Back/Scroll parsing |
| `CommandParserTest` | `parses calls sms whatsapp and prime contacts` | PASS | 0.008s | Call, SMS, WhatsApp, and Prime Contact triggers |
| `CommandParserTest` | `parses device controls maps parking and scanner` | PASS | 0.000s | Torch, Volume, Wi-Fi/BT settings, Maps, Parking, Scanner |
| `CommandParserTest` | `returns null for normal conversation` | PASS | 0.001s | Non-command conversational fallback to Gemini Live |
| `OtpSanitizerAndCryptoTest` | `detects and redacts otp messages locally` | PASS | 0.041s | OTP detection, extraction & redaction |
| `OtpSanitizerAndCryptoTest` | `verifies hmac sha256 pairing signature` | PASS | 0.045s | PIN-derived key & constant-time HMAC-SHA256 |
| `OtpSanitizerAndCryptoTest` | `tool registry converts gemini function calls to AppCommands` | PASS | 0.051s | Gemini tool declarations & command conversion |
| `OtpSanitizerAndCryptoTest` | `audio rms computes zero for silence and positive for signal` | PASS | 0.079s | PCM16 RMS envelope math for orb waveform |
| `MyraRobolectricTest` | `app name and defaults match MYRA specification` | PASS | 0.134s | App name `MYRA`, `Aoede` default voice, `GF` mode, default model |
| `MyraRobolectricTest` | `prime contacts save and remove cleanly` | PASS | 1.988s | `SecureSettingsRepository` prime contact persistence |
| `MyraRobolectricTest` | `system prompt builds with personality and user name` | PASS | 0.107s | System prompt generation for Gemini Live setup |
| `MyraRobolectricTest` | `main dashboard renders orb waveform mic and text input` | PASS | 2.434s | Jetpack Compose UI hierarchy & responsive rendering |
| `CompanionNetworkAndSecurityTest` | `pc companion websocket enforces pin auth delivers commands blocks otp and serves safe dashboard` | PASS | 0.504s | Real `127.0.0.1` HTTP + RFC 6455 WebSocket pairing, command ACK, OTP blocking, telemetry broadcast, XSS-safe HTML |
| `CompanionNetworkAndSecurityTest` | `pc companion locks out ip after 5 failed pin attempts` | PASS | 0.326s | Real WebSocket 5-attempt brute-force IP lockout |
| `CompanionNetworkAndSecurityTest` | `myra connect tcp hmac challenge response pairs valid pin and rejects tampered hmac` | PASS | 0.242s | Real `127.0.0.1` TCP nonce+HMAC auth, Room DB paired persistence, OTP blocking |
| `CompanionNetworkAndSecurityTest` | `myra connect locks out ip after 5 failed hmac attempts` | PASS | 0.160s | Real TCP 5-attempt brute-force IP lockout & clean lockout frame handling |
| `CompanionNetworkAndSecurityTest` | `gemini live client redacts secrets validates wss proxy policy and sends setup over proxy` | PASS | 0.448s | Log/error secret redaction, `ws://` rejection on remote hosts, loopback WebSocket setup (`Aoede` + Bearer token, no client `key=`) |
| `CompanionNetworkAndSecurityTest` | `room repository redacts otp before persistence and secure settings starts without bundled key` | PASS | 8.176s | Empty clean-install key + Room DB redaction of OTPs in chats and logs |

- **Instrumentation Tests (`androidTest`):** **Not Executed.** No Android Emulator or ADB is present in this cloud execution environment, so hardware and on-device integration behaviors remain unverified on live hardware.

---

## Gemini Live Audio Streaming & Google Endpoint Connection Status

1. **What is Implemented & Verified Locally:**
   - `GeminiLiveClient.kt` implements the `v1alpha GenerativeService.BidiGenerateContent` WebSocket protocol, 3 selectable model IDs (`gemini-2.5-flash-native-audio-preview-12-2025`, `gemini-2.0-flash-live-001`, `gemini-2.5-flash-preview-native-audio-dialog`), `Aoede` default voice (+ 7 selectable voices), 8s silent keepalive frames, 540s session renewal, 3s reconnect, Function Calling (`ToolRegistry.kt`), input/output transcription handling, and `redactSecrets()`.
   - Loopback socket tests verified that the WebSocket client transmits the `setup` JSON with `voice_name: "Aoede"`, `tools`, and `Authorization: Bearer` header in Proxy Mode without leaking any client API key.
   - `AudioEngine.kt` implements 16kHz Mono PCM16 `AudioRecord` capture, 24kHz Mono PCM16 `AudioTrack` streaming playback, `AcousticEchoCanceler`, 320ms playback-to-mic echo tail suppression, and interrupt/flush support (`interruptPlayback()`).
2. **What is NOT Yet Device/Live Verified (Honest Status):**
   - **Live Google Endpoint (`generativelanguage.googleapis.com`):** Real upstream handshake, live server audio synthesis, model availability for a user's specific Google Cloud project, and live latency were **not executed** in this container because no user API key or live Android device is attached.
   - **Physical Microphone & Speaker Streaming:** Real `AudioRecord` / `AudioTrack` hardware hardware-AEC behavior and duplex echo cancellation were **not executed** because no physical Android audio hardware/emulator exists in this environment.
   - Therefore, **Gemini Live WebSocket & PCM Audio Streaming remain `PARTIALLY IMPLEMENTED` / `IMPLEMENTED BUT NOT DEVICE TESTED`** until tested on a physical Android phone with a valid Gemini API key or deployed Backend Proxy.

---

## Backend Proxy Deployment Instructions (Production)

Do **not** ship a shared/production `GEMINI_API_KEY` inside the Android APK. Deploy `backend-proxy/` as a standalone Node.js WebSocket server:

1. **Install & Configure (`backend-proxy/`):**
   ```bash
   cd backend-proxy
   npm install
   cp .env.example .env
   ```
   Set inside server-only `.env` (or Cloud Run Secret Manager / Fly.io Secrets):
   - `GEMINI_API_KEY=<NEW_ROTATED_KEY>`
   - `PROXY_AUTH_TOKEN=<STRONG_RANDOM_SECRET>`
   - `PORT=8080`
   - `MAX_MESSAGES_PER_MIN=180`
2. **Start / Deploy Behind TLS (`wss://`):**
   - Local test: `npm start` (exposes `/healthz` and WebSocket `/ws`).
   - Production: Deploy `backend-proxy/` to Cloud Run, Fly.io, Railway, or an Nginx/Caddy reverse proxy with HTTPS/TLS termination so Android connects over `wss://<your-domain>/ws`.
3. **Configure MYRA Android App:**
   - Open **MYRA → Settings → Connection Mode → Secure Backend Proxy (Production)**.
   - Enter `wss://<your-domain>/ws` and your `PROXY_AUTH_TOKEN` -> **SAVE & APPLY CHANGES**.
4. **Google Cloud / AI Studio Security Hardening:**
   - **Rotate immediately** the old key (`AQ.Ab8RN6...`) packaged into the pre-audit build.
   - Restrict the new key strictly to **Generative Language API**.
   - Enable per-minute / daily request quotas and billing budget alerts.
   - Before public launch, replace the single static `PROXY_AUTH_TOKEN` in `backend-proxy/server.js` with per-user Firebase Auth ID tokens or short-lived JWTs.
