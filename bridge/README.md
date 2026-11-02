# wristle-bridge

Runs on your computer and connects Claude Code to the Wristle watch app. Works on Windows, macOS and Linux (Node.js 20+).

**Status:** skeleton (phase 2, milestone P2-M1). It runs as a daemon and manages the list of folders where sessions may start. Pairing with the watch, the relay and Claude Code sessions come in later milestones; see [docs/phase-2-plan.md](../docs/phase-2-plan.md).

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
```

## Where things live

| | Windows | macOS | Linux |
|---|---|---|---|
| Config and logs | `%APPDATA%\wristle` | `~/Library/Application Support/wristle` | `$XDG_CONFIG_HOME/wristle` (default `~/.config/wristle`) |
| Local IPC | named pipe `\\.\pipe\wristle-bridge-<id>` | `bridge.sock` in the config folder | `bridge.sock` in the config folder |

Set `WRISTLE_BRIDGE_HOME` to use another folder (the tests do this).

## Development

```bash
npm test          # unit tests (paths per OS, project allowlist, daemon over IPC)
npm run smoke     # builds must exist: runs the real CLI end to end
```

CI runs both on Windows, macOS and Linux with Node 20 and 22 (`.github/workflows/bridge.yml`).
