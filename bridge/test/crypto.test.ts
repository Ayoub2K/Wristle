import fs from "node:fs";
import { describe, expect, it } from "vitest";
import { CryptoError, deriveSessionKeys, generateKeyPair, MAX_CLOCK_SKEW_MS, open, publicKeyOf, seal, type Envelope } from "../src/crypto.js";

const vectors = JSON.parse(fs.readFileSync(new URL("../../relay/test-vectors.json", import.meta.url), "utf8"));
const b = (base64: string) => Buffer.from(base64, "base64");
const hex = (text: string) => Buffer.from(text, "hex");

describe("key exchange", () => {
  it("matches RFC 7748 for public keys", () => {
    expect(publicKeyOf(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")).toString("hex")).toBe(
      "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
    );
  });

  it("gives both sides the same keys and security code", () => {
    const watch = generateKeyPair();
    const bridge = generateKeyPair();
    const onWatch = deriveSessionKeys(watch.privateKey, bridge.publicKey, watch.publicKey, bridge.publicKey);
    const onBridge = deriveSessionKeys(bridge.privateKey, watch.publicKey, watch.publicKey, bridge.publicKey);
    expect(onWatch).toEqual(onBridge);
    expect(onWatch.sas).toMatch(/^\d{6}$/);
    expect(onWatch.w2b.equals(onWatch.b2w)).toBe(false);
  });

  it("matches the shared test vectors", () => {
    expect(publicKeyOf(b(vectors.watchPrivate)).toString("base64")).toBe(vectors.watchPublic);
    const keys = deriveSessionKeys(b(vectors.watchPrivate), b(vectors.bridgePublic), b(vectors.watchPublic), b(vectors.bridgePublic));
    expect(keys.w2b.toString("base64")).toBe(vectors.w2bKey);
    expect(keys.b2w.toString("base64")).toBe(vectors.b2wKey);
    expect(keys.sas).toBe(vectors.sas);
  });

  it("rejects an all-zero public key", () => {
    expect(() => deriveSessionKeys(generateKeyPair().privateKey, Buffer.alloc(32), Buffer.alloc(32), Buffer.alloc(32))).toThrow();
  });
});

describe("messages", () => {
  const key = b(vectors.b2wKey);
  const { pairingId, envelope } = vectors.message as { pairingId: string; envelope: Envelope };
  const sentAt = 1793600000000;

  it("opens the shared test vector", () => {
    expect(JSON.stringify(open(key, pairingId, "b2w", envelope, 0, sentAt))).toBe(vectors.message.plaintext);
  });

  it("round-trips", () => {
    const sealed = seal(key, "p", "w2b", 1, { type: "pong", ts: sentAt, id: "x" });
    expect(open(key, "p", "w2b", sealed, 0, sentAt)).toEqual({ type: "pong", ts: sentAt, id: "x" });
  });

  it("rejects replays, other routes and tampering", () => {
    expect(() => open(key, pairingId, "b2w", envelope, 7, sentAt)).toThrow(/replayed/);
    expect(() => open(key, pairingId, "w2b", envelope, 0, sentAt)).toThrow(CryptoError);
    expect(() => open(key, "other", "b2w", envelope, 0, sentAt)).toThrow(CryptoError);
    expect(() => open(key, pairingId, "b2w", { ...envelope, seq: 8 }, 0, sentAt)).toThrow(CryptoError);
    const flipped = b(envelope.c);
    flipped[0] ^= 1;
    expect(() => open(key, pairingId, "b2w", { ...envelope, c: flipped.toString("base64") }, 0, sentAt)).toThrow(CryptoError);
  });

  it("rejects messages outside the time window", () => {
    expect(() => open(key, pairingId, "b2w", envelope, 0, sentAt + MAX_CLOCK_SKEW_MS + 1)).toThrow(/clocks/);
  });
});
