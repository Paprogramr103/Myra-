# Maya v5.2.67 → MYRA Feature-Parity Audit

Audit date: 2026-11-26  
Reference APK: `maya-v5.2.67.apk`  
APK size: 49,347,540 bytes  
APK SHA-256: `157ba375729e19608dd39c652c33710e0daf67eb6ca1cfc6488f6415d47182fd`  
MYRA target branch: `fix/ai-brain-hardening`  
MYRA package: `com.myra.assistant`

## 1. Scope and evidence standard

The Maya APK was inspected as an archive, including its two DEX files, bundled assets, model manifests, macro definitions, and readable strings. The results below distinguish:
- **APK evidence:** a model, file, string, or code-level symbol exists in the APK. This does not prove the feature works on every phone.
- **MYRA source evidence:** the named MYRA implementation exists in the target branch. This does not prove device-level behavior.
- **Runtime verification:** still required on a physical Android device for microphone, OEM restrictions, Bluetooth, SIM routing, accessibility, camera, and background operation.

Use Maya only as a feature reference. Do not copy its proprietary source, models, voice recordings, branding, license tokens, server protocol, or protected assets. Reimplement equivalent capabilities in MYRA using original code and models/assets with compatible licenses.

## 2. Maya APK findings

### 2.1 Licensing and paid tiers

The APK contains these readable license/subscription indicators:
- `/api/license/verify-android`
- `/api/license/android-update-check`
- `license_key`, `license_proof`, `license_host`
- `X-Subscription-Token`
- `NOT_LICENSED`, `TRIAL_OFF`, `not_licensed`, `trial_off`
- `Pro Max`, `PRO MAX`, `Advanced Agentic Mode`, and Agentic-plan status messages
- A free-use-limit message and a message that the free trial is disabled.

No `BillingClient` / `com.android.billingclient` indicator was found in the scanned DEX strings. The evidence therefore points to a custom online license-verification/subscription-token flow in this APK; it does not establish every backend billing detail or the current price.

Strings and messages indicate these paid/entitlement-gated areas:
- Unlimited conversation / removal of free-minute limits.
- Full access to phone tools.
- PC Companion.
- Pro Max add-on: Hunter TTS, Conversation Mode, assistant-name customization, additional characters and live-wallpaper options.
- Advanced Agentic Mode / Agentic plan.
- Image generation includes a “pay-per-image, no subscription” message and an external provider API-key reference; this is a separate usage/provider cost, not proof of a MYRA requirement.

**MYRA direction:** do not remove or bypass Maya's server-side license checks. MYRA will have its own feature policy and must not add an MYRA subscription lock to locally implemented MYRA features. Cloud-model/API usage can still have provider costs and must be disclosed.

### 2.2 Voice, wake word, and audio

APK asset evidence:
- `assets/wakeword/embedding_model.tflite`
- `assets/wakeword/hello_maya.tflite`
- `assets/wakeword/hey_maya.tflite`
- `assets/wakeword/wake_up_maya.tflite`
- `assets/wakeword/melspectrogram.tflite`
- `assets/wakeword/stage2_v7.tflite`
- `assets/guardian/silero_vad.tflite`
- `assets/dtln/dtln_aec_128_1.tflite`, `dtln_aec_128_2.tflite`
- Multiple voice-bank OGG files for Maya, Friday, and Venom names.
- Pocket-TTS-related license notices and model/runtime assets are present in the archive.

The APK's own `assets/guardian/README.txt` says the speaker-recognition models are not bundled; it describes downloading model files from a Maya server manifest and verifying their SHA-256 before use. It identifies ReDimNet2-B6 (“Powerful”) and ECAPA-TDNN (“Medium”) model IDs. It also says the bundled Silero VAD is a speech gate, not the speaker identity model.

