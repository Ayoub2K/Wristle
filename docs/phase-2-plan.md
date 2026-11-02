# Phase 2 plan: control Claude Code on your computer from the watch

## Goal

From the *Code* half of Wristle, follow and steer Claude Code sessions running on your own computer: see what they are doing, approve or deny tool use, answer Claude's questions, dictate follow-up instructions, start a new session in an allowed project, and pause or resume. It must work on **macOS, Windows and Linux**.

## Ground rules

Same as phase 1 (small milestones testable on the watch, ask before big choices, verify instead of assume), plus:

- **Nothing runs on the computer unless the owner allowed it**: sessions only start in allowlisted project folders, and permission prompts default to *deny* when the watch doesn't answer in time.
- **End-to-end encryption**: the relay only ever sees ciphertext and routing metadata.
- **No accounts of our own**: pairing is a one-time code plus a short security code checked on both screens.
- **Every bridge feature is tested on all three operating systems** (CI) before it counts as done.

## Decisions (owner, 2026-11-02)

| Topic | Decision | Why / trade-offs |
|---|---|---|
| Bridge | **Own bridge**, written from scratch in this repo | Cross-platform from day one and shaped for Wristle. Existing bridges were studied as reference only. More work than forking. |
| Transport | **Own Firebase relay** | Works anywhere (home Wi-Fi, LTE), with push to the watch. Needs a Firebase project owned by the user. |
| Sessions | **Both**: own terminal sessions *and* sessions started from the watch | See [Two kinds of sessions](#two-kinds-of-sessions). |

## Findings that shaped this plan

- **An existing MIT bridge does not run on Windows as-is** (tested 2026-11-02 on the owner's PC, Node 24): its unit tests (26) and build pass, but the daemon crashes on start because it listens on a Unix socket file (`ENOENT … chmod bridge.sock`). Windows needs a named pipe (`\\.\pipe\…`). It also assumes `~/.config`, `SIGTERM` and tmux/systemd. Not tested on Linux (no Linux machine available).
- **Authentication rules for the Claude Agent SDK** (from the official docs): *"Unless previously approved, Anthropic does not allow third party developers to offer claude.ai login or rate limits for their products, including agents built on the Claude Agent SDK."* The SDK supports an API key (`ANTHROPIC_API_KEY`) or cloud providers (Bedrock, Vertex, Foundry). It bundles a native Claude Code binary.

## Two kinds of sessions

| | Terminal sessions | Watch-started sessions |
|---|---|---|
| Who starts it | You, with `claude` in a terminal | The bridge, when you ask from the watch |
| Login | Your own Claude Code login (subscription) | **API key** on the computer (Agent SDK rule above) |
| How the watch is involved | Claude Code **hooks** installed by the bridge: `PreToolUse` asks the watch to approve, `Notification` and `Stop` send updates | The bridge runs the session through the **Agent SDK** and relays everything: progress, permission requests, questions, follow-ups |
| What the watch can do | Approve/deny tool use, get notified when Claude needs you or is done | Everything: start, follow, approve, answer, dictate follow-ups, pause, resume |
| Open question | Whether hooks on your own subscription session count as "offering claude.ai login" in a product. Likely fine because Claude Code itself runs the session, but **not certain**; check before P2-M6 | none |

## Architecture

```
┌───────────────────────────┐        ┌──────────────────────────┐        ┌──────────────────────────┐
│ Computer                  │        │ Owner's Firebase project │        │ Watch (Wristle)          │
│                           │        │                          │        │                          │
│  claude (terminal) ─hooks─┤        │  Firestore: per-pairing  │  push  │  Code half               │
│                           │ cipher │  mailboxes, ciphertext   │ ─────▶ │   chat/BridgeBackend     │
│  wristle-bridge (Node)  ──┼──────▶ │  only                    │        │   approval / question    │
│   ├ Agent SDK sessions    │ ◀──────┼                          │ ◀───── │   screens, voice input   │
│   ├ IPC (pipe / socket)   │        │  FCM: wake-up pushes     │ cipher │                          │
│   └ project allowlist     │        │                          │        │                          │
└───────────────────────────┘        └──────────────────────────┘        └──────────────────────────┘
```

- **`bridge/`** (new, Node 20+ / TypeScript): a CLI `wristle-bridge` with a long-running `serve` daemon. Local IPC between the hook commands and the daemon uses a **named pipe on Windows** and a **Unix socket on macOS/Linux**. Config lives in the OS's normal place (`%APPDATA%\wristle` on Windows, `~/Library/Application Support/wristle` on macOS, `$XDG_CONFIG_HOME/wristle` on Linux).
- **`relay/`** (new): Firebase config and security rules. The intended design stays on Firebase's free tier: Firestore holds encrypted messages per pairing, the bridge sends FCM wake-up pushes itself, and the watch signs in anonymously. **To verify in P2-M2**: that this works without Cloud Functions (which need the paid Blaze plan), and that FCM works on the standalone Watch 4.
- **Watch app**: a second `ChatBackend` implementation and a transport module, behind the *Code* half. UI, speech and the Claude API chat stay untouched.
- **Crypto**: X25519 key exchange at pairing, a 6-digit security code compared on both screens, AES-256-GCM per message with authenticated routing data, sequence numbers and a time window against replay.

## Milestones

Each milestone ends in something the owner can run or test.

### P2-M1: Bridge skeleton on all three operating systems ✅ (2026-11-02)

Status: unit tests (16) and the end-to-end smoke test pass on the owner's Windows PC (Node 24, named pipe) and in CI on Windows, macOS and Linux with Node 20 and 22 (all 6 jobs green).

- `bridge/` package: CLI (`wristle-bridge serve | status | stop | projects add/list/remove | doctor`).
- Cross-platform config paths, IPC (named pipe / Unix socket), daemon start and stop without POSIX signals, logs.
- Project allowlist.
- GitHub Actions CI on Windows, macOS and Linux: unit tests plus an IPC round-trip test.

**Done when:** CI is green on all three; on the owner's PC, `serve`, `status` and `stop` work.

### P2-M2: Relay and pairing

Split in two, so the relay design is proven before anything is built on it.

**P2-M2a: relay test** ✅ (verified 2026-11-02)

Result on the owner's Galaxy Watch 4 over Wi-Fi, Firebase Spark plan, no Cloud Functions: the bridge's FCM push reached the watch and the pong came back through Firestore in **616–811 ms** with the app open, and with the app in the background and the screen off; **1,056 ms** after two minutes of sleep. The free-tier design works and FCM works on the standalone Watch 4.

- [docs/firebase-setup.md](firebase-setup.md): the owner creates the Firebase project (Spark plan), Firestore with [`relay/firestore.rules`](../relay/firestore.rules), anonymous sign-in, and a service account key for the bridge.
- Bridge: `wristle-bridge relay setup <key.json>` stores the key in the config folder; `wristle-bridge relay test` pushes a ping to the watch via FCM and waits for the pong in Firestore.
- Watch: Firebase is configured from `local.properties` (no `google-services.json`, no Google Services plugin), signs in anonymously, registers its FCM token, and answers pings (**Settings → Relay test**).
- **Done when:** the ping round-trips between the PC and the watch over Wi-Fi. That proves the free-tier design (no Cloud Functions) and FCM on the standalone Watch 4. The owner's watch (SM-R870) has no LTE, so LTE is not tested.

**P2-M2b: pairing and encryption** ✅ (verified 2026-11-02)

Result on the owner's Galaxy Watch 4 and Windows PC: the codes matched on both screens, the bridge's encrypted hello reached the watch (it shows the computer's name) and `wristle-bridge ping` round-tripped encrypted.

- Pairing: the watch shows a code, the bridge enters it, both show the same 6-digit security code.
- Encrypted ping/pong between watch and bridge; the relay sees only ciphertext.
- Built: `wristle-bridge pair <code> | unpair | ping`; the Code half on the watch pairs and shows the encrypted ping count. Crypto: X25519 (Tink on the watch, Node crypto on the bridge), HKDF-SHA256, AES-256-GCM with the pairing, direction and sequence number as AAD, ±5 minute window. Both sides pass the same test vectors ([`relay/test-vectors.json`](../relay/test-vectors.json)) and RFC 7748's X25519 vector. The pairing only counts after both the watch and the computer confirm the security code.

**Done when:** the watch and the PC pair and an encrypted test message round-trips.

### P2-M3: Watch-started sessions, read-only ✅ (verified 2026-11-02)

- Start a session from the watch in an allowlisted project with a spoken instruction (Agent SDK, API key on the computer).
- Follow progress on the watch: status and short summaries.

**Done when:** a spoken instruction starts a session on the PC and the watch shows its progress.

Built and verified on the owner's Galaxy Watch 4 and Windows PC on 2026-11-02: a spoken question started a read-only session, the watch showed its progress, answer and cost.

- `wristle-bridge serve` keeps the encrypted mailbox open when paired and runs sessions with the Claude Agent SDK (`@anthropic-ai/claude-agent-sdk`). One session at a time.
- **Read-only**: the session only gets `Read`, `Glob` and `Grep` (`permissionMode: "dontAsk"`), so it can look but not change anything until approvals arrive in P2-M4. It loads project settings and `CLAUDE.md`, not user settings.
- **API key only**: the bridge refuses to start without `ANTHROPIC_API_KEY`, and stops a session whose init message reports any other credential source.
- The model is the Agent SDK's default unless set with `wristle-bridge model <id>`; the watch shows which model ran.
- Progress goes to the watch at most every 2.5 seconds (a few lines: what Claude says and which files it reads), then the final answer and the cost.
- Watch: the *Code* half lists the allowed projects; tap one and speak the instruction. A running session can be stopped from the watch.

### P2-M4: Approvals and questions

- Permission requests on the watch: approve or deny, deny on timeout.
- Claude's questions answered by tapping a choice or by voice.

**Done when:** a session that needs a tool permission and asks a question can be finished from the watch alone.

### P2-M5: Follow-ups, pause and resume

- Dictate a follow-up instruction to a running or finished session.
- Pause and resume.

### P2-M6: Terminal sessions via hooks

- `wristle-bridge hooks install | uninstall | status`, working on all three operating systems (hook commands, paths and shells differ on Windows).
- Approve your own terminal sessions from the watch; get notified when Claude needs you or is done.
- First, settle the open question about subscription sessions and hooks.

## Risks

| Risk | Mitigation |
|---|---|
| Free-tier Firebase design doesn't work without Cloud Functions | Resolved in P2-M2a: it works on the Spark plan |
| FCM doesn't deliver to the standalone Watch 4 | Resolved in P2-M2a: pushes arrive in under a second, also with the screen off |
| Hooks behave differently per OS or Claude Code version | CI on three operating systems; keep the hook side small |
| API costs of watch-started sessions | Default to a cheaper model; show the model on the watch; spending limit in the Claude Console |
| A malicious or mistaken approval runs something harmful | Allowlisted folders only, deny on timeout, show the exact command before approving |
