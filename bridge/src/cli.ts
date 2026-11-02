#!/usr/bin/env node
/** wristle-bridge command line. */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createInterface } from "node:readline/promises";
import { CryptoError } from "./crypto.js";
import { addProject, ConfigError, loadConfig, removeProject, saveConfig } from "./config.js";
import { startDaemon } from "./daemon.js";
import { DaemonAlreadyRunningError, isListening, request } from "./ipc.js";
import { createLogger } from "./log.js";
import { loadPairing, Mailbox, pair, pairingFile, unpair } from "./pairing.js";
import { configDir, ipcPath, logFile } from "./paths.js";
import { checkRelay, credentialsFile, installServiceAccount, loadServiceAccount, pingWatch, RelayError } from "./relay.js";
import { VERSION } from "./version.js";

const EXIT = { ok: 0, error: 1, usage: 2, notRunning: 3 } as const;

const USAGE = `wristle-bridge ${VERSION}

Usage:
  wristle-bridge serve                    run the bridge daemon (keep it running)
  wristle-bridge status                   show whether the daemon is running
  wristle-bridge stop                     stop a running daemon
  wristle-bridge projects list            list folders where sessions may start
  wristle-bridge projects add <dir> [name]
  wristle-bridge projects remove <id|name|dir>
  wristle-bridge relay setup <key.json>   store the Firebase service account key and check it
  wristle-bridge relay test               push a ping to the watch and wait for its answer
  wristle-bridge pair <code>              pair with the watch (open Code on the watch for the code)
  wristle-bridge unpair                   forget the paired watch
  wristle-bridge ping                     send an encrypted ping to the paired watch
  wristle-bridge model [<id>|default]     model for sessions started from the watch
  wristle-bridge doctor                   check this computer's setup
  wristle-bridge help`;

async function serve(): Promise<number> {
  const log = createLogger();
  let daemon;
  try {
    daemon = await startDaemon(log);
  } catch (e) {
    if (e instanceof DaemonAlreadyRunningError) {
      console.error(e.message);
      return EXIT.error;
    }
    throw e;
  }
  // Ctrl+C works on every OS; SIGTERM is only sent on macOS and Linux. `wristle-bridge stop` works everywhere.
  process.once("SIGINT", () => void daemon.stop());
  if (process.platform !== "win32") process.once("SIGTERM", () => void daemon.stop());
  await daemon.stopped;
  return EXIT.ok;
}

async function status(): Promise<number> {
  const response = await request({ cmd: "status" });
  if (!response) {
    console.log("not running");
    return EXIT.notRunning;
  }
  console.log(`running: pid ${response.pid}, up ${response.uptimeSeconds}s, ${response.projects} allowed project(s)`);
  console.log(`config: ${response.configDir}`);
  console.log(`watch: ${response.watch}`);
  return EXIT.ok;
}

