# Phase 1 plan: standalone assistant on the Galaxy Watch 4

## Goal

A voice assistant on the Samsung Galaxy Watch 4 that talks directly to the Claude API over the watch's own Wi-Fi/LTE connection. No phone or computer is needed at runtime. Ask by voice, see the reply stream in, hear it read aloud.

Phase 2 (remote control of a computer) is **out of scope** here. Phase 1 must only keep the architecture open for it (see [Architecture target](#architecture-target)).

## Ground rules

- Every milestone ends in something that can be installed and tested on the watch.
- Big choices (speech-to-text, proxy, relay) are proposed with pros and cons and decided by the owner, never picked by the implementer.
- No assumptions about what the Watch 4 can do: verify in code, documentation or on the device, and record what is still unverified.
- API keys are never committed and never compiled into the APK.
- No Anthropic name or logo. "Claude" is used only to describe which API the app calls. The accent colour is Claude's terracotta, by the owner's choice.

## Device facts

| Fact | Status |
|---|---|
| Galaxy Watch 4 launched with Wear OS 3 (API 30); app `minSdk` is 30 | Known |
| Owner's watch: SM-R870 (Watch 4, 44 mm, Bluetooth/Wi-Fi), Android 16 / **API 36** (Wear OS 6), One UI Watch 8 (`ro.build.version.oneui=80000`), build `R870XXS2JZG3`, locale nl-NL. Matches `targetSdk` 36 | Verified via adb (2026-11-01) |
| `ACTION_RECOGNIZE_SPEECH` is handled by **two** activities: Google `com.google.android.tts` (GoogleTTSActivity) and Samsung Keyboard `com.samsung.android.honeyboard` (RemoteInputActivity). In practice Google's opens, without a chooser, and recognises Dutch well | Verified on the watch (M2) |
| `RecognitionService`: only Google's (`com.google.android.tts/…GoogleTTSRecognitionService`), and it is the selected `voice_recognition_service` | Verified via adb, before installing Wristle |
| Current digital assistant: `com.google.android.wearable.assistant`; `voice_interaction_service` is empty | Verified via adb |
| TTS engines installed: Samsung (`com.samsung.SMT`, the default) and Google (`com.google.android.tts`). Samsung's has **no Dutch voice** (`nld-NLD is LANG_NOT_SUPPORTED`) and stays silent; Google's speaks Dutch | Verified via adb and on the watch (M2) |
| Media volume (`STREAM_MUSIC`, which TTS uses) was 0 out of the box | Verified via adb |
| A third-party app **can** be the digital assistant on One UI Watch 8: Wristle holds `android.app.role.ASSISTANT` | Verified (M4) |
| Home key "press and hold" set to the digital assistant is stored as `setting_home_key_long_press=googlevoiceassistant/googlevoiceassistant` and opens Wristle | Verified (M4) |
| With Wristle as assistant, `voice_recognition_service` stays Google's; the stub `RecognitionService` does **not** replace it | Verified via adb (M4) |
| "Double press Home key" stores an arbitrary activity (`setting_home_key_double_press=<package>/<activity>`, currently Google Wallet) | Seen via adb; not used |
| Microphone quality | No problems for the owner in normal use |

## Current state (after M4)

What the app does today:

- Compose for Wear OS UI with chat bubbles and a large mic button; streaming via the Anthropic Java SDK, server-side web search.
- Speech input: `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` launched from `MainActivity` (Google's recognizer on the Watch 4). Failures show a short message.
- Speech output: system `TextToSpeech` (`ui/ReplySpeaker.kt`), read sentence by sentence while the reply streams. Falls back to Google's engine when the default can't speak the device language, and remembers that choice. The screen stays on while a reply streams or is spoken.
- Launch: Home key press and hold (Wristle as digital assistant), a **Wristle tile**, or the app list.
- The app registers a `VoiceInteractionService` plus a stub `RecognitionService`; on the Watch 4 this does not affect system speech recognition (see device facts).
- The API key is stored AES-GCM-encrypted with a key held in the Android Keystore (`data/SecretCipher.kt`). This is what EncryptedSharedPreferences does internally, and that library is deprecated, so we keep this approach.
- The key is set via `adb` (`setup-key.ps1` / `setup-key.sh`) or typed on the watch.

## Milestones

### M1: Base app runs on the Watch 4 ✅ (verified on the watch, 2026-11-01)

- Set up the project under the package `dev.ayoub.wristle`.
- Removed the Notion integration, the Russian text and the Anthropic name and logo.
- English strings (the Dutch translation was later removed: the UI is English only); English system prompt; date format follows the device locale.
- Windows key setup script; English README with sideload steps.

**Done when:** the release APK installs on the Watch 4, the key can be set, and a question (spoken, or typed via the debug `--es prompt` hook) gets a streamed and spoken reply.

### M2: Speech input ✅ (verified on the watch, 2026-11-01)

The system recognizer (Google's, via `RecognizerIntent`) works well on the Watch 4, including Dutch, so **no external speech-to-text is needed**. The STT options (cloud STT, on-device model, STT behind a proxy) are dropped unless recognition quality becomes a problem.

- Speech failures show a message instead of failing silently: no recognizer, no match, no connection, microphone error. Swiping the recognizer away is treated as a cancel.
- Read-aloud fixed: Google TTS fallback for Dutch (remembered), sentence-by-sentence reading while streaming, screen kept on while answering or speaking, a warning when media volume is 0.

### M3: Reliable speech input (dropped)

Not needed after M2: the system recognizer is reliable. Moving speech handling into `speech/` behind a `SpeechInput` interface is done as part of M5's architecture work instead.

### M4: Fast launch ✅ (verified on the watch, 2026-11-01)

- Wristle can be the digital assistant on One UI Watch 8; Home key press and hold then opens it straight into listening.
- A **Wristle tile** with one large mic button opens the app into listening (`tile/AskTileService.kt`, Wear Tiles + ProtoLayout).
- The README documents both setups.

### M5: Backend seam and key handling ✅ (code done, 2026-11-01)

**Decision (owner, 2026-11-01): the API key stays on the watch**, Keystore-encrypted, for now. Option considered and not chosen yet: a small own proxy (e.g. a serverless function) that holds the key while the watch uses its own revocable token. Pros: the real key never touches the watch, spending limits and logging in one place. Cons: a server to deploy and secure, a little extra latency. Revisit if phase 2 needs a server or relay anyway.

- `chat/ChatBackend` interface: `unavailable()` and `reply(history, onDelta)`; failures are thrown as `ChatBackendException` carrying a backend-neutral `Failure`.
- `chat/ClaudeApiBackend` implements it with the Anthropic Java SDK and maps SDK errors to `Failure`. `chat/StreamAccumulator` moved along.
- `ChatRepository` depends only on `ChatBackend`; the backend is wired in `AppContainer`.
- Speech moved to `speech/`: `SystemSpeechInput` (recognizer launch and error messages) and `ReplySpeaker` (TTS).
- No configurable base URL: not needed while there is no proxy.

**Done when:** the app behaves the same on the watch, and a second backend can be added by implementing `ChatBackend` without changing UI or speech code.

### M6: Daily-use polish ✅ (2026-11-01)

Chosen by the owner and done:

- **Start screen**: two half moons on the round display, *Chat* (left, terracotta) and *Code* (right, darker terracotta), split by a straight line. *Code* shows "coming in a later version" until phase 2. The app list opens this screen; the Home key and the tile still go straight to listening.
- **Short / longer replies** toggle in settings (1–3 sentences vs. up to ~8).
- **Chat history**: the last 20 chats are kept (`history.json`); a past chat can be reopened and continued. History button (clock icon) next to the settings button.
- **Dependencies**: anthropic-java 3.3.0, AGP 9.5.0, Wear Tiles 1.6.2, ProtoLayout 1.4.2. okhttp stays on 4.12, the version the SDK itself uses.
- **English-only UI**; speech recognition and read-aloud follow the watch language. The prompt's date is in English, so Claude answers in the language of the question.
- **New launcher icon**: a small octopus whose middle tentacles form a "w" (an original design, not Anthropic's logo or mascot).
- README rewritten with screenshots and a compatibility section.

Not done (not chosen for now): release signing with an own keystore, a battery and latency measurement over a day.

## Architecture target

The goal is clear seams, so the phase 2 computer bridge fits in without a rewrite.

```
ui/            Compose screens, view models.
speech/        SystemSpeechInput - listen() via the watch's recognizer, with error messages
               ReplySpeaker      - reads a streaming reply aloud sentence by sentence (TTS)
chat/          ChatBackend       - unavailable(), suspend fun reply(history, onDelta): String
               ClaudeApiBackend  - Claude API with the key on the watch
               (phase 2: BridgeBackend talking to the computer bridge)
data/          ChatRepository (state, persistence), SettingsRepository, SecretCipher
service/       Foreground service while a request runs
assist/        Voice interaction service (Home key launch)
tile/          Tile with a mic button
ui/home/       Start screen (Chat | Code), ui/history/ past chats
```

Rules:

- UI never talks to the network or to Android speech APIs directly.
- `ChatRepository` depends on `ChatBackend`, never on the Anthropic SDK.
- Backend choice is a setting, wired in `AppContainer`.
- Transport details (HTTPS endpoint, auth token, later the relay and E2E encryption) stay inside the backend implementation.

## Testing

- Unit tests (`./gradlew :app:testDebugUnitTest`) for chat state, persistence and stream handling. Add tests for each new interface implementation where it is not Android-bound.
- `./gradlew :app:ktlintCheck :app:lintRelease` must pass (lint treats warnings as errors).
- Every milestone has a manual test on the Watch 4, written down in its "Done when".

## Risks

| Risk | Mitigation |
|---|---|
| No usable speech recognizer on the Watch 4 | M2 diagnosis; options B, C or D |
| Assistant mode breaks other apps' speech | Test in M4; fall back to tile or double press |
| The API key leaks from the watch | Keystore encryption now; proxy option in M5 |
| API costs from a default high-end model | Default effort is low; model choice in settings; spending limits via the proxy if chosen |
| Battery drain from streaming and the foreground service | Release build only on the watch; measure in M6 |
