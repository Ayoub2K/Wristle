/**
 * The parts of the Firebase setup the bridge can do itself with the service account key: publish the Firestore
 * rules, switch on anonymous sign-in, register the watch app and fetch its config for the build. Creating the
 * project, the key and the database stay manual steps in the Firebase console; `wristle setup` explains them.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WATCH_PACKAGE } from "./adb.js";
import { firebaseApp, loadServiceAccount, RelayError, type ServiceAccount } from "./relay.js";

/** The repository checkout this bridge runs from (bridge/dist or bridge/src → the repo root), if there is one. */
export function repoRoot(): string | null {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
  return fs.existsSync(path.join(root, "settings.gradle.kts")) ? root : null;
}

export const consoleUrl = (projectId: string, page: string): string =>
  `https://console.firebase.google.com/project/${projectId}/${page}`;

const normalize = (text: string) => text.replace(/\r\n/g, "\n").trim();

export type RulesResult = "published" | "unchanged";

/** Publishes relay/firestore.rules unless the project already runs exactly these rules. */
export async function syncRules(account: ServiceAccount = loadServiceAccount()): Promise<RulesResult> {
  const root = repoRoot();
  const file = root && path.join(root, "relay", "firestore.rules");
  if (!file || !fs.existsSync(file)) throw new RelayError("relay/firestore.rules not found next to the bridge");
  const source = fs.readFileSync(file, "utf8");
  const { getSecurityRules } = await import("firebase-admin/security-rules");
  const rules = getSecurityRules(await firebaseApp(account));
  try {
    const current = await rules.getFirestoreRuleset();
    if (current.source.some((f) => normalize(f.content) === normalize(source))) return "unchanged";
  } catch {
    // No rules released yet: publish ours.
  }
  await rules.releaseFirestoreRulesetFromSource(source);
  return "published";
}

export type AnonymousAuthResult = "enabled" | "already" | "not-started";

/** Turns on anonymous sign-in, which the watch uses. "not-started": Authentication was never opened in the console. */
export async function enableAnonymousAuth(account: ServiceAccount = loadServiceAccount()): Promise<AnonymousAuthResult> {
  const app = await firebaseApp(account);
  const { access_token: token } = await (app.options.credential as { getAccessToken(): Promise<{ access_token: string }> }).getAccessToken();
  const url = `https://identitytoolkit.googleapis.com/admin/v2/projects/${account.project_id}/config`;
  const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  const current = await fetch(url, { headers });
  const body = (await current.json()) as { signIn?: { anonymous?: { enabled?: boolean } }; error?: { message?: string } };
  if (!current.ok) {
    if (current.status === 404 || /CONFIGURATION_NOT_FOUND/.test(body.error?.message ?? "")) return "not-started";
    throw new RelayError(`could not read the sign-in settings: ${body.error?.message ?? current.status}`);
  }
  if (body.signIn?.anonymous?.enabled) return "already";
  const update = await fetch(`${url}?updateMask=signIn.anonymous.enabled`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({ signIn: { anonymous: { enabled: true } } }),
  });
  if (!update.ok) {
    const error = (await update.json()) as { error?: { message?: string } };
    throw new RelayError(`could not turn on anonymous sign-in: ${error.error?.message ?? update.status}`);
  }
  return "enabled";
}

export interface WatchFirebaseConfig {
  projectId: string;
  appId: string;
  apiKey: string;
  senderId: string;
}

/** The four values the watch build needs, from a google-services.json. */
export function parseGoogleServices(json: string, packageName = WATCH_PACKAGE): WatchFirebaseConfig {
  const data = JSON.parse(json) as {
    project_info?: { project_id?: string; project_number?: string };
    client?: { client_info?: { mobilesdk_app_id?: string; android_client_info?: { package_name?: string } }; api_key?: { current_key?: string }[] }[];
  };
  const client = data.client?.find((c) => c.client_info?.android_client_info?.package_name === packageName) ?? data.client?.[0];
  const config = {
    projectId: data.project_info?.project_id ?? "",
    appId: client?.client_info?.mobilesdk_app_id ?? "",
    apiKey: client?.api_key?.[0]?.current_key ?? "",
    senderId: data.project_info?.project_number ?? "",
  };
  if (Object.values(config).some((v) => !v)) throw new RelayError("not a google-services.json for the watch app");
  return config;
}

/** Registers the watch app in the project when needed and returns its config. */
export async function fetchWatchConfig(account: ServiceAccount = loadServiceAccount()): Promise<{ config: WatchFirebaseConfig; created: boolean }> {
  const { getProjectManagement } = await import("firebase-admin/project-management");
  const management = getProjectManagement(await firebaseApp(account));
  let created = false;
  let app = null;
  for (const candidate of await management.listAndroidApps()) {
    if ((await candidate.getMetadata()).packageName === WATCH_PACKAGE) app = candidate;
  }
  if (!app) {
    app = await management.createAndroidApp(WATCH_PACKAGE, "Wristle");
    created = true;
  }
  return { config: parseGoogleServices(await app.getConfig()), created };
}

const PROPERTY_KEYS: Record<keyof WatchFirebaseConfig, string> = {
  projectId: "firebase.projectId",
  appId: "firebase.appId",
  apiKey: "firebase.apiKey",
  senderId: "firebase.senderId",
};

/** Writes the config into local.properties (git-ignored); returns whether anything changed. */
export function writeLocalProperties(file: string, config: WatchFirebaseConfig): boolean {
  const original = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
  const eol = original.includes("\r\n") ? "\r\n" : "\n";
  const lines = original === "" ? [] : original.split(/\r?\n/);
  if (lines.at(-1) === "") lines.pop();
  for (const [field, key] of Object.entries(PROPERTY_KEYS) as [keyof WatchFirebaseConfig, string][]) {
    const line = `${key}=${config[field]}`;
    const index = lines.findIndex((l) => l.startsWith(`${key}=`));
    if (index >= 0) lines[index] = line;
    else lines.push(line);
  }
  const updated = lines.join(eol) + eol;
  if (updated === original) return false;
  fs.writeFileSync(file, updated);
  return true;
}
