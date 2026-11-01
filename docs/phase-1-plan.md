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
| `ACTION_RECOGNIZE_SPEECH` is handled by **two** activities: Google `com.google.android.tts` (GoogleTTSActivity) and Samsung Keyboard `com.samsung.android.honeyboard` (RemoteInputActivity). Which one opens, or whether a chooser appears, is untested | Verified via adb; behaviour: M2 |
| `RecognitionService`: only Google's (`com.google.android.tts/…GoogleTTSRecognitionService`), and it is the selected `voice_recognition_service` | Verified via adb, before installing Wristle |
| Current digital assistant: `com.google.android.wearable.assistant`; `voice_interaction_service` is empty | Verified via adb |
| TTS engines installed: Samsung (`com.samsung.SMT`) and Google (`com.google.android.tts`); no default set in secure settings | Verified via adb |
| Whether a third-party app can be picked as digital assistant on One UI Watch | **Unverified**: M4 |
| Whether "double press Home key → open app" can open any app | **Unverified**: M4 |
| Microphone quality is weaker than on newer watches | Reported by the owner; measure in M2 |
| The Claude API does not accept audio input (no speech-to-text) | **To verify** against current API docs before the M2 decision |

## Current state (after M1)

What the app does today:

- Compose for Wear OS UI, streaming via the Anthropic Java SDK, server-side web search.
- Speech input: `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` launched from `MainActivity`. If no recognizer activity exists, it **fails silently**.
- Speech output: system `TextToSpeech` (`ui/ReplySpeaker.kt`).
- The app registers a `VoiceInteractionService` plus a **stub `RecognitionService`** that always returns an error. While the app is the active assistant, this may break system speech recognition. That is a suspected cause of "no selected voice recognition service".
- The API key is stored AES-GCM-encrypted with a key held in the Android Keystore (`data/SecretCipher.kt`). This is what EncryptedSharedPreferences does internally, and that library is deprecated, so we keep this approach.
- The key is set via `adb` (`setup-key.ps1` / `setup-key.sh`) or typed on the watch.

## Milestones

### M1: Base app runs on the Watch 4 ✅ (verified on the watch, 2026-11-01)

- Set up the project under the package `dev.ayoub.wristle`.
- Removed the Notion integration, the Russian text and the Anthropic name and logo.
- English strings plus a Dutch translation; English system prompt; date format follows the device locale.
- Windows key setup script; English README with sideload steps.

**Done when:** the release APK installs on the Watch 4, the key can be set, and a question (spoken, or typed via the debug `--es prompt` hook) gets a streamed and spoken reply.

### M2: Speech input diagnosis

Goal: know exactly why and when speech fails on the Watch 4 before choosing a fix.

- Show a visible error instead of failing silently (no recognizer found, recognizer error, empty result).
- Add a small diagnostics screen in settings (debug builds) that shows:
  - the Wear OS / SDK version;
  - the activities that handle `ACTION_RECOGNIZE_SPEECH`;
  - `SpeechRecognizer.isRecognitionAvailable()` and, on API 31+, `isOnDeviceRecognitionAvailable()`;
  - the current `assistant` / `voice_interaction_service` secure settings;
  - TTS engine and available voices for nl-NL and en-US.
- Test matrix on the watch: app as assistant vs. not, Dutch vs. English, quiet vs. noisy room.
- Verify against the API docs that the Claude API has no speech-to-text.
- Write the findings and the STT options (below) to `docs/stt-decision.md`.

**Done when:** the owner has the findings and picks an STT option.

#### STT options to present in M2

