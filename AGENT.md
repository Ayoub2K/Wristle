# Agent guide: Wristle

Instructions for AI coding agents working in this repository.

## Project

Wristle is a voice-first assistant for the **Samsung Galaxy Watch 4** (Wear OS) that calls the Claude API directly from the watch. It is a hobby project by Ayoub (Android developer: Kotlin, Compose, Android Studio).

- **Phase 1 (current):** standalone assistant on the watch. Plan and milestone status: [docs/phase-1-plan.md](docs/phase-1-plan.md).
- **Phase 2 (later, do not build yet):** control a computer from the watch (approve commands, answer questions, dictate follow-ups) via a bridge on the computer. It must run on macOS, Windows **and** Linux.

## External references (agent context only)

These repositories are named here for your context only. **Do not mention them anywhere else** in the repo: not in code, docs, the README or commit messages. The only exception is the copyright line that the MIT license requires in [LICENSE](LICENSE).

- **mushinlab/claude-watch** (MIT): the phase 1 code is based on it.
- **kayomarz97/wristpilot**: phase 2 inspiration. Its bridge and relay are MIT, but its Wear app is "all rights reserved": do not copy its code. Its bridge uses a Unix socket path, so it probably does not run on Windows as-is; verify before building on it.
- **caamanoluismiguel/ccwearos**: macOS only. Reference only.

## Communication

- Talk to the owner in **Dutch**.
- Write code, comments, commit messages and documentation in **English**.

## Working agreements

- Work in small milestones that the owner can install and test on the watch.
- **Ask before big choices:** speech-to-text, proxy and relay are the owner's decisions. Present options with pros and cons; do not pick one yourself.
- Make **no assumptions about Watch 4 capabilities.** Verify them in code, documentation or on the device, and say explicitly what is still unverified.
- Keep the architecture split into UI, speech, chat backend and transport, so a second backend (the phase 2 computer bridge) fits in without a rewrite. See "Architecture target" in the phase 1 plan.
- Commit or push only when the owner asks.

## Security

- **Never commit secrets.** API keys live in `local.properties` (git-ignored) and reach the watch via `setup-key.ps1` / `setup-key.sh` or on-watch input. They are never compiled into the APK.
- Secrets on the watch are encrypted with an Android Keystore AES-GCM key (`data/SecretCipher.kt`). Do not switch to the deprecated EncryptedSharedPreferences.
- `keystore.properties`, `*.jks` and `*.keystore` are git-ignored; keep them that way.

## Branding

The repo is public. Do not use Anthropic's name or logo: no "Claude" as the app name, no Claude logo or look-alike icons. "Claude" may appear only to describe which API is used (e.g. "Claude API key", `ClaudeApiBackend`). The README carries an "unofficial, not affiliated" disclaimer.

The owner chose to use Claude's terracotta (`#D97757`) as the accent colour (`ui/Colors.kt`, launcher icon). Keep it.

## Build and test

Requirements: Android SDK with platform 37, JDK 17+. `local.properties` needs `sdk.dir` (on Windows escape the colon: `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk`).

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:ktlintCheck            # style (./gradlew :app:ktlintFormat to fix)
./gradlew :app:lintRelease            # Android lint, warnings are errors
./gradlew :app:assembleRelease        # APK for the watch (release is much faster than debug)
```

Run all four before handing a change over. Sideload steps for the watch are in the [README](README.md#install-on-the-galaxy-watch-4).

## Code layout

Package `dev.ayoub.wristle` under `app/src/main/java/`:

| Path | Purpose |
|---|---|
| `MainActivity.kt` | Entry point; decides between reopening the chat and starting a new one; launches speech input |
| `KeySetupActivity.kt` | Receives the API key via `adb shell` only (guarded by `WRITE_SECURE_SETTINGS`) |
| `AppContainer.kt` | Manual dependency injection; wire new implementations here |
| `chat/` | `ChatBackend` interface; `ClaudeApiBackend` (Anthropic Java SDK) and `StreamAccumulator`. Phase 2 adds a second backend here |
| `data/` | `ChatRepository` (depends only on `ChatBackend`), `ChatSession`, `ChatStore`, `SettingsRepository`, `SecretCipher` |
| `speech/` | `SystemSpeechInput` (watch recognizer), `ReplySpeaker` (TTS, sentence by sentence) |
| `service/` | Foreground service while a request runs |
| `ui/` | Compose for Wear OS screens and view models |
| `assist/` | Voice interaction service so the app can be the digital assistant |
| `tile/` | Wear tile with a mic button that opens the app into listening |

## Conventions

- Kotlin official style, enforced by ktlint. Compose for Wear OS (`androidx.wear.compose`).
- Manual DI through `AppContainer`; no DI framework.
- Match the density of comments in the surrounding code (sparse; explain why, not what).
- User-facing text goes in `res/values/strings.xml` (English) with a Dutch translation in `res/values-nl/strings.xml`.
- Default model IDs are in `SettingsRepository.MODELS`.
