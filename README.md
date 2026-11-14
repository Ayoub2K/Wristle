<div align="center">

<img src="./docs/logo.svg" alt="Wristle logo" width="128" />

# Wristle

**Talk to the Claude API from your wrist, and run Claude Code on your computer from it too.**

<table>
  <tr>
    <td align="center"><img src="./docs/screenshots/home.png" alt="Start screen with chat and code halves" width="200" /></td>
    <td align="center"><img src="./docs/screenshots/chat.png" alt="A question and a streamed answer" width="200" /></td>
    <td align="center"><img src="./docs/screenshots/empty.png" alt="New chat with the mic button" width="200" /></td>
  </tr>
  <tr>
    <td align="center"><sub><i>Start screen</i></sub></td>
    <td align="center"><sub><i>Ask, read, listen</i></sub></td>
    <td align="center"><sub><i>New chat</i></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./docs/screenshots/settings.png" alt="Settings" width="200" /></td>
    <td align="center"><img src="./docs/screenshots/code.png" alt="Code half: an allowed project on the computer" width="200" /></td>
    <td></td>
  </tr>
  <tr>
    <td align="center"><sub><i>Settings</i></sub></td>
    <td align="center"><sub><i>Code: pick a project, say what to do</i></sub></td>
    <td></td>
  </tr>
</table>

Press and hold the Home key, ask a question out loud, and watch the answer
stream in while it is read back to you. The watch talks straight to the
Claude API over its own Wi-Fi or LTE: no phone, no computer, no server.

Tap **Code** instead, pick a project on your computer and say what you want
to know: a Claude Code session runs there and the answer comes back to your
wrist, end-to-end encrypted.

