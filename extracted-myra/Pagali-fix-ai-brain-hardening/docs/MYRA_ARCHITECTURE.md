# MYRA Android Architecture

This document records the canonical responsibilities for the current native Android app. Keep this as a maintenance guide; do not create a second implementation of an existing responsibility.

## Keep these canonical components

- `ai/GeminiLiveClient.kt`: Gemini Live WebSocket lifecycle, session setup/resumption, transcripts, audio payloads, and tool-call transport. It must not directly access Android UI or Room.
- `ai/AudioEngine.kt`: microphone capture, PCM playback, echo suppression, mute state, and Android audio routing. Keep a single owner for AudioRecord/AudioTrack.
- `ai/ToolRegistry.kt`: native action function declarations, argument validation, and mapping to `AppCommand`.
- `ai/CommandParser.kt`: fast, deterministic local Hindi/Hinglish command parsing. It should not duplicate Gemini's general language understanding.
- `ai/ChatHistoryToolRegistry.kt`: validation and declaration for bounded MYRA-only history recall.
- `ai/MemoryToolRegistry.kt` and `ai/EmotionalMemoryToolRegistry.kt`: separate validation contracts for stable facts and dated emotional events. Proposals are not saved until the user approves them.
- `data/db/*`: Room entities, DAOs, migrations, and persistence only.
- `data/repository/MyraRepository.kt`: the app-facing persistence boundary. UI and AI code should not issue Room queries directly.
- `viewmodel/MainViewModel.kt`: screen state and orchestration. Move future independent workflows into focused coordinators/use-cases rather than growing this class indefinitely.
- `ui/screens/*` and `ui/components/*`: Compose screens and reusable visual components. Do not put Android command execution or SQL in composables.
- `service/*`: Android lifecycle-bound foreground, accessibility, notification, wallpaper, and companion services. Each service must have a user-visible purpose and least privilege.

## Storage boundaries

- API credentials belong in Android Keystore-backed encrypted preferences when available.
- Chat history, approved personal facts, approved emotional events, pending memory proposals, and action outcomes are distinct Room data sets.
- On real Android devices/emulators, Room uses SQLCipher with a database key prepared through `MyraDatabaseEncryptionMigration` and the Android Keystore-backed `MyraDatabaseKeyStore`. Robolectric host-JVM tests deliberately use platform SQLite because SQLCipher Android JNI is unavailable there. Do not treat the Robolectric test path as the production storage path. Review plaintext migration backups and crash-recovery behavior before changing this lifecycle.
- Do not persist raw microphone audio by default. Persist recognized text only.
- Action audit records store command type, parameter names, outcome, summary, and timestamp; never store phone numbers, OTPs, credentials, or message bodies in the audit table.

## Change rules

1. Inspect call sites and tests before changing or removing a file.
2. Preserve Android manifest declarations, Gradle wrapper/signing configuration, package name, Room migrations, voice model/voice ID, and working native services unless the change explicitly requires a reviewed migration.
3. Prefer modifying the canonical component over adding another parallel class.
4. Every Room schema change needs a forward migration and a migration test.
5. Every new AI tool needs strict argument validation, provenance/safety checks where applicable, bounded output, failure responses, and tests.
6. A feature is not complete because its class or UI exists. Verify tool wiring, persistence, failure handling, CI, and a real-device test where hardware/OS behavior is involved.
7. Keep `main` untouched until the feature branch has passed validation and is deliberately reviewed for merge.
