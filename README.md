# Wristle

A voice-first assistant for the Samsung Galaxy Watch 4 (Wear OS) that talks directly to the Claude API — no phone or PC needed at runtime. Ask a question by voice, watch the answer stream in, and hear it read aloud.

> Unofficial hobby project. Not affiliated with, sponsored or endorsed by Anthropic. "Claude" is a trademark of Anthropic, PBC, and is used here only to describe which API the app talks to.

Roadmap and milestone status: [docs/phase-1-plan.md](docs/phase-1-plan.md).

## Features

- Opens straight into listening; the reply streams to the screen and is spoken via the system TTS.
- Relaunching while a reply is in progress (or unseen) reopens the same chat instead of starting a new one.
- Model and effort selectable in settings (Opus 5.5 / Sonnet 5.5 / Haiku 5.5; effort low…max). Default: Opus 5.5 · low.
- Web search via the Claude API's server-side tool.
- Opus/Sonnet use server-side fallback (`fallbacks: "default"`).
- The API key is encrypted with an AES-GCM key held in the Android Keystore.

## Requirements

- Android Studio (ships the Android SDK and `adb`), JDK 17+.
- A Claude API key from [platform.claude.com](https://platform.claude.com).
- `local.properties` in the project root (git-ignored):
  ```properties
  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  claude.apiKey=sk-ant-...
  ```
  The key is never compiled into the APK; it is sent to the watch after install (see below).

## Build

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Install the release build on the watch: the debug build is noticeably slower and uses more battery.

Release builds are signed with the key from `keystore.properties` (git-ignored, see `keystore.properties.example`). Without that file the release build is signed with the debug key.

## Install on the Galaxy Watch 4

1. On the watch: **Settings → About watch → Software → tap "Software version" 5 times** until developer options are enabled.
   *(Exact menu names differ per One UI Watch version; on other Wear OS watches it is "Build number", tapped 7 times.)*
2. **Settings → Developer options**: enable **ADB debugging** and **Debug over Wi-Fi** (or **Wireless debugging**), then choose **Pair new device**. The watch shows an IP:port and a pairing code.
3. On the computer (same Wi-Fi network as the watch):
   ```bash
   adb pair <ip>:<pairing-port>      # enter the code shown on the watch
   adb connect <ip>:<debug-port>
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
4. Send the API key (once, and again after changing the key or clearing app data):
   - Windows (PowerShell): `.\setup-key.ps1`
   - macOS / Linux / Git Bash: `./setup-key.sh`

   `KeySetupActivity` is protected by `WRITE_SECURE_SETTINGS`, which normal apps can't hold, so only `adb shell` can start it. You can also enter the key on the watch itself: without a key the app opens the system text input on launch, and you can change it later under ⚙ → Claude API key.

## Launching from the side button

**Not yet verified on the Galaxy Watch 4.** The app registers as a `VoiceInteractionService` so it can be picked as the digital assistant. On stock Wear OS: **Settings → Apps → Default apps → Digital assistant → Wristle**.

Fallback via adb:
```bash
adb shell settings put secure assistant dev.ayoub.wristle/.assist.WristleVoiceInteractionService
adb shell settings put secure voice_interaction_service dev.ayoub.wristle/.assist.WristleVoiceInteractionService
```

While the app is the active assistant, system speech recognition for *other* apps may stop working (the app's `RecognitionService` is a stub). Investigating this on the Watch 4 is milestone 2.

## Project layout

| File | Purpose |
|---|---|
| `MainActivity.kt` | Entry point: reopen the current chat or start a new one and listen |
| `KeySetupActivity.kt` | Receives the API key via `adb shell` (no UI) |
| `AppContainer.kt`, `WristleApplication.kt` | Manual DI: repositories and the app-wide scope |
| `data/ChatRepository.kt` | Request lifecycle, chat state, retry, persistence |
| `data/ChatSession.kt`, `data/ChatStore.kt` | Chat model, stored in `noBackupFilesDir` |
| `data/ClaudeRemoteDataSource.kt` | Anthropic Java SDK: cancellable streaming, timeouts, web search |
| `data/SettingsRepository.kt`, `data/SecretCipher.kt` | Settings; secrets encrypted with an Android Keystore key |
| `service/ChatRequestService.kt` | Foreground service + Ongoing Activity while a request runs |
| `ui/` | Compose for Wear OS: swipe-to-dismiss navigation, chat and settings screens, TTS |
| `assist/AssistServices.kt` | Voice interaction service so the watch can treat the app as its assistant |

## Known limitations

- Only the current chat is kept; a seen chat is replaced on the next launch.
- While a reply is in progress an Ongoing Activity icon shows on the watch face (asks for notification permission once).
- The `--es prompt "..."` test hook on `MainActivity` only works in debug builds.

## License

MIT, see [LICENSE](LICENSE).
