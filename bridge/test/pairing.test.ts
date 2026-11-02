import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadPairing, normalizeCode, pairingFile, savePairing } from "../src/pairing.js";

beforeEach(() => {
  process.env.WRISTLE_BRIDGE_HOME = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-pairing-"));
});

afterEach(() => {
  delete process.env.WRISTLE_BRIDGE_HOME;
});

describe("pairing code", () => {
  it("accepts the code however it is typed", () => {
    expect(normalizeCode("abcd-efgh")).toBe("ABCDEFGH");
    expect(normalizeCode(" ABCD EFGH ")).toBe("ABCDEFGH");
  });

  it("rejects wrong lengths and confusable characters", () => {
    expect(normalizeCode("ABCD-EFG")).toBeNull();
    expect(normalizeCode("ABCD-EFG0")).toBeNull();
    expect(normalizeCode("ABCD-EFGI")).toBeNull();
  });
});

describe("stored pairing", () => {
  it("is saved privately and loaded back", () => {
    expect(loadPairing()).toBeNull();
    const pairing = { pairingId: "p", w2bKey: "a", b2wKey: "b", nextSeq: 1, lastSeqIn: 0, pairedAt: "now" };
    savePairing(pairing);
    expect(loadPairing()).toEqual(pairing);
    if (process.platform !== "win32") expect(fs.statSync(pairingFile()).mode & 0o777).toBe(0o600);
  });
});
