/**
 * The long-running bridge daemon. For now it only answers status and stop over IPC;
 * the relay connection and Claude Code sessions are added in later milestones.
 */
import fs from "node:fs";
import type net from "node:net";
import { loadConfig } from "./config.js";
import { type IpcMessage, startIpcServer } from "./ipc.js";
import type { Logger } from "./log.js";
import { configDir, ipcPath, pidFile } from "./paths.js";
import { VERSION } from "./version.js";

export interface Daemon {
  /** Resolves once the daemon has fully shut down. */
  stopped: Promise<void>;
  stop(): Promise<void>;
}

export async function startDaemon(log: Logger): Promise<Daemon> {
  const startedAt = Date.now();
  let server: net.Server | undefined;
  let resolveStopped!: () => void;
  const stopped = new Promise<void>((resolve) => (resolveStopped = resolve));
  let stopping = false;

  const stop = async (): Promise<void> => {
    if (stopping) return stopped;
    stopping = true;
    log("stopping");
    await new Promise<void>((resolve) => (server ? server.close(() => resolve()) : resolve()));
    fs.rmSync(pidFile(), { force: true });
    log("stopped");
    resolveStopped();
  };

  const handle = (message: IpcMessage, reply: (response: IpcMessage) => void): void => {
    switch (message.cmd) {
      case "status":
        reply({
          ok: true,
          version: VERSION,
          pid: process.pid,
          uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
          projects: loadConfig().projects.length,
          configDir: configDir(),
        });
        break;
      case "stop":
        reply({ ok: true });
        // Reply first, then shut down, so the client gets its answer.
        setImmediate(() => void stop());
        break;
      default:
        reply({ ok: false, error: `unknown command: ${String(message.cmd)}` });
    }
  };

  fs.mkdirSync(configDir(), { recursive: true });
  server = await startIpcServer(handle);
  fs.writeFileSync(pidFile(), String(process.pid));
  log(`wristle-bridge ${VERSION} listening on ${ipcPath()} (pid ${process.pid})`);
  return { stopped, stop };
}
