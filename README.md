# Wristle

A voice-first assistant for the Samsung Galaxy Watch 4 (Wear OS) that talks directly to the Claude API — no phone or PC needed at runtime. Ask a question by voice, watch the answer stream in, and hear it read aloud.

> Unofficial hobby project. Not affiliated with, sponsored or endorsed by Anthropic. "Claude" is a trademark of Anthropic, PBC, and is used here only to describe which API the app talks to.

Roadmap and milestone status: [docs/phase-1-plan.md](docs/phase-1-plan.md).

## Features

- Opens straight into listening; the reply streams to the screen and is read aloud sentence by sentence.
- Start it from the Home key (as digital assistant), a tile, or the app list.
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
   The pairing port and the debug port are different. If `adb pair` fails with `protocol fault`, you most likely used the debug port: `adb mdns services` lists both (`_adb-tls-pairing` and `_adb-tls-connect`).
4. Send the API key (once, and again after changing the key or clearing app data):
   - Windows (PowerShell): `.\setup-key.ps1`
   - macOS / Linux / Git Bash: `./setup-key.sh`

   `KeySetupActivity` is protected by `WRITE_SECURE_SETTINGS`, which normal apps can't hold, so only `adb shell` can start it. You can also enter the key on the watch itself: without a key the app opens the system text input on launch, and you can change it later under ⚙ → Claude API key.

## Launching Wristle

Verified on a Galaxy Watch 4 with One UI Watch 8.

**Home key (press and hold)**
1. **Settings → Apps → Default apps → Digital assistant app → Wristle** (menu names vary slightly per One UI Watch version).
2. **Settings → Advanced features → Customize keys → Home key → Press and hold →** the digital assistant option.

Pressing and holding the Home key now opens Wristle, which starts listening right away. Speech recognition for other apps keeps working.

**Tile**
Swipe left from the watch face to the end, tap **+ Add tile** and choose **Wristle**. Its mic button opens the app into listening.

The app list always works too.

## Project layout

| File | Purpose |
|---|---|
| `MainActivity.kt` | Entry point: reopen the current chat or start a new one and listen |
| `KeySetupActivity.kt` | Receives the API key via `adb shell` (no UI) |
| `AppContainer.kt`, `WristleApplication.kt` | Manual DI: repositories and the app-wide scope |
| `data/ChatRepository.kt` | Request lifecycle, chat state, retry, persistence |
| `data/ChatSession.kt`, `data/ChatStore.kt` | Chat model, stored in `noBackupFilesDir` |
| `chat/ChatBackend.kt` | Interface for where replies come from |
| `chat/ClaudeApiBackend.kt` | Anthropic Java SDK: cancellable streaming, timeouts, web search, error mapping |
| `data/SettingsRepository.kt`, `data/SecretCipher.kt` | Settings; secrets encrypted with an Android Keystore key |
| `service/ChatRequestService.kt` | Foreground service + Ongoing Activity while a request runs |
| `speech/` | Speech input via the watch recognizer; read-aloud via TTS |
| `ui/` | Compose for Wear OS: swipe-to-dismiss navigation, chat and settings screens |
| `assist/AssistServices.kt` | Voice interaction service so the watch can treat the app as its assistant |
| `tile/AskTileService.kt` | Tile with a mic button that opens the app into listening |

## Known limitations

- Only the current chat is kept; a seen chat is replaced on the next launch.
- While a reply is in progress an Ongoing Activity icon shows on the watch face (asks for notification permission once).
- The `--es prompt "..."` test hook on `MainActivity` only works in debug builds.

## License

MIT, see [LICENSE](LICENSE).
