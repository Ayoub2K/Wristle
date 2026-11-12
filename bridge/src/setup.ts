/**
 * `wristle setup`: one guided run through everything the bridge needs (relay key, Claude API key,
 * allowed projects, pairing with the watch) and the API key's trip to the watch, so nothing has to be typed there.
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import * as p from "@clack/prompts";
import { connect, type Device, discoverWireless, findAdb, installApk, isInstalled, isSafeKey, listDevices, pairDevice, sendKey, WATCH_PACKAGE } from "./adb.js";
import { addProject, loadConfig, maskApiKey, resolveApiKey, saveConfig } from "./config.js";
import { isListening, request } from "./ipc.js";
import { loadPairing, Mailbox, normalizeCode, pair, send, unpair } from "./pairing.js";
import { consoleUrl, enableAnonymousAuth, fetchWatchConfig, parseGoogleServices, repoRoot, syncRules, type WatchFirebaseConfig, writeLocalProperties } from "./firebase.js";
import { checkRelay, connect as connectRelay, credentialsFile, installServiceAccount, loadServiceAccount, RelayError, type ServiceAccount } from "./relay.js";

/** The user pressed Ctrl+C or Esc in a prompt. */
export class SetupCancelled extends Error {}

const useColor = process.stdout.isTTY && !process.env.NO_COLOR;
const paint = (open: string, close: string) => (text: string) => (useColor ? `\x1b[${open}m${text}\x1b[${close}m` : text);
// The watch app's terracotta accent.
const accent = paint("38;2;217;119;87", "39");
const bold = paint("1", "22");
const dim = paint("2", "22");

// "wristle" in two rows of block characters, one entry per letter so the rows always line up.
const LOGO = [
  ["█ █ █", "█▀█", "█", "█▀", "▀█▀", "█  ", "█▀▀"],
  ["▀▄▀▄▀", "█▀▄", "█", "▄█", " █ ", "█▄▄", "██▄"],
];

function banner(): void {
  console.log();
  for (const row of LOGO) console.log("  " + accent(row.join(" ")));
  console.log(dim("  your watch ↔ Claude Code on this computer"));
  console.log();
}

function answer<T>(value: T): Exclude<T, symbol> {
  if (p.isCancel(value)) {
    p.cancel("Setup cancelled. Run `wristle setup` again any time.");
    throw new SetupCancelled();
  }
  return value as Exclude<T, symbol>;
}

