// End-to-end check of the built CLI: serve, status, projects, stop. Runs the same on Windows, macOS and Linux.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const cli = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "dist", "cli.js");
const home = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-smoke-"));
const env = { ...process.env, WRISTLE_BRIDGE_HOME: home };
const run = (...args) => spawnSync(process.execPath, [cli, ...args], { env, encoding: "utf8" });
const fail = (message) => {
  console.error(`smoke test failed: ${message}`);
  process.exit(1);
};

if (run("status").status !== 3) fail("status should report not running before serve");

const daemon = spawn(process.execPath, [cli, "serve"], { env, stdio: "inherit" });
const exited = new Promise((resolve) => daemon.on("exit", (code) => resolve(code)));

let up = false;
for (let i = 0; i < 50 && !up; i++) {
  await new Promise((resolve) => setTimeout(resolve, 200));
  up = run("status").status === 0;
}
if (!up) fail("daemon did not come up");
console.log(run("status").stdout.trim());

const project = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-project-"));
if (run("projects", "add", project, "demo").status !== 0) fail("projects add");
if (!run("projects", "list").stdout.includes("demo")) fail("projects list");
if (!run("status").stdout.includes("1 allowed project")) fail("status should count the project");

const stop = run("stop");
if (stop.status !== 0) fail(`stop: ${stop.stdout}${stop.stderr}`);
const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve("timeout"), 5000))]);
if (code !== 0) fail(`serve exited with ${code}`);
if (run("status").status !== 3) fail("status should report not running after stop");

console.log("smoke test passed");
