import { describe, expect, it } from "vitest";
import { configDir, ipcPath, type Platform } from "../src/paths.js";

const base = (platform: NodeJS.Platform, env: NodeJS.ProcessEnv = {}, homedir = "/home/ayoub"): Platform => ({
  platform,
  env,
  homedir,
  tmpdir: "/tmp",
});

describe("configDir", () => {
  it("uses APPDATA on Windows", () => {
    expect(configDir(base("win32", { APPDATA: "C:\\Users\\a\\AppData\\Roaming" }, "C:\\Users\\a"))).toBe(
      "C:\\Users\\a\\AppData\\Roaming\\wristle",
    );
  });

  it("falls back to the roaming folder when APPDATA is missing", () => {
    expect(configDir(base("win32", {}, "C:\\Users\\a"))).toBe("C:\\Users\\a\\AppData\\Roaming\\wristle");
  });

  it("uses Application Support on macOS", () => {
    expect(configDir(base("darwin", {}, "/Users/a"))).toBe("/Users/a/Library/Application Support/wristle");
  });

  it("follows XDG on Linux", () => {
    expect(configDir(base("linux"))).toBe("/home/ayoub/.config/wristle");
    expect(configDir(base("linux", { XDG_CONFIG_HOME: "/xdg" }))).toBe("/xdg/wristle");
  });

  it("lets WRISTLE_BRIDGE_HOME override everything", () => {
    expect(configDir(base("linux", { WRISTLE_BRIDGE_HOME: "/custom" }))).toBe("/custom");
  });
});

describe("ipcPath", () => {
  it("is a named pipe on Windows, unique per config dir", () => {
    const a = ipcPath(base("win32", { APPDATA: "C:\\a" }));
    const b = ipcPath(base("win32", { APPDATA: "C:\\b" }));
    expect(a).toMatch(/^\\\\\.\\pipe\\wristle-bridge-[0-9a-f]{12}$/);
    expect(a).not.toBe(b);
  });

  it("is a socket file in the config dir on Unix", () => {
    expect(ipcPath(base("linux"))).toBe("/home/ayoub/.config/wristle/bridge.sock");
  });

  it("moves to the temp dir when the socket path would be too long", () => {
    const long = "/" + "x".repeat(120);
    expect(ipcPath(base("darwin", { WRISTLE_BRIDGE_HOME: long }))).toMatch(/^\/tmp\/wristle-bridge-[0-9a-f]{12}\.sock$/);
  });
});
