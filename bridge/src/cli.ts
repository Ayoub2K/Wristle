#!/usr/bin/env node
/** wristle-bridge command line. */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { addProject, ConfigError, loadConfig, removeProject, saveConfig } from "./config.js";
import { startDaemon } from "./daemon.js";
import { DaemonAlreadyRunningError, isListening, request } from "./ipc.js";
import { createLogger } from "./log.js";
import { configDir, ipcPath, logFile } from "./paths.js";
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
    console.error(e instanceof ConfigError ? e.message : e);
    process.exitCode = EXIT.error;
  },
);
