/** Just enough adb to put the Claude API key on the watch for the Chat half, without the relay. */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const WATCH_PACKAGE = "dev.ayoub.wristle";

const exe = process.platform === "win32" ? "adb.exe" : "adb";

/** adb from ANDROID_HOME / ANDROID_SDK_ROOT, the default Android Studio SDK folder, or PATH. */
export function findAdb(env: NodeJS.ProcessEnv = process.env): string | null {
  const sdkDirs = [
    env.ANDROID_HOME,
    env.ANDROID_SDK_ROOT,
    process.platform === "win32" && env.LOCALAPPDATA ? path.join(env.LOCALAPPDATA, "Android", "Sdk") : undefined,
    process.platform === "darwin" ? path.join(os.homedir(), "Library", "Android", "sdk") : undefined,
    process.platform === "linux" ? path.join(os.homedir(), "Android", "Sdk") : undefined,
  ];
  for (const dir of sdkDirs) {
    const candidate = dir && path.join(dir, "platform-tools", exe);
    if (candidate && fs.existsSync(candidate)) return candidate;
  }
  return spawnSync(exe, ["version"], { encoding: "utf8", timeout: 10_000 }).status === 0 ? exe : null;
}

function run(adb: string, args: string[], timeoutMs = 20_000): { ok: boolean; out: string } {
  const result = spawnSync(adb, args, { encoding: "utf8", timeout: timeoutMs });
  return { ok: result.status === 0, out: `${result.stdout ?? ""}${result.stderr ?? ""}` };
}

export interface Device {
  serial: string;
  model: string;
}

/** Devices that are connected and authorised. */
export function listDevices(adb: string): Device[] {
  return run(adb, ["devices", "-l"])
    .out.split(/\r?\n/)
    .map((line) => line.trim().split(/\s+/))
    .filter((parts) => parts[1] === "device" && parts[0])
    .map((parts) => ({
      serial: parts[0] as string,
      model: (parts.find((part) => part.startsWith("model:")) ?? "model:unknown").slice("model:".length).replace(/_/g, " "),
    }));
}

/** Watches with wireless debugging on that announce themselves on the network ("ip:port"). */
export function discoverWireless(adb: string): string[] {
  return parseMdns(run(adb, ["mdns", "services"]).out);
}

export function parseMdns(output: string): string[] {
  return output
    .split(/\r?\n/)
    .filter((line) => line.includes("_adb-tls-connect"))
    .map((line) => line.trim().split(/\s+/).at(-1) ?? "")
    .filter((address) => /^[\d.]+:\d+$/.test(address));
}

export function connect(adb: string, address: string): boolean {
  return /connected to/i.test(run(adb, ["connect", address]).out);
}

export function pairDevice(adb: string, address: string, code: string): { ok: boolean; message: string } {
  const result = run(adb, ["pair", address, code], 30_000);
  return { ok: /successfully paired/i.test(result.out), message: result.out.trim() };
}

export function isInstalled(adb: string, serial: string): boolean {
  return run(adb, ["-s", serial, "shell", "pm", "path", WATCH_PACKAGE]).out.includes("package:");
}

/** API keys only contain these; anything else could break out of the remote shell command. */
export const isSafeKey = (key: string): boolean => /^[A-Za-z0-9_-]{20,300}$/.test(key);

/** Hands the key to the app's adb-only key activity (it requires the shell's WRITE_SECURE_SETTINGS). */
export function sendKey(adb: string, serial: string, key: string): boolean {
  if (!isSafeKey(key)) throw new Error("this key has unexpected characters; it was not sent");
  const result = run(adb, ["-s", serial, "shell", "am", "start", "-n", `${WATCH_PACKAGE}/.KeySetupActivity`, "--es", "api_key", key]);
  return result.ok && !/error/i.test(result.out);
}
