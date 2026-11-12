import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { findAdb, isSafeKey, parseMdns } from "../src/adb.js";

describe("adb helpers", () => {
  it("reads wireless watches from `adb mdns services`", () => {
    const output = [
      "List of discovered mdns services",
      "adb-WATCH0001-AbCdEf\t_adb-tls-connect._tcp\t192.168.1.20:36461",
      "adb-WATCH0001-AbCdEf\t_adb-tls-pairing._tcp\t192.168.1.20:41235",
    ].join("\r\n");
    expect(parseMdns(output)).toEqual(["192.168.1.20:36461"]);
    expect(parseMdns("List of discovered mdns services\n")).toEqual([]);
  });

  it("only lets key characters through to the remote shell", () => {
    expect(isSafeKey("test-key-abcdEFGH_1234567890")).toBe(true);
    expect(isSafeKey("test-key'; rm -rf /")).toBe(false);
    expect(isSafeKey("short")).toBe(false);
  });

  it("finds adb in the Android SDK", () => {
    const sdk = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-sdk-"));
    const adb = path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
    fs.mkdirSync(path.dirname(adb));
    fs.writeFileSync(adb, "");
    expect(findAdb({ ANDROID_HOME: sdk })).toBe(adb);
  });
});