async function stop(): Promise<number> {
  const response = await request({ cmd: "stop" });
  if (!response) {
    console.log("not running");
    return EXIT.notRunning;
  }
  for (let i = 0; i < 50; i++) {
    if (!(await isListening())) {
      console.log("stopped");
      return EXIT.ok;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  console.error("daemon did not stop within 5 seconds");
  return EXIT.error;
}

function projects(args: string[]): number {
  const [sub, ...rest] = args;
  const config = loadConfig();
  switch (sub) {
    case undefined:
    case "list":
      if (config.projects.length === 0) console.log("no allowed projects yet: wristle-bridge projects add <dir>");
      for (const p of config.projects) console.log(`${p.id}  ${p.name}  ${p.path}`);
      return EXIT.ok;
    case "add": {
      if (!rest[0]) return usage("projects add needs a directory");
      const project = addProject(config, rest[0], rest[1]);
      saveConfig(config);
      console.log(`allowed: ${project.name} (${project.path})`);
      return EXIT.ok;
    }
    case "remove": {
      if (!rest[0]) return usage("projects remove needs an id, name or directory");
      const project = removeProject(config, rest[0]);
      saveConfig(config);
      console.log(`removed: ${project.name} (${project.path})`);
      return EXIT.ok;
    }
    default:
      return usage(`unknown projects command: ${sub}`);
  }
}

async function relay(args: string[]): Promise<number> {
  const [sub, file] = args;
  switch (sub) {
    case "setup": {
      if (!file) return usage("relay setup needs the path to the downloaded service account key");
      const account = installServiceAccount(file);
      console.log(`stored key for project ${account.project_id} in ${credentialsFile()}`);
      await checkRelay(account);
      console.log("✓ Firestore reachable; you can delete the downloaded key file now");
      return EXIT.ok;
    }
    case "test": {
      const account = loadServiceAccount();
      console.log("pinging the watch… (keep Settings → Relay test open on the watch)");
      const result = await pingWatch(60_000, account);
      console.log(`✓ watch ${result.watchId.slice(0, 8)} answered in ${result.roundTripMs} ms`);
      return EXIT.ok;
    }
    default:
      return usage(`unknown relay command: ${sub ?? ""}`);
  }
}

async function pairCommand(code: string | undefined): Promise<number> {
  if (!code) return usage("pair needs the code shown on the watch");
  if (loadPairing()) {
    console.error("already paired: run `wristle-bridge unpair` first");
    return EXIT.error;
  }
  console.log("connecting…");
  await pair(code, {
    async confirm(sas) {
      const rl = createInterface({ input: process.stdin, output: process.stdout });
      try {
        console.log(`
  security code: ${sas.slice(0, 3)} ${sas.slice(3)}
`);
        const answer = await rl.question("Does the watch show the same code? Confirm it there too. [y/N] ");
        return answer.trim().toLowerCase().startsWith("y");
      } finally {
        rl.close();
      }
    },
  });
  console.log("✓ paired; try `wristle-bridge ping`");
  if (await isListening()) console.log("restart `wristle-bridge serve` so the daemon picks up the pairing");
  return EXIT.ok;
}

async function unpairCommand(): Promise<number> {
  console.log((await unpair()) ? "unpaired" : "not paired");
  return EXIT.ok;
}

async function ping(): Promise<number> {
  console.log("sending an encrypted ping…");
  let roundTripMs: number;
  // A running daemon owns the mailbox; a second listener would race it for the watch's answers.
  if (await isListening()) {
    const response = await request({ cmd: "ping" }, undefined, 65_000);
    if (!response?.ok) throw new RelayError(String(response?.error ?? "the daemon did not answer"));
    roundTripMs = Number(response.roundTripMs);
  } else {
    const mailbox = await Mailbox.open();
    try {
      roundTripMs = await mailbox.ping();
    } finally {
      mailbox.close();
    }
  }
  console.log(`✓ the watch opened it and answered in ${roundTripMs} ms`);
  return EXIT.ok;
}

function model(id: string | undefined): number {
  const config = loadConfig();
  if (id) {
    if (id === "default") delete config.model;
    else config.model = id;
    saveConfig(config);
  }
  console.log(`model for watch sessions: ${config.model ?? "Agent SDK default"}`);
  return EXIT.ok;
}

async function doctor(): Promise<number> {
  let problems = 0;
  const check = (ok: boolean, label: string, detail: string) => {
    if (!ok) problems++;
    console.log(`${ok ? "✓" : "✗"} ${label}: ${detail}`);
  };
  const major = Number(process.versions.node.split(".")[0]);
  check(major >= 20, "Node.js", `${process.versions.node}${major >= 20 ? "" : " (need 20 or newer)"}`);
  console.log(`  platform: ${process.platform} ${process.arch}`);
  let writable = true;
  try {
    fs.mkdirSync(configDir(), { recursive: true });
    fs.accessSync(configDir(), fs.constants.W_OK);
  } catch {
    writable = false;
  }
  check(writable, "config folder", configDir());
  console.log(`  log: ${logFile()}`);
  console.log(`  ipc: ${ipcPath()}`);
  console.log(`  daemon: ${(await isListening()) ? "running" : "not running"}`);
  console.log(`  allowed projects: ${loadConfig().projects.length}`);
  console.log(`  Firebase key: ${fs.existsSync(credentialsFile()) ? "stored" : "not set up (see docs/firebase-setup.md)"}`);
  console.log(`  watch: ${fs.existsSync(pairingFile()) ? "paired" : "not paired (wristle-bridge pair <code>)"}`);
  // `claude` can be a .cmd shim on Windows, which needs a shell; a fixed command string avoids passing args through it.
  const claude =
    process.platform === "win32"
      ? spawnSync("claude --version", { encoding: "utf8", shell: true, timeout: 15_000 })
      : spawnSync("claude", ["--version"], { encoding: "utf8", timeout: 15_000 });
  check(claude.status === 0, "Claude Code", claude.status === 0 ? claude.stdout.trim() : "not found on PATH (needed for terminal sessions)");
  // Only presence is reported, never the value.
  console.log(`  ANTHROPIC_API_KEY: ${process.env.ANTHROPIC_API_KEY ? "set" : "not set (needed later for watch-started sessions)"}`);
  return problems === 0 ? EXIT.ok : EXIT.error;
}

function usage(message?: string): number {
  if (message) console.error(message + "\n");
  console.error(USAGE);
  return EXIT.usage;
}

async function main(argv: string[]): Promise<number> {
  const [command, ...args] = argv;
  switch (command) {
    case "serve":
      return serve();
    case "status":
      return status();
    case "stop":
      return stop();
    case "projects":
      return projects(args);
    case "relay":
      return relay(args);
    case "pair":
      return pairCommand(args[0]);
    case "unpair":
      return unpairCommand();
    case "ping":
      return ping();
    case "model":
      return model(args[0]);
    case "doctor":
      return doctor();
    case "--version":
    case "version":
      console.log(VERSION);
      return EXIT.ok;
    case undefined:
    case "help":
    case "--help":
    case "-h":
      console.log(USAGE);
      return EXIT.ok;
    default:
      return usage(`unknown command: ${command}`);
  }
}

main(process.argv.slice(2)).then(
  (code) => {
    process.exitCode = code;
  },
  (e: unknown) => {
    console.error(e instanceof ConfigError || e instanceof RelayError || e instanceof CryptoError ? e.message : e);
    process.exitCode = EXIT.error;
  },
);
