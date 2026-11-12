/**
 * Pairing with the watch and the encrypted mailbox on top of the relay.
 *
 * Firestore layout (see relay/firestore.rules):
 *   pairingCodes/{code}               written by the watch: which pairing a typed code points to
 *   pairings/{pairingId}              watch's public key and FCM token, the bridge's public key, confirmations
 *                                     (watchConfirmed, then bridgeConfirmed: only then is the pairing in use)
 *   pairings/{pairingId}/b2w/{seq}    sealed messages from the bridge to the watch
 *   pairings/{pairingId}/w2b/{seq}    sealed messages from the watch to the bridge
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { CryptoError, deriveSessionKeys, generateKeyPair, open, seal, type Envelope, type Message } from "./crypto.js";
import { configDir } from "./paths.js";
import { connect, RelayError } from "./relay.js";

export interface Pairing {
  pairingId: string;
  /** base64 AES keys per direction */
  w2bKey: string;
  b2wKey: string;
  /** Next sequence number to send, and the highest one accepted from the watch. */
  nextSeq: number;
  lastSeqIn: number;
  pairedAt: string;
}

export const pairingFile = (): string => path.join(configDir(), "pairing.json");

// No 0/O, 1/I/L: the code is read off a small screen and typed.
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 8;

/** Accepts "abcd-efgh", "ABCD EFGH" and so on; returns the stored form or null. */
export function normalizeCode(input: string): string | null {
  const code = input.toUpperCase().replace(/[\s-]/g, "");
  if (code.length !== CODE_LENGTH || [...code].some((c) => !CODE_ALPHABET.includes(c))) return null;
  return code;
}

export function loadPairing(): Pairing | null {
  if (!fs.existsSync(pairingFile())) return null;
  return JSON.parse(fs.readFileSync(pairingFile(), "utf8")) as Pairing;
}