**MYRA direction:** implement offline wake-word detection as a separate engine behind a clear interface; retain the existing recognizer fallback and show which mode is active. Preserve MYRA Gemini Live Aoede configuration and the existing audio pipeline. Evaluate properly licensed VAD/AEC/TTS components; do not copy Maya's models or voice files. Voice identity must be opt-in and must never be presented as strong authentication or a replacement for Android biometrics.

### 2.3 Macro automation and agentic tools

APK evidence:
- `assets/macros/builtin_macros.json` contains built-in macro definitions (the file declares version 3).
- DEX strings include `MacroDraft`, `MacroStep`, `macro task agent`, and macro-related operations.
- The macro file documents label-based accessibility actions, optional steps, screen-text waits, and avoiding brittle fixed waits for variable-duration operations.
- DEX strings include Termux command execution, OpenClaw gateway references, and a Termux wakelock instruction.
- DEX strings include deletion-related tool names; these are destructive and must not be replicated without strong user confirmation and safe scoping.

**MYRA direction:** build a user-visible macro recorder and replay engine with allowlisted actions, named parameters, screen-state checks, a preview, stop/cancel control, and per-step results. Require explicit confirmation for sending, deleting, purchasing, account changes, or other consequential actions. Never record passwords, OTPs, payment data, or raw screen contents. Do not implement unrestricted shell/Termux command execution or silently execute destructive tools.

### 2.4 SIM, phone, and device integration

APK DEX strings include Android telephony subscription APIs such as `SubscriptionManager`, `SubscriptionInfo`, and a dual-SIM selection phrase. This is evidence of SIM-selection-related code, not proof that every carrier/device combination works.

Other APK strings indicate phone tools, PC Companion, Termux, and app automation. These need device/runtime validation.

**MYRA direction:** use supported Android Telecom/Subscription APIs and Android's user-facing chooser where required. Never bypass carrier, OS, emergency-call, or permission safeguards. Preserve safe handoffs for WhatsApp rather than pretending its private call/message APIs are available.

### 2.5 Camera, barcode, local models, and other assets

APK asset evidence:
- `assets/mlkit_barcode_models/barcode_ssd_mobilenet_v1_dmp25_quant.tflite`
- `assets/mlkit_barcode_models/oned_auto_regressor_mobile.tflite`
- `assets/mlkit_barcode_models/oned_feature_extractor_mobile.tflite`
- `assets/hasi/yamnet.tflite` and `laugh_head.bin`
- Voice, wake-word, VAD, and AEC assets listed above.

These filenames show bundled models/assets, not their exact runtime accuracy or every UI capability.

## 3. MYRA source audit

| Capability | MYRA source evidence | Assessment |
|---|---|---|
| Wake phrase | `service/WakeWordService.kt` | **Partial.** Uses Android SpeechRecognizer, optionally on-device; not a dedicated offline neural hotword model. Status text correctly discloses this. |
| Audio capture/playback, routing, AEC | `ai/AudioEngine.kt` | **Present / device test required.** Uses AudioRecord/AudioTrack, audio-device callbacks, and Android AcousticEchoCanceler when available. |
| Voice Guardian / speaker verification | No dedicated speaker-verification engine found in the inspected MYRA source | **Missing.** Separate opt-in design and licensed model required. |
| Offline LLM | No dedicated local LLM runtime/model manager found in inspected source | **Missing.** Gemini Live remains the cloud conversational engine. |
| Camera vision | `ui/screens/CameraVisionScreen.kt` | **Present / device test required.** |
| Barcode / QR | `ui/screens/BarcodeScannerScreen.kt` | **Present / device test required.** |
| Calls / SMS / WhatsApp | `system/AndroidToolsExecutor.kt`, `ai/ToolRegistry.kt` | **Partial.** Safe call/SMS/WhatsApp handoffs exist; WhatsApp call start remains user-controlled. |
| Dual-SIM routing | No explicit SIM-selection implementation found in inspected executor/registry | **Missing.** |
| Accessibility screen control | `service/AccessibilityHelperService.kt`, `system/AndroidToolsExecutor.kt` | **Partial.** Visible text click/type/scroll and guarded WhatsApp/YouTube actions exist. |
| Macro recorder/replay | No complete macro repository/recorder/replay feature found in inspected source | **Missing.** |
| PC Companion / MYRA Connect | `network/PcCompanionServer.kt`, `network/MyraConnectManager.kt`, companion services | **Present / real-device and PC interoperability tests required.** |
| Memory / history recall | `MemoryToolRegistry.kt`, `ChatHistoryToolRegistry.kt`, emotional memory components | **Present.** Stable, pending, emotional, and chat-history paths are distinct; recall is bounded/local. |
| Notifications / OTP protection | `service/MyraNotificationListenerService.kt`, `security/OtpSanitizer.kt` | **Present / device and notification-format tests required.** |
| Live wallpaper / overlay | `service/MyraLiveWallpaperService.kt`, `service/MyraOverlayService.kt` | **Present / OEM lifecycle and battery tests required.** |
| Maps / parking | `AndroidToolsExecutor.kt`, parking DAO/entity | **Present / device test required.** |
| Reminders / calendar | No complete first-party reminder/calendar scheduler found in inspected source | **Missing / needs a focused source re-check before implementation.** |
| Termux / arbitrary shell | No safe scoped Termux bridge found | **Not implemented.** Do not add unrestricted shell access. |
| MYRA billing / license | No MYRA subscription requirement is part of this parity task | **Do not add a paywall to MYRA's own local features.** Keep any third-party API usage/cost disclosure separate. |