| Option | Pros | Cons |
|---|---|---|
| **A. System recognizer** (current `RecognizerIntent`, or `SpeechRecognizer` bound to a specific service) | Free, no extra key, low latency, no audio leaves the watch except via the recognizer vendor | Depends on what Samsung/Google installed; may be missing or broken on the Watch 4; little control over language and timeouts |
| **B. Record audio + cloud STT API** (the watch records with `AudioRecord` and sends it to an STT service, then the text goes to Claude) | Works independently of installed recognizers; good accuracy for Dutch with a strong model; full control over UX | Second API key on the watch (or a proxy, see M5); costs per minute; extra latency; audio leaves the device; needs `RECORD_AUDIO` |
| **C. On-device model** (e.g. a small Whisper or Vosk model) | Offline and private, no extra cost | Heavy for a watch CPU and RAM: likely slow and battery-hungry; large APK; quality for Dutch is uncertain. Feasibility unverified |
| **D. STT behind our own proxy** (B, but the proxy calls the STT service) | No STT key on the watch; can be combined with the Claude proxy | Requires running a server (see M5) |

Which STT providers to compare (accuracy for Dutch, price, latency) is part of M2's research, not decided here.

### M3: Reliable speech input

Implement the option chosen in M2 behind a `SpeechInput` interface (see [Architecture target](#architecture-target)).

- Move speech handling out of `MainActivity` into `speech/`.
- Clear listening states on screen (listening, processing, error with retry).
- If audio recording is chosen: permission flow, max duration, silence detection or tap-to-stop, haptic feedback at start and end.
- Language handling: follow the device locale, with a setting to force nl-NL or en-US.
- Keep the system recognizer as a fallback if it works.

**Done when:** ten spoken questions in a row work on the watch, in Dutch and in English, without falling back to typing.

### M4: Fast launch

- Test whether Wristle can be selected as digital assistant on One UI Watch, and whether long-pressing the side button then opens it.
- Check whether the stub `RecognitionService` is still needed. If being the assistant breaks speech for other apps, decide with the owner whether assistant mode is worth it.
- Alternatives, in order of preference, depending on what works:
  1. Samsung "double press Home key" → open Wristle (system setting, no code);
  2. a **Tile** with a single "Ask" button that opens straight into listening;
  3. a **complication** that launches the app from the watch face;
  4. the app list (always works).
- Document the working setup in the README.

**Done when:** Wristle starts listening within two actions from the watch face, and the README explains how.

### M5: Backend seam and key handling

- Introduce a `ChatBackend` interface; `ClaudeApiBackend` wraps the current `ClaudeRemoteDataSource`. `ChatRepository` only depends on the interface.
- Make the API base URL configurable, so the same backend can talk to the Claude API directly or to a proxy.
- Present the proxy decision to the owner:

| Option | Pros | Cons |
|---|---|---|
| **Key on the watch** (current, Keystore-encrypted) | Simplest, no server, lowest latency | Anyone with physical access plus adb or a rooted watch could try to extract it; rotating the key means re-sending it |
| **Small own proxy** (e.g. a serverless function) holding the Claude key (and STT key); the watch authenticates with its own revocable token | The real key never touches the watch; can add spending limits, logging and STT in one place; easy to revoke one device | A server to deploy and keep running; a little extra latency; the proxy must be secured (token auth, HTTPS, rate limits) |

- Implement the chosen option.

**Done when:** the app works with the chosen key setup, and a second backend can be added by implementing `ChatBackend` without changing UI or speech code.

### M6: Daily-use polish (optional, decide after M5)

Candidates, to prioritise with the owner after real use:

- Release signing with an own keystore.
- Battery and latency check over a day of normal use.
- Shorter default replies or a "short / normal" toggle.
- Chat history beyond the current conversation.
- Upgrade dependencies (anthropic-java 3.x, AGP) as a separate, tested change.

## Architecture target

The goal is clear seams, so the phase 2 computer bridge fits in without a rewrite.

```
ui/            Compose screens, view models. Knows only the interfaces below.
speech/        SpeechInput  - suspend fun listen(language): SpeechResult
               SpeechOutput - speak(text), stop()          (TTS, current ReplySpeaker)
chat/          ChatBackend  - suspend fun stream(history, onDelta): String
               ClaudeApiBackend (direct API or via proxy)
               (phase 2: BridgeBackend talking to the computer bridge)
data/          ChatRepository (state, persistence), SettingsRepository, SecretCipher
service/       Foreground service while a request runs
assist/        Voice interaction / launch entry points
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
