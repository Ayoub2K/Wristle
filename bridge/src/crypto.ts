/**
 * End-to-end encryption between the bridge and the watch. The relay (Firestore and FCM) only ever sees
 * public keys, sealed envelopes and routing ids.
 *
 * Pairing: X25519 key exchange, then HKDF-SHA256 over the shared secret into one AES-256-GCM key per
 * direction and a 6-digit security code (SAS) that both screens show. Messages carry a sequence number
 * (bound into the AAD) and a timestamp, so replays and stale messages are rejected.
 *
 * The watch implements the same protocol; relay/test-vectors.json keeps both sides in step.
 */
import { createCipheriv, createDecipheriv, createPrivateKey, createPublicKey, diffieHellman, generateKeyPairSync, hkdfSync, randomBytes } from "node:crypto";

export class CryptoError extends Error {}

export type Direction = "w2b" | "b2w";

export interface KeyPair {
  privateKey: Buffer;
  publicKey: Buffer;
}

export interface SessionKeys {
  w2b: Buffer;
  b2w: Buffer;
  sas: string;
}

export interface Envelope {
  seq: number;
  /** base64 nonce */
  n: string;
  /** base64 ciphertext with the GCM tag appended */
  c: string;
}

export interface Message {
  type: string;
  /** Sender's clock in milliseconds, checked against the receiver's within [MAX_CLOCK_SKEW_MS]. */
  ts: number;
  [field: string]: unknown;
}

export const MAX_CLOCK_SKEW_MS = 5 * 60_000;
const KEY_BYTES = 32;
const NONCE_BYTES = 12;
const TAG_BYTES = 16;

// DER headers for raw X25519 keys (RFC 8410), so Node can import the 32-byte form both sides exchange.
const PKCS8_PREFIX = Buffer.from("302e020100300506032b656e04220420", "hex");
const SPKI_PREFIX = Buffer.from("302a300506032b656e032100", "hex");

export function generateKeyPair(): KeyPair {
  const { privateKey, publicKey } = generateKeyPairSync("x25519");
  return {
    privateKey: privateKey.export({ format: "der", type: "pkcs8" }).subarray(PKCS8_PREFIX.length),
    publicKey: publicKey.export({ format: "der", type: "spki" }).subarray(SPKI_PREFIX.length),
  };
}

export function publicKeyOf(privateKey: Buffer): Buffer {
  return createPublicKey(importPrivate(privateKey)).export({ format: "der", type: "spki" }).subarray(SPKI_PREFIX.length);
}

/** Both sides derive the same keys and code; [watchPublic] and [bridgePublic] fix the order. */
export function deriveSessionKeys(ownPrivate: Buffer, peerPublic: Buffer, watchPublic: Buffer, bridgePublic: Buffer): SessionKeys {
  requireLength(peerPublic, KEY_BYTES, "public key");
  const shared = diffieHellman({
    privateKey: importPrivate(ownPrivate),
    publicKey: createPublicKey({ key: Buffer.concat([SPKI_PREFIX, peerPublic]), format: "der", type: "spki" }),
  });
  if (shared.every((b) => b === 0)) throw new CryptoError("invalid public key");
  const salt = Buffer.concat([watchPublic, bridgePublic]);
  const keys = Buffer.from(hkdfSync("sha256", shared, salt, "wristle/v1/keys", 2 * KEY_BYTES));
  const sasBytes = Buffer.from(hkdfSync("sha256", shared, salt, "wristle/v1/sas", 4));
  return {
    w2b: keys.subarray(0, KEY_BYTES),
    b2w: keys.subarray(KEY_BYTES),
    sas: String(sasBytes.readUInt32BE(0) % 1_000_000).padStart(6, "0"),
  };
}

/** Routing data the envelope is bound to: moving it to another pairing, direction or position fails. */
export function aad(pairingId: string, direction: Direction, seq: number): Buffer {
  return Buffer.from(`wristle/v1|${pairingId}|${direction}|${seq}`, "utf8");
}

export function seal(key: Buffer, pairingId: string, direction: Direction, seq: number, message: Message, nonce: Buffer = randomBytes(NONCE_BYTES)): Envelope {
  requireLength(nonce, NONCE_BYTES, "nonce");
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(aad(pairingId, direction, seq));
  const sealed = Buffer.concat([cipher.update(JSON.stringify(message), "utf8"), cipher.final(), cipher.getAuthTag()]);
  return { seq, n: nonce.toString("base64"), c: sealed.toString("base64") };
}

/**
 * Opens an envelope. [lastSeq] is the highest sequence number already accepted in this direction;
 * anything at or below it is a replay.
 */
export function open(key: Buffer, pairingId: string, direction: Direction, envelope: Envelope, lastSeq: number, now = Date.now()): Message {
  if (!Number.isSafeInteger(envelope.seq) || envelope.seq <= lastSeq) throw new CryptoError(`replayed or out-of-order message (seq ${envelope.seq})`);
  const nonce = Buffer.from(envelope.n, "base64");
  const sealed = Buffer.from(envelope.c, "base64");
  requireLength(nonce, NONCE_BYTES, "nonce");
  if (sealed.length < TAG_BYTES) throw new CryptoError("message too short");
  let plain: string;
  try {
    const decipher = createDecipheriv("aes-256-gcm", key, nonce);
    decipher.setAAD(aad(pairingId, direction, envelope.seq));
    decipher.setAuthTag(sealed.subarray(sealed.length - TAG_BYTES));
    plain = Buffer.concat([decipher.update(sealed.subarray(0, sealed.length - TAG_BYTES)), decipher.final()]).toString("utf8");
  } catch {
    throw new CryptoError("message failed authentication");
  }
  const message = JSON.parse(plain) as Message;
  if (typeof message.type !== "string" || typeof message.ts !== "number") throw new CryptoError("malformed message");
  if (Math.abs(now - message.ts) > MAX_CLOCK_SKEW_MS) throw new CryptoError("message is too old or from the future; check both clocks");
  return message;
}

function importPrivate(raw: Buffer) {
  requireLength(raw, KEY_BYTES, "private key");
  return createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, raw]), format: "der", type: "pkcs8" });
}

function requireLength(bytes: Buffer, length: number, what: string) {
  if (bytes.length !== length) throw new CryptoError(`${what} must be ${length} bytes, got ${bytes.length}`);
}
