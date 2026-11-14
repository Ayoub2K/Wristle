<div align="center">

<img src="./docs/logo.svg" alt="Wristle logo" width="128" />

# Wristle

**Talk to the Claude API from your wrist, and run Claude Code on your computer from it too.**

<table>
  <tr>
    <td align="center"><img src="./docs/screenshots/home.png" alt="Start screen with chat and code halves" width="200" /></td>
    <td align="center"><img src="./docs/screenshots/chat.png" alt="A question and a streamed answer" width="200" /></td>
    <td align="center"><img src="./docs/screenshots/code.png" alt="Code half: an allowed project on the computer" width="200" /></td>
  </tr>
  <tr>
    <td align="center"><sub><i>Start screen</i></sub></td>
    <td align="center"><sub><i>Ask, read, listen</i></sub></td>
    <td align="center"><sub><i>Code: pick a project, say what to do</i></sub></td>
  </tr>
</table>

Press and hold the Home key, ask a question out loud, and watch the answer
stream in while it is read back to you. The watch talks straight to the
Claude API over its own Wi-Fi or LTE: no phone, no computer, no server.

Tap **Code** instead, pick a project on your computer and say what you want
to know: a Claude Code session runs there and the answer comes back to your
wrist, end-to-end encrypted.

[Get started](#get-started) · [What it does](#what-it-does) · [Compatibility](#compatibility) · [Launching](#launching-wristle) · [Roadmap](docs/phase-2-plan.md)

</div>

---

## What it does

- 🎙️ **Voice first.** Press and hold the Home key and it is listening, with
  the watch's own speech recognizer, in your watch's language.
- 🔊 **Reads replies aloud while they stream,** sentence by sentence.
- 🌐 **Web search** for weather, news, prices and anything recent.
- 🧠 **Pick your model and effort** (Opus, Sonnet or Haiku), and short or
  longer replies.
- 🕘 **Chat history.** Your last 20 chats stay on the watch; reopen one and
  carry on.
- ⚡ **Three ways in**: press and hold the Home key, a one-tap **tile**, or the
  app list.
- 💻 **Claude Code from your wrist.** The *Code* half pairs with a small
  bridge on your computer (Windows, macOS and Linux). Pick a project, say what
  you want to know, and a read-only Claude Code session runs there; you see
  its progress, the answer and what it cost. Approving tool use from the watch
  is next.
- 🔒 **No accounts, no analytics.** Chat only talks to the Claude API with your
  own key. Code goes through your own free Firebase project, end-to-end
  encrypted.

---

## Compatibility

| | |
|---|---|
| **Tested** | Samsung Galaxy Watch 4, One UI Watch 8 (Wear OS 6) |
| **Should work** | Any watch on **Wear OS 3 or newer**: Galaxy Watch 4 to 7 and Ultra, Pixel Watch, recent TicWatch. Untested. |
| **Won't work** | Wear OS 2 and older, Tizen Galaxy watches (Watch 3 and earlier), Apple Watch |

The watch needs its own internet connection (Wi-Fi or LTE) while you ask.

---

## Get started

There are two ways in. Pick one:

- **Chat only:** download the app, install it, add your key. No building.
- **Chat and Code:** build the app yourself. Code talks to your computer
  through *your own* Firebase project (so no server of mine sits in between),
  and the app has to be built with that project in it. `wristle setup` does
  most of the work.

### What you need for both

- A **Claude API key** from [platform.claude.com](https://platform.claude.com).
  You pay for your own usage; set a spending limit there.
- **Wireless debugging** on the watch, to install the app:
  1. Enable developer options: **Settings → About watch → Software → tap
     "Software version"** a few times (other Wear OS watches: **Build
     number**, 7 times).
  2. **Settings → Developer options** → turn on **ADB debugging** and
     **Wireless debugging**.
- **adb** on your computer: the Android
  [platform-tools](https://developer.android.com/tools/releases/platform-tools)
  (or Android Studio, which includes them).

### Chat only

1. Download `wristle-<version>.apk` from the
   [latest release](https://github.com/Ayoub2K/Wristle/releases/latest).
2. On the watch: **Wireless debugging → Pair new device**. On your computer,
   on the same Wi-Fi:

   ```bash
   adb pair <ip>:<pairing-port>     # the code shown on the watch
   adb connect <ip>:<port>          # the port on the Wireless debugging screen, not the pairing one
   adb install -r wristle-<version>.apk
   ```

3. Put your API key on the watch:

   ```bash
   adb shell am start -n dev.ayoub.wristle/.KeySetupActivity --es api_key <your-key>
   ```

   The watch shows **Key saved**. You can also type the key on the watch,
   but it is long.

Open Wristle, tap **Chat** and ask.

### Chat and Code

You also need [Android Studio](https://developer.android.com/studio) (or the
Android SDK with JDK 17+) and [Node.js](https://nodejs.org) 20+.

1. Clone this repository and create `local.properties` in it with the path to
   your Android SDK:

   ```properties
   sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
   ```

2. Install the `wristle` command:

   ```bash
   cd bridge
   npm ci
   npm run build
   npm link
   ```

3. Run the setup and pick **Code** with the arrow keys:

   ```bash
   wristle setup
   ```

   It walks you through everything: the few Firebase steps that need a
   browser (with links), then the rest by itself. It builds the app and
   installs it on the watch, pairs the watch with your computer, sends your
   API key to the watch and starts the bridge.

Keep the bridge running while you use Code (`wristle serve` starts it again
later). On the watch, tap **Code**, pick a project and say what you want to
know. `wristle doctor` checks the setup; more in
[bridge/README.md](bridge/README.md).

> If you installed the release APK before, remove it first
> (`adb uninstall dev.ayoub.wristle`): Android won't install your own build
> over it.

---

## Launching Wristle

- **Home key, press and hold** (One UI Watch): **Settings → Apps → Default
  apps → Digital assistant app → Wristle**, then **Settings → Advanced
  features → Customize keys → Home key → Press and hold →** the digital
  assistant. On other watches, set Wristle as the digital assistant and use
  the button your watch maps to it.
- **Tile:** swipe left from the watch face, tap **+ Add tile** and choose
  **Wristle**. Its mic button opens straight into listening.
- **App list:** opens the start screen with *Chat* and *Code*.

---

## Good to know

- **Unofficial.** Not affiliated with, sponsored or endorsed by Anthropic.
  "Claude" is a trademark of Anthropic, PBC, and is used here only to say
  which API the app talks to.
- **A hobby project.** It runs every day on my Galaxy Watch 4, but Wear OS and
  the APIs underneath move; expect the odd breakage.
- **Your key stays on the watch,** encrypted with a key from the Android
  Keystore. Someone with your unlocked watch and developer access could try
  to get at it.

## Development

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:ktlintCheck         # style (ktlintFormat to fix)
./gradlew :app:lintRelease         # Android lint, warnings are errors
./gradlew :app:assembleRelease     # APK for the watch
```

How Chat works:

```
┌──────────────────┐   voice   ┌───────────────────────────────┐  HTTPS + SSE   ┌──────────────────┐
│ Watch recognizer │ ────────▶ │  Wristle (Wear OS app)        │ ─────────────▶ │  Claude API      │
│ (Google)         │   text    │  speech/  → chat/ → data/     │ ◀───────────── │  /v1/messages    │
└──────────────────┘           │  ChatBackend: ClaudeApiBackend│   streamed     │  + web search    │
                               └───────────────┬───────────────┘   reply        └──────────────────┘
                                               │ sentence by sentence
                                               ▼
                                     ┌──────────────────┐
                                     │ Text-to-speech   │
                                     └──────────────────┘
```

The UI, speech, chat backend and transport are kept apart, so Code adds a second backend (the bridge,
through the encrypted relay) without touching UI or speech. The bridge has its own checks
([bridge/README.md](bridge/README.md)); `setup-key.ps1` / `setup-key.sh` send `claude.apiKey` from
`local.properties` to the watch over adb. Plans:
[phase 1](docs/phase-1-plan.md), [phase 2](docs/phase-2-plan.md). Manual
Firebase setup: [docs/firebase-setup.md](docs/firebase-setup.md). Releases:
[docs/releasing.md](docs/releasing.md). Instructions for AI coding agents:
[AGENT.md](AGENT.md).

## License

MIT, see [LICENSE](LICENSE).