const unquote = (text: string) => text.trim().replace(/^["']|["']$/g, "");
const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export type KeyCheck = "ok" | "rejected" | "unreachable";

/** Asks the Claude API whether it accepts [key], with a request that costs nothing. */
export async function checkApiKey(key: string): Promise<KeyCheck> {
  try {
    const response = await fetch("https://api.anthropic.com/v1/models?limit=1", {
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01" },
      signal: AbortSignal.timeout(15_000),
    });
    if (response.ok) return "ok";
    return response.status === 401 || response.status === 403 ? "rejected" : "unreachable";
  } catch {
    return "unreachable";
  }
}

/** Sends the computer's API key to the paired watch, through the daemon when it runs (it owns the mailbox). */
export async function sendApiKeyToWatch(): Promise<void> {
  if (await isListening()) {
    const response = await request({ cmd: "sendApiKey" }, undefined, 30_000);
    if (!response?.ok) throw new RelayError(String(response?.error ?? "the daemon did not answer"));
    return;
  }
  const pairing = loadPairing();
  if (!pairing) throw new RelayError("not paired with a watch yet: run `wristle setup`");
  const apiKey = resolveApiKey(loadConfig());
  if (!apiKey) throw new RelayError("no API key on this computer: run `wristle setup`");
  await send(pairing, { type: "apiKey", ts: Date.now(), key: apiKey.key });
}

/** Asks for a key until the API accepts it (or the user keeps one it couldn't check); stores it in the config. */
export async function enterApiKey(): Promise<void> {
  p.log.info(`Create a key in the Claude Console: ${accent("https://platform.claude.com/settings/keys")}`);
  for (;;) {
    const key = answer(
      await p.password({
        message: "Paste your Claude API key",
        validate: (value) => (value?.trim() ? undefined : "Paste a key, or press Esc to stop"),
      }),
    ).trim();
    const spin = p.spinner();
    spin.start("Checking the key with the Claude API");
    const check = await checkApiKey(key);
    if (check === "rejected") {
      spin.error("The API rejected this key");
      continue;
    }
    if (check === "unreachable") {
      spin.error("Could not reach the API to check the key");
      if (!answer(await p.confirm({ message: "Save it anyway?", initialValue: false }))) continue;
    } else {
      spin.stop(`Key accepted ${dim(maskApiKey(key))}`);
    }
    const config = loadConfig();
    config.apiKey = key;
    saveConfig(config);
    return;
  }
}

/** Pairs with the watch that shows a code, with both screens confirming the same security code. */
export async function pairWithWatch(givenCode?: string): Promise<void> {
  p.note(`Open ${bold("Wristle")} on the watch and tap ${bold("Code")}.\nIt shows a code like ${accent("ABCD-EFGH")}.`, "Pair your watch");
  let code = givenCode;
  for (;;) {
    const current =
      code ??
      answer(
        await p.text({
        message: "Code on the watch",
        placeholder: "ABCD-EFGH",
          validate: (value) => (normalizeCode(value ?? "") ? undefined : "8 letters and digits, like ABCD-EFGH"),
        }),
      );
    const spin = p.spinner();
    spin.start("Connecting to the watch");
    try {
      await pair(current, {
        async confirm(sas) {
          spin.stop("Connected");
          p.note(`${accent(bold(`  ${sas.slice(0, 3)}  ${sas.slice(3)}  `))}\n\n${dim("The watch shows a security code too.")}`, "Security code");
          const same = answer(await p.confirm({ message: "Same code on the watch? Tap ✓ there too.", initialValue: false }));
          if (same) spin.start("Waiting for the watch to confirm");
          return same;
        },
      });
      spin.stop("Paired with the watch");
      return;
    } catch (e) {
      if (e instanceof SetupCancelled) throw e;
      spin.error(errorText(e));
      code = undefined;
      if (!answer(await p.confirm({ message: "Try again with a new code?", initialValue: true }))) throw new SetupCancelled();
    }
  }
}

const PAIRING_CHECK_MS = 20_000;

/** Whether the watch still has this pairing: it must open an encrypted ping and answer it. */
async function watchAnswers(): Promise<boolean> {
  const pairing = loadPairing();
  if (!pairing) return false;
  const { db } = await connectRelay();
  // Unpairing on the watch deletes the pairing document.
  if (!(await db.collection("pairings").doc(pairing.pairingId).get()).exists) return false;
  try {
    if (await isListening()) {
      const response = await request({ cmd: "ping" }, undefined, PAIRING_CHECK_MS + 5_000);
      return response?.ok === true;
    }
    const mailbox = await Mailbox.open();
    try {
      await mailbox.ping(PAIRING_CHECK_MS);
      return true;
    } finally {
      mailbox.close();
    }
  } catch {
    return false;
  }
}

/** Keeps a pairing the watch still answers; otherwise offers to replace it, since the watch may have forgotten it. */
async function checkExistingPairing(): Promise<boolean> {
  const spin = p.spinner();
  spin.start("Checking that the watch still knows this computer");
  if (await watchAnswers()) {
    spin.stop("Paired, and the watch answers");
    return true;
  }
  spin.error("The watch did not answer");
  const choice = answer(
    await p.select({
      message: "The watch may have forgotten this pairing (it then shows a code under Code).",
      options: [
        { value: "again", label: "Pair again", hint: "recommended" },
        { value: "keep", label: "Keep the pairing", hint: "the watch is just offline" },
      ],
    }),
  );
  if (choice === "keep") return true;
  await unpair();
  return false;
}

/** Builds the release APK in this checkout and installs it on the watch over adb. */
async function buildAndInstallWatchApp(root: string): Promise<void> {
  const spin = p.spinner();
  spin.start("Building the watch app (this takes a few minutes)");
  // A fixed command line: Windows needs a shell to run gradlew.bat.
  const child =
    process.platform === "win32"
      ? spawn("gradlew.bat :app:assembleRelease", { cwd: root, shell: true })
      : spawn("./gradlew", [":app:assembleRelease"], { cwd: root });
  let output = "";
  child.stdout?.on("data", (chunk: Buffer) => (output += chunk.toString()));
  child.stderr?.on("data", (chunk: Buffer) => (output += chunk.toString()));
  const code = await new Promise<number | null>((resolve) => {
    child.on("close", resolve);
    child.on("error", () => resolve(-1));
  });
  if (code !== 0) {
    spin.error("The build failed");
    p.log.message(dim(output.trim().split(/\r?\n/).slice(-12).join("\n")));
    p.log.info("Fix the build (see the README), then run setup again.");
    return;
  }
  spin.stop("Built");
  const watch = await connectWatchOverAdb("install the app later yourself");
  if (!watch) return;
  const install = p.spinner();
  install.start(`Installing on ${watch.device.model}`);
  const result = installApk(watch.adb, watch.device.serial, path.join(root, "app", "build", "outputs", "apk", "release", "app-release.apk"));
  if (result.ok) install.stop(`Installed on ${watch.device.model}`);
  else install.error(`Install failed: ${result.message}`);
}

async function waitForManualStep(): Promise<void> {
  answer(await p.select({ message: "Done in the browser?", options: [{ value: true, label: "Done, check again" }] }));
}

/**
 * Your own Firebase project is the relay. Only what needs a browser is manual (project, key, database,
 * starting Authentication); everything else is done with the key: rules, anonymous sign-in, the watch config.
 */
async function firebaseStep(): Promise<void> {
  let account: ServiceAccount;
  if (fs.existsSync(credentialsFile())) {
    account = loadServiceAccount();
    p.log.success(`Firebase project ${account.project_id}`);
  } else {
    p.note(
      [
        "The watch and this computer talk through your own Firebase project (free).",
        "Two steps in the browser; setup does the rest:",
        "",
        `${accent("1")}  ${accent("https://console.firebase.google.com")} → ${bold("Create a project")}.`,
        "   Any name, Google Analytics off, stay on the free Spark plan.",
        `${accent("2")}  In the project: ${bold("⚙ Project settings → Service accounts")}`,
        `   → ${bold("Generate new private key")}. A .json file downloads.`,
        "",
        dim("That file gives full access to the project: keep it private, never commit it."),
      ].join("\n"),
      "Firebase · in your browser",
    );
    for (;;) {
      const file = unquote(answer(await p.path({ message: "The downloaded key (.json)", directory: false })));
      try {
        account = installServiceAccount(file);
        p.log.success(`Key stored for project ${account.project_id} ${dim("(you can delete the download)")}`);
        break;
      } catch (e) {
        p.log.error(errorText(e));
      }
    }
  }
  const id = account.project_id;

  // The database can only be created in the console: it needs a location, which is fixed afterwards.
  for (;;) {
    const spin = p.spinner();
    spin.start("Checking the Firestore database");
    try {
      await checkRelay(account);
      spin.stop("Firestore database ready");
      break;
    } catch (e) {
      const missing = /NOT_FOUND|does not exist|has not been used|is disabled/i.test(errorText(e));
      spin.error(missing ? "No Firestore database yet" : errorText(e));
      p.note(
        [
          `Open ${accent(consoleUrl(id, "firestore"))}`,
          `→ ${bold("Create database")} → ${bold("Production mode")} → a location near you.`,
          dim("The location is fixed afterwards. Setup publishes the rules for you."),
        ].join("\n"),
        "Firestore · in your browser",
      );
      await waitForManualStep();
    }
  }

  const rules = p.spinner();
  rules.start("Publishing the Firestore rules");
  try {
    rules.stop((await syncRules(account)) === "published" ? "Firestore rules published" : "Firestore rules up to date");
  } catch (e) {
    rules.error(`Could not publish the rules: ${errorText(e)}`);
    p.note(`Open ${accent(consoleUrl(id, "firestore/rules"))}, paste relay/firestore.rules and click ${bold("Publish")}.`, "Rules · by hand");
  }

  for (;;) {
    const spin = p.spinner();
    spin.start("Turning on anonymous sign-in for the watch");
    try {
      const result = await enableAnonymousAuth(account);
      if (result !== "not-started") {
        spin.stop(result === "enabled" ? "Anonymous sign-in turned on" : "Anonymous sign-in is on");
        break;
      }
      spin.error("Authentication is not set up in this project yet");
      p.note(`Open ${accent(consoleUrl(id, "authentication"))} and click ${bold("Get started")}. Nothing else.`, "Authentication · in your browser");
      await waitForManualStep();
    } catch (e) {
      spin.error(errorText(e));
      p.note(
        `Open ${accent(consoleUrl(id, "authentication/providers"))} → ${bold("Anonymous")} → ${bold("Enable")} → ${bold("Save")}.`,
        "Anonymous sign-in · by hand",
      );
      await waitForManualStep();
      break;
    }
  }

  const watchConfig = p.spinner();
  watchConfig.start("Registering the watch app and fetching its Firebase config");
  let config: WatchFirebaseConfig;
  try {
    const fetched = await fetchWatchConfig(account);
    config = fetched.config;
    watchConfig.stop(fetched.created ? "Watch app registered in Firebase" : "Watch app found in Firebase");
  } catch (e) {
    watchConfig.error(errorText(e));
    p.note(
      [
        `Open ${accent(consoleUrl(id, "settings/general"))} → ${bold("Add app")} → Android.`,
        `Package name ${bold(WATCH_PACKAGE)}, then download ${bold("google-services.json")}.`,
      ].join("\n"),
      "Watch app · in your browser",
    );
    for (;;) {
      const file = unquote(answer(await p.path({ message: "The downloaded google-services.json", directory: false })));
      try {
        config = parseGoogleServices(fs.readFileSync(file, "utf8"));
        break;
      } catch (err) {
        p.log.error(errorText(err));
      }
    }
  }

  const root = repoRoot();
  if (!root) {
    p.note(
      Object.entries(config)
        .map(([key, value]) => `firebase.${key}=${value}`)
        .join("\n"),
      "Add to local.properties before building the watch app",
    );
    return;
  }
  const changed = writeLocalProperties(path.join(root, "local.properties"), config);
  if (!changed) {
    p.log.success("Watch app config up to date in local.properties");
    return;
  }
  p.log.success(`Watch app config written to local.properties ${dim("(git-ignored)")}`);
  const build = answer(
    await p.confirm({ message: "The watch app needs a rebuild for this. Build and install it on the watch now?", initialValue: true }),
  );
  if (build) await buildAndInstallWatchApp(root);
  else p.log.info(`Later: ${bold("./gradlew :app:assembleRelease")} and install it (see the README).`);
}

async function apiKeyStep(): Promise<void> {
  const existing = resolveApiKey(loadConfig());
  if (existing) {
    const spin = p.spinner();
    spin.start(`Checking the API key ${maskApiKey(existing.key)} from the ${existing.source}`);
    const check = await checkApiKey(existing.key);
    if (check === "rejected") {
      spin.error(`The API rejects the key ${maskApiKey(existing.key)} (revoked or mistyped). Enter a new one.`);
    } else {
      if (check === "ok") spin.stop(`Key ${maskApiKey(existing.key)} works`);
      else spin.error("Could not reach the API to check the key");
      const keep = answer(await p.confirm({ message: `Keep using ${maskApiKey(existing.key)}?`, initialValue: true }));
      if (keep) return;
    }
  }
  await enterApiKey();
}

async function projectsStep(): Promise<void> {
  const config = loadConfig();
  if (config.projects.length > 0) {
    p.log.success(`Allowed projects\n${config.projects.map((project) => `${accent("●")} ${project.name} ${dim(project.path)}`).join("\n")}`);
  } else {
    p.log.info("The watch can only start sessions in folders you allow.");
    if (answer(await p.confirm({ message: `Allow this folder? ${dim(process.cwd())}`, initialValue: true }))) {
      p.log.success(`Allowed ${addProject(config, process.cwd()).name}`);
      saveConfig(config);
    }
  }
  while (answer(await p.confirm({ message: "Allow another folder?", initialValue: config.projects.length === 0 }))) {
    const dir = unquote(answer(await p.path({ message: "Folder", directory: true })));
    try {
      p.log.success(`Allowed ${addProject(config, dir).name}`);
      saveConfig(config);
    } catch (e) {
      p.log.error(errorText(e));
    }
  }
}

/** Finds the watch over adb (wireless debugging), helping to connect or pair adb first; null when the user skips. */
async function connectWatchOverAdb(skipHint: string): Promise<{ adb: string; device: Device } | null> {
  const adb = findAdb();
  if (!adb) {
    p.log.error("adb not found. Install Android Studio or the Android platform-tools and run setup again.");
    return null;
  }
  let devices = listDevices(adb);
  while (devices.length === 0) {
    const spin = p.spinner();
    spin.start("Looking for the watch on your Wi-Fi");
    for (const address of discoverWireless(adb)) connect(adb, address);
    devices = listDevices(adb);
    if (devices.length > 0) {
      spin.stop("Found the watch");
      break;
    }
    spin.error("No watch found");
    p.note(
      [
        `On the watch: ${bold("Settings → Developer options → Wireless debugging")}.`,
        `Make sure it is on, then tap ${bold("Pair new device")}.`,
        "It shows an IP address with a port, and a 6-digit code.",
        dim("Developer options: Settings → About watch → Software → tap Software version 5×."),
      ].join("\n"),
      "Connect the watch",
    );
    const next = answer(
      await p.select({
        message: "What now?",
        options: [
          { value: "pair", label: "Pair this computer", hint: "with the code on the watch" },
          { value: "retry", label: "Look again", hint: "wireless debugging was just switched on" },
          { value: "skip", label: "Skip", hint: skipHint },
        ],
      }),
    );
    if (next === "skip") return null;
    if (next === "pair") {
      const address = answer(
        await p.text({
          message: "IP address and port under the pairing code",
          placeholder: "192.168.1.20:37099",
          validate: (value) => (/^[\d.]+:\d+$/.test(value?.trim() ?? "") ? undefined : "Like 192.168.1.20:37099"),
        }),
      ).trim();
      const code = answer(
        await p.text({ message: "Pairing code", placeholder: "123456", validate: (value) => (/^\d{6}$/.test(value?.trim() ?? "") ? undefined : "6 digits") }),
      ).trim();
      const result = pairDevice(adb, address, code);
      if (result.ok) p.log.success("Paired with the watch");
      else p.log.error(`Pairing failed: ${result.message || "no answer"}`);
    }
  }

  let device = devices[0] as Device;
  if (devices.length > 1) {
    device = answer(
      await p.select({ message: "Which device is the watch?", options: devices.map((d) => ({ value: d, label: d.model, hint: d.serial })) }),
    );
  }
  return { adb, device };
}

/** Puts the key on the watch through the app's adb-only key activity. */
async function sendKeyOverAdb(key: string): Promise<boolean> {
  if (!isSafeKey(key)) {
    p.log.error("This key has unexpected characters, so it can't go over adb. Type it on the watch instead.");
    return false;
  }
  const watch = await connectWatchOverAdb("type the key on the watch instead");
  if (!watch) return false;
  const { adb, device } = watch;
  if (!isInstalled(adb, device.serial)) {
    p.log.error(`Wristle is not installed on ${device.model}. Install it first (see the README).`);
    return false;
  }
  const spin = p.spinner();
  spin.start(`Sending the key to ${device.model}`);
  if (sendKey(adb, device.serial, key)) {
    spin.stop(`Key sent; the watch shows ${bold("Key saved")}`);
    return true;
  }
  spin.error("The watch did not accept it");
  return false;
}

/** Chat only needs the API key on the watch: over the paired bridge if there is one, otherwise over adb. */
async function chatSetup(): Promise<void> {
  p.log.step(bold("1 · Claude API key"));
  await apiKeyStep();
  const key = resolveApiKey(loadConfig())?.key ?? "";

  p.log.step(bold("2 · Send it to the watch"));
  const paired = loadPairing() !== null && fs.existsSync(credentialsFile());
  const route = paired
    ? answer(
        await p.select({
          message: "How should the key reach the watch?",
          options: [
            { value: "adb", label: "Over adb", hint: "wireless debugging; the watch confirms it" },
            { value: "bridge", label: "Through the paired bridge", hint: "end-to-end encrypted" },
          ],
        }),
      )
    : "adb";
  let sent = false;
  if (route === "bridge") {
    const spin = p.spinner();
    spin.start("Sending the key (end-to-end encrypted)");
    try {
      await sendApiKeyToWatch();
      spin.stop("Key sent; the watch picks it up within a few seconds");
      sent = true;
    } catch (e) {
      spin.error(errorText(e));
    }
  } else {
    sent = await sendKeyOverAdb(key);
  }
  p.outro(sent ? `All set. Open ${bold("Chat")} on the watch and ask away.` : `No key sent. On the watch you can also tap ${bold("Type it here")}.`);
}

export async function setup(): Promise<{ startDaemon: boolean }> {
  banner();
  p.intro(accent(bold(" setup ")));

  const mode = answer(
    await p.select({
      message: "What do you want to set up?",
      options: [
        { value: "chat", label: "Chat", hint: "put your Claude API key on the watch" },
        { value: "code", label: "Code", hint: "pair the watch with this computer for Claude Code" },
      ],
    }),
  );
  if (mode === "chat") {
    await chatSetup();
    return { startDaemon: false };
  }

  p.log.step(bold("1 · Firebase relay"));
  await firebaseStep();
  p.log.step(bold("2 · Claude API key"));
  await apiKeyStep();
  p.log.step(bold("3 · Projects"));
  await projectsStep();
  p.log.step(bold("4 · Watch"));
  let pairedNow = false;
  if (!loadPairing() || !(await checkExistingPairing())) {
    await pairWithWatch();
    pairedNow = true;
  }

  // A daemon started before pairing doesn't have the mailbox open; stop it so it starts fresh below.
  const running = await isListening();
  if (running && pairedNow) {
    await request({ cmd: "stop" });
    for (let i = 0; i < 50 && (await isListening()); i++) await new Promise((resolve) => setTimeout(resolve, 100));
  }
  const daemonUp = running && !pairedNow;

  if (answer(await p.confirm({ message: "Send the API key to the watch, so you don't have to type it there?", initialValue: true }))) {
    const spin = p.spinner();
    spin.start("Sending the key (end-to-end encrypted)");
    try {
      await sendApiKeyToWatch();
      spin.stop("Key sent; the watch picks it up within a few seconds");
    } catch (e) {
      spin.error(`${errorText(e)} (try later with \`wristle key send\`)`);
    }
  }

  if (daemonUp) {
    p.outro(`All set. The bridge is already running; open ${bold("Code")} on the watch.`);
    return { startDaemon: false };
  }
  const start = answer(
    await p.confirm({ message: "Start the bridge now? It keeps running in this window (Ctrl+C stops it).", initialValue: true }),
  );
  p.outro(start ? `All set. Open ${bold("Code")} on the watch.` : "All set. Start the bridge later with `wristle serve`.");
  return { startDaemon: start };
}