## 4. Priority plan

### Phase 0 — Protect and stabilize
1. Work only on `fix/ai-brain-hardening`; do not merge or push changes to `main` without explicit approval.
2. Preserve the current package ID, Room schema/migrations, existing services, memory, tool validation, and Gemini Live Aoede configuration.
3. Stabilize CI and get unit/Robolectric, instrumentation, lint, and APK assembly to pass before claiming a deliverable APK.
4. Record a source baseline and inspect call sites before changing or replacing any existing component.

### Phase 1 — Highest-value local parity
1. Dedicated offline wake-word engine with a legally usable model/runtime; retain the existing SpeechRecognizer fallback and honest mode reporting.
2. Macro recorder/replay with allowlisted actions, persistent versioned macro definitions, preview, cancellation, and confirmation gates.
3. Improve Accessibility automation only through explicit user-enabled Android Accessibility settings and visible, bounded actions.

### Phase 2 — Voice and device intelligence
1. Opt-in Voice Guardian using a compatible licensed speaker-verification model; separate liveness/spoof checks from ordinary voice matching.
2. VAD/AEC evaluation and audio-route tests; preserve existing MYRA audio implementation unless measured tests prove a change is needed.
3. Dual-SIM call selection through supported Android APIs and user choice.
4. Reminder/calendar integration with clear permissions and user-visible scheduling controls.

### Phase 3 — Optional advanced integrations
1. Offline small-model runtime only after device RAM/storage/performance benchmarks and model-license review.
2. PC Companion interoperability hardening.
3. Any Termux bridge only as a narrow, explicit, allowlisted integration; no arbitrary command execution.
4. Additional local TTS only with compatible model licenses; Gemini Live Aoede remains untouched.

## 5. Acceptance criteria

- Preserve Gemini Live model/voice configuration (Aoede) and existing audio pipeline.
- Do not duplicate canonical services or registries.
- Every new tool has strict argument validation, bounded inputs/outputs, safe failure handling, and tests.
- Every Room schema change has a forward migration and migration test.
- Macro actions are allowlisted; consequential actions require preview and explicit confirmation.
- No password, OTP, payment details, or raw screen content in macro storage or diagnostics.
- No fake success when Android or another app has not confirmed the action.
- Offline wake-word mode is labelled offline only when the bundled local engine actually runs.
- Hardware-dependent features remain marked unverified until physical-device testing.
- No Maya proprietary code, protected assets, license tokens, or server-side license bypass is copied into MYRA.
