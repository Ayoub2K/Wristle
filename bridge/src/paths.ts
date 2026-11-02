/** Where the bridge keeps its files and listens for local IPC, per operating system. */
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";

export interface Platform {
  platform: NodeJS.Platform;
  env: NodeJS.ProcessEnv;
  homedir: string;
  tmpdir: string;
}

const current = (): Platform => ({
  platform: process.platform,
  env: process.env,
  homedir: os.homedir(),
  tmpdir: os.tmpdir(),
});

/** WRISTLE_BRIDGE_HOME overrides everything (used by tests); otherwise the OS's usual config location. */
export function configDir(p: Platform = current()): string {
  const override = p.env.WRISTLE_BRIDGE_HOME;
  if (override) return override;
  const join = p.platform === "win32" ? path.win32.join : path.posix.join;
  switch (p.platform) {
    case "win32":
      return join(p.env.APPDATA || join(p.homedir, "AppData", "Roaming"), "wristle");
    case "darwin":
      return join(p.homedir, "Library", "Application Support", "wristle");
    default:
      return join(p.env.XDG_CONFIG_HOME || join(p.homedir, ".config"), "wristle");
  }
}

// macOS allows 104 bytes for a Unix socket path, Linux 108; stay safely below both.
const MAX_SOCKET_PATH = 100;

/**
 * Local IPC endpoint between the daemon and other bridge commands.
 * Windows has no Unix socket files, so it uses a named pipe derived from the config dir.
 */
export function ipcPath(p: Platform = current()): string {
  const dir = configDir(p);
  const id = createHash("sha256").update(dir).digest("hex").slice(0, 12);
  if (p.platform === "win32") return `\\\\.\\pipe\\wristle-bridge-${id}`;
  const socket = path.posix.join(dir, "bridge.sock");
  return socket.length <= MAX_SOCKET_PATH ? socket : path.posix.join(p.tmpdir, `wristle-bridge-${id}.sock`);
}

export const configFile = (p?: Platform): string => path.join(configDir(p), "config.json");
export const pidFile = (p?: Platform): string => path.join(configDir(p), "bridge.pid");
export const logFile = (p?: Platform): string => path.join(configDir(p), "logs", "bridge.log");
