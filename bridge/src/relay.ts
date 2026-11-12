/**
 * Connection to the owner's Firebase project: Firestore as the mailbox, FCM to wake the watch.
 * The bridge authenticates with a service account key stored in its config folder.
 */
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { configDir } from "./paths.js";

export class RelayError extends Error {}

export interface ServiceAccount {
  type: "service_account";
  project_id: string;
  client_email: string;
  private_key: string;
}

export const credentialsFile = (): string => path.join(configDir(), "service-account.json");

export function parseServiceAccount(text: string): ServiceAccount {
  let json: Partial<ServiceAccount>;
  try {
    json = JSON.parse(text) as Partial<ServiceAccount>;
  } catch {
    throw new RelayError("not a JSON file");
  }
  if (json.type !== "service_account" || !json.project_id || !json.client_email || !json.private_key) {
    throw new RelayError("not a Firebase service account key (expected type, project_id, client_email, private_key)");
  }
  return json as ServiceAccount;
}

/** Copies the key into the config folder, readable only by the current user on macOS and Linux. */
export function installServiceAccount(source: string): ServiceAccount {
  const account = parseServiceAccount(fs.readFileSync(source, "utf8"));
  fs.mkdirSync(configDir(), { recursive: true });
  fs.writeFileSync(credentialsFile(), JSON.stringify(account, null, 2), { mode: 0o600 });
  return account;
}

export function loadServiceAccount(): ServiceAccount {
  if (!fs.existsSync(credentialsFile())) {
    throw new RelayError("no Firebase key yet: run `wristle relay setup <service-account.json>` (see docs/firebase-setup.md)");
  }
  return parseServiceAccount(fs.readFileSync(credentialsFile(), "utf8"));
}

// firebase-admin is loaded lazily so commands that don't need the relay stay fast.
export async function firebaseApp(account: ServiceAccount = loadServiceAccount()) {
  const { cert, getApps, initializeApp } = await import("firebase-admin/app");
  const apps = getApps();
  // A newly installed key (setup) must not keep talking to the project of an older one.
  const match = apps.find((app) => app.options.projectId === account.project_id);
  if (match) return match;
  return initializeApp(
    { projectId: account.project_id, credential: cert({ projectId: account.project_id, clientEmail: account.client_email, privateKey: account.private_key }) },
    apps.length > 0 ? `wristle-${account.project_id}` : undefined,
  );
}

export async function connect(account: ServiceAccount = loadServiceAccount()) {
  const { getFirestore } = await import("firebase-admin/firestore");
  const { getMessaging } = await import("firebase-admin/messaging");
  const app = await firebaseApp(account);
  return { db: getFirestore(app), messaging: getMessaging(app) };
}

/** Proves the key works by reading from Firestore. */
export async function checkRelay(account = loadServiceAccount()): Promise<void> {
  const { db } = await connect(account);
  await db.collection(TESTS).limit(1).get();
}

export interface PingResult {
  watchId: string;
  roundTripMs: number;
}

/**
 * P2-M2a relay test: push a ping to the most recently registered watch and wait until it
 * writes the matching pong back to Firestore.
 */
export async function pingWatch(timeoutMs = 60_000, account = loadServiceAccount()): Promise<PingResult> {
  const { db, messaging } = await connect(account);
  const latest = await db.collection(TESTS).orderBy("updatedAt", "desc").limit(1).get();
  const doc = latest.docs[0];
  const token = doc?.get("fcmToken") as string | undefined;
  if (!doc || !token) throw new RelayError("no watch registered yet: open Settings → Relay test on the watch first");

  const id = randomUUID();
  const sentAt = Date.now();
  const pong = new Promise<number>((resolve, reject) => {
    const timer = setTimeout(() => {
      unsubscribe();
      reject(new RelayError(`no answer from the watch within ${timeoutMs / 1000}s`));
    }, timeoutMs);
    const unsubscribe = doc.ref.onSnapshot(
      (snapshot) => {
        if (snapshot.get("pongId") === id) {
          clearTimeout(timer);
          unsubscribe();
          resolve(Date.now());
        }
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
  // High priority so the watch wakes up for it; data-only, so the app handles it rather than the system tray.
  await messaging.send({ token, data: { type: "ping", id, sentAt: String(sentAt) }, android: { priority: "high", ttl: timeoutMs } });
  const receivedAt = await pong;
  return { watchId: doc.id, roundTripMs: receivedAt - sentAt };
}

const TESTS = "tests";