export function savePairing(pairing: Pairing): void {
  fs.mkdirSync(configDir(), { recursive: true });
  const tmp = `${pairingFile()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(pairing, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, pairingFile());
}

function requirePairing(): Pairing {
  const pairing = loadPairing();
  if (!pairing) throw new RelayError("not paired with a watch yet: open Code on the watch and run `wristle pair <code>`");
  return pairing;
}

const seqId = (seq: number) => String(seq).padStart(12, "0");

export interface PairingCallbacks {
  /** Show the security code and resolve with whether the user says it matches the watch. */
  confirm(sas: string): Promise<boolean>;
}

/**
 * Pairs with the watch that shows [rawCode]. Both the user on the computer and the watch must
 * confirm the same security code; only then is the pairing stored and greeted.
 */
export async function pair(rawCode: string, ui: PairingCallbacks, timeoutMs = 120_000): Promise<Pairing> {
  const code = normalizeCode(rawCode);
  if (!code) throw new RelayError(`"${rawCode}" is not a pairing code (8 letters and digits, like ABCD-EFGH)`);
  const { db } = await connect();
  const codeDoc = await db.collection(CODES).doc(code).get();
  if (!codeDoc.exists || Number(codeDoc.get("expiresAt")) < Date.now()) {
    throw new RelayError("unknown or expired code: open Code on the watch for a fresh one");
  }
  const pairingId = String(codeDoc.get("pairingId"));
  const ref = db.collection(PAIRINGS).doc(pairingId);
  const doc = await ref.get();
  const watchPublic = Buffer.from(String(doc.get("watchPublic") ?? ""), "base64");
  if (!doc.exists || doc.get("bridgePublic")) throw new RelayError("this code was already used: open Code on the watch for a fresh one");

  const own = generateKeyPair();
  const keys = deriveSessionKeys(own.privateKey, watchPublic, watchPublic, own.publicKey);
  await ref.update({ bridgePublic: own.publicKey.toString("base64") });
  await codeDoc.ref.delete();

  const watchAnswer = new Promise<boolean>((resolve, reject) => {
    const timer = setTimeout(() => {
      unsubscribe();
      reject(new RelayError(`the watch did not confirm within ${timeoutMs / 1000}s`));
    }, timeoutMs);
    const unsubscribe = ref.onSnapshot(
      (snapshot) => {
        const answer = snapshot.exists ? snapshot.get("watchConfirmed") : false;
        if (typeof answer !== "boolean") return;
        clearTimeout(timer);
        unsubscribe();
        resolve(answer);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
  // Don't leave an unhandled rejection behind if the user answers "no" first.
  watchAnswer.catch(() => undefined);

  const userAgrees = await ui.confirm(keys.sas);
  if (!userAgrees) {
    await db.recursiveDelete(ref);
    throw new RelayError("pairing cancelled: the codes did not match");
  }
  if (!(await watchAnswer)) {
    await db.recursiveDelete(ref);
    throw new RelayError("pairing cancelled on the watch");
  }

  await ref.update({ bridgeConfirmed: true });
  const pairing: Pairing = {
    pairingId,
    w2bKey: keys.w2b.toString("base64"),
    b2wKey: keys.b2w.toString("base64"),
    nextSeq: 1,
    lastSeqIn: 0,
    pairedAt: new Date().toISOString(),
  };
  savePairing(pairing);
  await send(pairing, { type: "hello", ts: Date.now(), name: os.hostname() });
  return pairing;
}

/** Removes the pairing here and in Firestore (including undelivered messages). */
export async function unpair(): Promise<boolean> {
  const pairing = loadPairing();
  if (!pairing) return false;
  const { db } = await connect();
  await db.recursiveDelete(db.collection(PAIRINGS).doc(pairing.pairingId));
  fs.rmSync(pairingFile(), { force: true });
  return true;
}

/** Seals [message], drops it in the watch's mailbox and wakes the watch. */
export async function send(pairing: Pairing, message: Message): Promise<void> {
  const { db, messaging } = await connect();
  const ref = db.collection(PAIRINGS).doc(pairing.pairingId);
  const doc = await ref.get();
  const token = doc.get("fcmToken") as string | undefined;
  if (!doc.exists) throw new RelayError("the watch removed this pairing: run `wristle unpair` and pair again");
  const seq = pairing.nextSeq;
  const envelope = seal(Buffer.from(pairing.b2wKey, "base64"), pairing.pairingId, "b2w", seq, message);
  pairing.nextSeq = seq + 1;
  savePairing(pairing);
  await ref.collection("b2w").doc(seqId(seq)).set(envelope);
  // Only routing data in the push; the watch fetches and opens the envelope itself.
  if (token) {
    await messaging.send({ token, data: { type: "msg", pairingId: pairing.pairingId }, android: { priority: "high", ttl: 60_000 } });
  }
}

/**
 * The bridge's end of the encrypted mailbox: one listener on the watch's outbox (w2b) that opens each
 * envelope, drops it from Firestore and hands the message to every subscriber. The daemon keeps one open.
 */
export class Mailbox {
  private readonly handlers = new Set<(message: Message) => void>();

  private constructor(
    readonly pairing: Pairing,
    private readonly unsubscribe: () => void,
  ) {}

  static async open(onError: (error: Error) => void = () => undefined): Promise<Mailbox> {
    const pairing = requirePairing();
    const { db } = await connect();
    const inbox = db.collection(PAIRINGS).doc(pairing.pairingId).collection("w2b");
    let mailbox: Mailbox | undefined;
    const unsubscribe = inbox.orderBy("seq").onSnapshot((snapshot) => {
      for (const change of snapshot.docChanges()) {
        if (change.type !== "added") continue;
        const envelope = change.doc.data() as Envelope;
        void change.doc.ref.delete();
        let message: Message;
        try {
          message = open(Buffer.from(pairing.w2bKey, "base64"), pairing.pairingId, "w2b", envelope, pairing.lastSeqIn);
        } catch (e) {
          if (e instanceof CryptoError) continue;
          throw e;
        }
        pairing.lastSeqIn = envelope.seq;
        savePairing(pairing);
        for (const handler of mailbox?.handlers ?? []) handler(message);
      }
    }, onError);
    mailbox = new Mailbox(pairing, unsubscribe);
    return mailbox;
  }

  /** Subscribes to messages from the watch; returns the unsubscribe function. */
  onMessage(handler: (message: Message) => void): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  send(message: Omit<Message, "ts">): Promise<void> {
    return send(this.pairing, { ...message, ts: Date.now() } as Message);
  }

  /** An encrypted ping that the watch must open and answer with an encrypted pong; resolves with the round trip in ms. */
  async ping(timeoutMs = 60_000): Promise<number> {
    const id = Math.random().toString(36).slice(2, 10);
    const sentAt = Date.now();
    const pong = new Promise<number>((resolve, reject) => {
      const timer = setTimeout(() => {
        off();
        reject(new RelayError(`no answer from the watch within ${timeoutMs / 1000}s`));
      }, timeoutMs);
      const off = this.onMessage((message) => {
        if (message.type !== "pong" || message.id !== id) return;
        clearTimeout(timer);
        off();
        resolve(Date.now() - sentAt);
      });
    });
    await this.send({ type: "ping", id });
    return pong;
  }

  close(): void {
    this.unsubscribe();
    this.handlers.clear();
  }
}

const CODES = "pairingCodes";
const PAIRINGS = "pairings";
