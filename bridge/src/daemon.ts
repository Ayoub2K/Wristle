/**
 * The long-running bridge daemon: answers bridge commands over IPC and, once paired with a watch,
 * keeps the encrypted mailbox open and runs the sessions the watch asks for.
 */
import fs from "node:fs";
import type net from "node:net";
import { loadConfig, resolveApiKey } from "./config.js";
import { type IpcMessage, startIpcServer } from "./ipc.js";
import type { Logger } from "./log.js";
import { loadPairing, Mailbox } from "./pairing.js";
import { configDir, ipcPath, pidFile } from "./paths.js";
import { credentialsFile } from "./relay.js";
import { agentSdkQuery, type QueryFn, SessionManager } from "./sessions.js";
import { VERSION } from "./version.js";

export interface Daemon {
  /** Resolves once the daemon has fully shut down. */
  stopped: Promise<void>;
  stop(): Promise<void>;
}

export interface DaemonOptions {
  /** Connect to the watch when paired (off in tests). */
  relay?: boolean;
  query?: QueryFn;
}

export async function startDaemon(log: Logger, options: DaemonOptions = {}): Promise<Daemon> {
  const startedAt = Date.now();
  let server: net.Server | undefined;
  let mailbox: Mailbox | undefined;
  let resolveStopped!: () => void;
  const stopped = new Promise<void>((resolve) => (resolveStopped = resolve));
  let stopping = false;

  const stop = async (): Promise<void> => {
    if (stopping) return stopped;
    stopping = true;
    log("stopping");
    mailbox?.close();
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
          watch: mailbox ? "connected" : loadPairing() ? "paired, not connected" : "not paired",
        });
        break;
      case "ping":
        if (!mailbox) {
          reply({ ok: false, error: "the daemon is not connected to a watch" });
          break;
        }
        mailbox.ping().then(
          (roundTripMs) => reply({ ok: true, roundTripMs }),
          (e: unknown) => reply({ ok: false, error: e instanceof Error ? e.message : String(e) }),
        );
        break;
      case "sendApiKey": {
        // The daemon reads the key itself, so it never travels over the local IPC.
        const apiKey = resolveApiKey(loadConfig());
        if (!mailbox) reply({ ok: false, error: "the daemon is not connected to a watch" });
        else if (!apiKey) reply({ ok: false, error: "no API key on this computer: run `wristle setup`" });
        else {
          mailbox.send({ type: "apiKey", key: apiKey.key }).then(
            () => reply({ ok: true }),
            (e: unknown) => reply({ ok: false, error: e instanceof Error ? e.message : String(e) }),
          );
        }
        break;
      }
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
  if (options.relay ?? true) mailbox = await connectWatch(log, options.query ?? agentSdkQuery);
  return { stopped, stop };
}

async function connectWatch(log: Logger, query: QueryFn): Promise<Mailbox | undefined> {
  if (!loadPairing() || !fs.existsSync(credentialsFile())) {
    log("not paired with a watch; sessions from the watch are off (wristle setup)");
    return undefined;
  }
  const mailbox = await Mailbox.open((e) => log(`relay error: ${e.message}`));
  const sessions = new SessionManager({ query, config: loadConfig, send: (message) => mailbox.send(message), log });
  mailbox.onMessage((message) => {
    sessions.handle(message).catch((e: unknown) => log(`could not handle ${message.type}: ${String(e)}`));
  });
  log("connected to the watch");
  return mailbox;
}