[What it does](#what-it-does) · [Compatibility](#compatibility) · [How it works](#how-it-works) · [Quick start](#quick-start) · [Launching](#launching-wristle) · [Computer bridge](bridge/README.md) · [Roadmap](docs/phase-2-plan.md)

</div>

---

## What it does

- 🎙️ **Voice first.** Opens straight into listening and uses the watch's own
  speech recognizer (Google's on the Galaxy Watch 4, Dutch works fine).
- 🔊 **Reads replies aloud while they stream,** sentence by sentence. Falls
  back to Google's voice when the default engine can't speak your language.
- 🌐 **Web search** through the Claude API's server-side tool, for weather,
  news, prices and anything recent.
- 🧠 **Pick your model and effort**: Opus 5.5, Sonnet 5.5 or Haiku 5.5, effort
  low to max. Toggle between short (1–3 sentences) and longer replies.
- 🕘 **Chat history.** Your last 20 chats are kept on the watch; reopen one
  and carry on.
- ⚡ **Three ways in**: press and hold the Home key (Wristle as digital
  assistant), a one-tap **tile**, or the app list.
- 💻 **Claude Code from your wrist.** The *Code* half pairs with a small
  bridge on your computer (`wristle setup`; Windows, macOS and Linux). Pick an
  allowed project, say what you want to know, and a read-only Claude Code
  session runs there; you see its progress, the answer and what it cost.
  Approving tool use from the watch is next.
- 🔒 **No accounts, no cloud of its own, no analytics.** Chat only talks to the
  Claude API with your key. Code adds your own free Firebase project as the
  relay, with end-to-end encryption between watch and computer.

---

## Project status

A **personal hobby project** that runs every day on my Galaxy Watch 4; public
because someone else might enjoy it, not because it is production-grade.

- **Unofficial.** Not affiliated with, sponsored or endorsed by Anthropic.
  "Claude" is a trademark of Anthropic, PBC, and is used here only to say
  which API the app talks to.
- **You pay for your own usage** with your own Claude API key (pay-as-you-go).
  Set a spending limit in the Claude Console.
- **The API key lives on the watch,** encrypted with a key from the Android
  Keystore. Anyone with your unlocked watch and developer access could try
  to get at it.
- **Sideload only.** No Play Store build; you install it with `adb`.
- **Expect breakage.** Wear OS, One UI Watch and the APIs underneath all move.

---

## Compatibility

| | |
|---|---|
| **Tested** | Samsung Galaxy Watch 4 (SM-R870), One UI Watch 8, Wear OS 6 / Android 16 |
| **Should work** | Any watch on **Wear OS 3 or newer** (`minSdk` 30): Galaxy Watch 4, 5, 6, 7 and Ultra; Pixel Watch 1, 2 and 3; recent TicWatch and other Wear OS 3+ watches. Untested. |
| **Won't work** | Wear OS 2 and older, Tizen-based Galaxy watches (Watch 3 and earlier), Apple Watch |

What a watch needs:

- **Its own internet connection** (Wi-Fi or LTE) while you ask a question.
- **A speech recognizer** that handles `RecognizerIntent` (Google's ships with
  most Wear OS 3+ watches) and a **text-to-speech engine**.
- **A round screen** looks best; square screens are untested.

Platform notes: the *Home key press and hold* setup below is specific to
Samsung's One UI Watch. On other watches, set Wristle as the digital
assistant and use whatever button your watch maps to the assistant, or just
use the tile.

---

## How it works

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

- **`speech/`** starts the watch's recognizer and reads replies aloud.
- **`chat/`** holds the `ChatBackend` interface. `ClaudeApiBackend` streams
  from the Claude API with the Anthropic Java SDK. Phase 2 adds a second
  backend that talks to a bridge on your computer, without touching UI or
  speech code.
- **`data/`** keeps chat state, history and settings; the API key is
  encrypted with an Android Keystore key.
- **`ui/`** is Compose for Wear OS; **`tile/`** is the one-tap tile;
  **`assist/`** lets the watch use Wristle as its digital assistant.

---

## Quick start

You need Android Studio (for the Android SDK and `adb`), JDK 17+,
[Node.js](https://nodejs.org) 20+ (for the `wristle` command) and a Claude API
key from [platform.claude.com](https://platform.claude.com).

**1. Configure.** Create `local.properties` in the project root (it is
git-ignored):

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

Only if you want to use the adb scripts from step 5, also add
`claude.apiKey=sk-ant-...`. Keys are never compiled into the APK; the API key
reaches the watch after install. For the Code half, `wristle setup` adds the
`firebase.*` lines here itself (step 6).

**2. Install the `wristle` command** (once):

```bash
cd bridge
npm ci
npm run build
npm link
cd ..
```

`npm link` points the global `wristle` command at this checkout, so a later
`npm run build` is picked up automatically.

**3. Build the watch app.**

```bash
./gradlew :app:assembleRelease
```

Use the release build on the watch: the debug build is noticeably slower and
uses more battery. Release builds are signed with `keystore.properties` if
present (see `keystore.properties.example`), otherwise with the debug key.

**4. Connect the watch and install.**

1. On the watch, enable developer options. On a Galaxy Watch: **Settings →
   About watch → Software → tap "Software version"** a few times. On other
   Wear OS watches it is **Build number**, tapped 7 times.
2. **Developer options** → enable **ADB debugging** and **Debug over Wi-Fi**
   (or **Wireless debugging**) → **Pair new device**.
3. On your computer, on the same Wi-Fi:

   ```bash
   adb pair <ip>:<pairing-port>
   adb connect <ip>:<debug-port>
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   The pairing port and the debug port differ. If `adb pair` fails with
   `protocol fault`, you probably used the debug port; `adb mdns services`
   lists both.

**5. Chat: put your API key on the watch** (once, and again after changing
it or clearing app data). Without a key, the app opens on a screen that shows
the options:

- **`wristle setup`** and pick **Chat** with the arrow keys. It checks your
  key against the API and puts it on the watch over adb, finding the watch on
  your Wi-Fi and helping you pair adb if needed.
- **Over adb by hand:** `.\setup-key.ps1` (Windows PowerShell) or
  `./setup-key.sh` (macOS / Linux / Git Bash), with `claude.apiKey` from step 1.
- **Typed on the watch:** possible, but the key is long.

That's all for Chat: open Wristle, tap **Chat** and ask. To start it with the
Home key, see [Launching Wristle](#launching-wristle).

**6. Optional, Code: control Claude Code from the watch.** Run `wristle setup`
again and pick **Code**. It needs your own free Firebase project as the relay
between the watch and your computer, and walks you through it:

- The few steps that need a browser are explained one by one, with links:
  create the Firebase project, download its service account key, create the
  Firestore database and click **Get started** under Authentication.
- The rest it does itself: it publishes the Firestore rules, turns on
  anonymous sign-in, registers the watch app, writes its `firebase.*` values
  into `local.properties` and offers to rebuild and install the watch app.
- Then it asks for your API key (sessions on the computer use it too), the
  folders the watch may start sessions in, and the pairing code that **Code**
  shows on the watch. It sends the key to the watch end-to-end encrypted and
  starts the bridge, which keeps running in that window.

Later, start the bridge with `wristle serve`; `wristle doctor` checks the
setup. More in [`bridge/README.md`](bridge/README.md) and, for the manual
Firebase route, [`docs/firebase-setup.md`](docs/firebase-setup.md).

---

## Launching Wristle

**Home key, press and hold** (verified on One UI Watch 8):

1. **Settings → Apps → Default apps → Digital assistant app → Wristle**.
2. **Settings → Advanced features → Customize keys → Home key → Press and
   hold →** the digital assistant option.

Press and hold the Home key: Wristle opens and starts listening. Speech
recognition in other apps keeps working.

**Tile:** swipe left from the watch face to the end, tap **+ Add tile** and
choose **Wristle**. Its mic button opens straight into listening.

**App list:** opens the start screen with *Chat* and *Code*.

---

## Development

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:ktlintCheck         # style (ktlintFormat to fix)
./gradlew :app:lintRelease         # Android lint, warnings are errors
./gradlew :app:assembleRelease     # APK for the watch
```

The roadmap and milestone status are in [docs/phase-1-plan.md](docs/phase-1-plan.md).
Agent instructions for AI coding tools are in [AGENT.md](AGENT.md).

## License

MIT, see [LICENSE](LICENSE).
