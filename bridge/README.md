# wristle-bridge

Runs on your computer and connects Claude Code to the Wristle watch app. Works on Windows, macOS and Linux (Node.js 20+).

**Status:** phase 2, milestone P2-M2b. It runs as a daemon, manages the list of folders where sessions may start, and pairs with the watch over your own Firebase relay with end-to-end encryption ([setup](../docs/firebase-setup.md)). Claude Code sessions come in later milestones; see [docs/phase-2-plan.md](../docs/phase-2-plan.md).

## Try it

```bash
cd bridge
npm ci
npm run build
node dist/cli.js doctor
node dist/cli.js projects add <path-to-a-project>
node dist/cli.js serve          # keep this running
node dist/cli.js status         # in a second terminal
node dist/cli.js stop
node dist/cli.js pair <code>    # the code shown under Code on the watch
node dist/cli.js ping           # encrypted round trip to the watch
```

To type `wristle-bridge` instead of `node dist/cli.js`, run `npm link` once in this folder. It points the global command at this checkout, so later `npm run build`s are picked up; `npm unlink -g wristle-bridge` removes it.

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
