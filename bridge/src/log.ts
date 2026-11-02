/** Minimal logger: timestamped lines to the bridge log file and to stderr. */
import fs from "node:fs";
import path from "node:path";
import { logFile } from "./paths.js";

export type Logger = (message: string) => void;

export function createLogger(file = logFile(), echo = true): Logger {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  return (message) => {
    const line = `${new Date().toISOString()} ${message}`;
    fs.appendFileSync(file, line + "\n");
    if (echo) console.error(line);
  };
}
