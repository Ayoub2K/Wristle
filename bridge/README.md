# wristle-bridge

Runs on your computer and connects Claude Code to the Wristle watch app. Works on Windows, macOS and Linux (Node.js 20+).

**Status:** phase 2, milestone P2-M3. It runs as a daemon, manages the list of folders where sessions may start, pairs with the watch over your own Firebase relay with end-to-end encryption ([setup](../docs/firebase-setup.md)), and runs read-only Claude Code sessions that you start from the watch. Approvals and terminal sessions come in later milestones; see [docs/phase-2-plan.md](../docs/phase-2-plan.md).

## Set up

```bash
cd bridge
npm ci
npm run build
npm link                         # once: makes the `wristle` command available everywhere
wristle setup
```

`setup` first asks, with the arrow keys, what to set up:

- **Chat**: only puts your Claude API key on the watch. The key is checked against the API, then sent over adb (it finds the watch on your Wi-Fi and helps pair adb if needed), or through the bridge when the watch is already paired.
- **Code**: walks through everything in one go: the Firebase relay (it explains the few browser steps and does the rest itself: rules, anonymous sign-in, registering the watch app, its config in `local.properties`, and optionally building and installing the watch app), your Claude API key (checked against the API and saved in the bridge config), the folders the watch may start sessions in, pairing with the watch (open **Code** on the watch for the code) and sending the API key to the watch, so it never has to be typed there. It ends by starting the bridge in that window.

## All commands

```bash
node dist/cli.js doctor
node dist/cli.js projects add <path-to-a-project>
node dist/cli.js serve          # keep this running
node dist/cli.js status         # in a second terminal
node dist/cli.js stop
node dist/cli.js pair [<code>]  # the code shown under Code on the watch; asks for it when left out
node dist/cli.js ping           # encrypted round trip to the watch
node dist/cli.js key            # which API key is in use; `key set`, `key send` (to the watch), `key remove`
```

To type `wristle` instead of `node dist/cli.js`, run `npm link` once in this folder (`wristle-bridge` works too). It points the global command at this checkout, so later `npm run build`s are picked up; `npm unlink -g wristle-bridge` removes it.

## Sessions from the watch

1. Give the bridge an API key from the [Claude Console](https://platform.claude.com) (`wristle setup` or `wristle key set`). Watch-started sessions use the Claude Agent SDK, which must run on an API key (not a claude.ai login). The key is stored in the bridge's config folder (readable only by you on macOS and Linux) and wins over `ANTHROPIC_API_KEY`, which is only used when no key is saved.
2. Allow at least one project: `wristle projects add <folder>`.
3. Start the daemon and keep it running: `wristle serve`.
4. On the watch, open **Code**, tap a project and say what you want to know.

Sessions are read-only for now (they can read and search the project, nothing else) and run one at a time. Each one costs API usage; the watch shows the cost when it finishes. `wristle model <id>` picks the model (`wristle model default` goes back to the Agent SDK's default).

## Encryption

Pairing is an X25519 key exchange. HKDF-SHA256 turns the shared secret into one AES-256-GCM key per direction and a 6-digit security code that both screens show. Every message carries a sequence number (bound to the pairing and direction as authenticated data) and a timestamp, so replayed, reordered or stale messages are rejected. The watch implements the same protocol; [`relay/test-vectors.json`](../relay/test-vectors.json) (regenerate with `node scripts/vectors.mjs` after a build) keeps both sides byte-for-byte in step.

## Where things live

| | Windows | macOS | Linux |
|---|---|---|---|
| Config and logs | `%APPDATA%\wristle` | `~/Library/Application Support/wristle` | `$XDG_CONFIG_HOME/wristle` (default `~/.config/wristle`) |
| Local IPC | named pipe `\\.\pipe\wristle-bridge-<id>` | `bridge.sock` in the config folder | `bridge.sock` in the config folder |

Set `WRISTLE_BRIDGE_HOME` to use another folder (the tests do this).

## Development

```bash
npm test          # unit tests (paths per OS, project allowlist, daemon over IPC, crypto, pairing)
npm run smoke     # builds must exist: runs the real CLI end to end
```

CI runs both on Windows, macOS and Linux with Node 20 and 22 (`.github/workflows/bridge.yml`).
