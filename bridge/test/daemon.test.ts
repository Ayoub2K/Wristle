import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { startDaemon } from "../src/daemon.js";
import { DaemonAlreadyRunningError, isListening, request } from "../src/ipc.js";
import { pidFile } from "../src/paths.js";

const quiet = () => {};

beforeEach(() => {
  process.env.WRISTLE_BRIDGE_HOME = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-daemon-"));
});

afterEach(() => {
  delete process.env.WRISTLE_BRIDGE_HOME;
});

describe("daemon over IPC", () => {
  it("answers status, refuses a second daemon, and stops on request", async () => {
    const daemon = await startDaemon(quiet, { relay: false });
    try {
      expect(fs.readFileSync(pidFile(), "utf8")).toBe(String(process.pid));

      expect(await request({ cmd: "status" })).toMatchObject({ ok: true, pid: process.pid, projects: 0 });
      await expect(startDaemon(quiet, { relay: false })).rejects.toBeInstanceOf(DaemonAlreadyRunningError);
      expect(await request({ cmd: "nope" })).toMatchObject({ ok: false });
      // Without a watch there is nobody to send the API key to.
      expect(await request({ cmd: "sendApiKey" })).toMatchObject({ ok: false, error: expect.stringMatching(/not connected/) });

      expect(await request({ cmd: "stop" })).toMatchObject({ ok: true });
      await daemon.stopped;
      expect(await isListening()).toBe(false);
      expect(fs.existsSync(pidFile())).toBe(false);
    } finally {
      await daemon.stop();
    }
  });

  it("reports no daemon when nothing is listening", async () => {
    expect(await request({ cmd: "status" }, undefined, 500)).toBeNull();
  });
});
