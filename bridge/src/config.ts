/** Bridge config: the project allowlist, model and API key, stored as JSON with atomic writes (owner-only on macOS and Linux). */
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { configFile } from "./paths.js";

export interface Project {
  id: string;
  name: string;
  path: string;
}

export interface BridgeConfig {
  version: 1;
  projects: Project[];
  /** Model for watch-started sessions; the Agent SDK's default when unset. */
  model?: string;
  /** Claude API key for watch-started sessions (`wristle setup`); wins over ANTHROPIC_API_KEY in the environment. */
  apiKey?: string;
}

export class ConfigError extends Error {}

const empty = (): BridgeConfig => ({ version: 1, projects: [] });

export function loadConfig(file = configFile()): BridgeConfig {
  if (!fs.existsSync(file)) return empty();
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8")) as Partial<BridgeConfig>;
    return {
      version: 1,
      projects: Array.isArray(parsed.projects) ? parsed.projects : [],
      ...(typeof parsed.model === "string" && parsed.model ? { model: parsed.model } : {}),
      ...(typeof parsed.apiKey === "string" && parsed.apiKey ? { apiKey: parsed.apiKey } : {}),
    };
  } catch (e) {
    throw new ConfigError(`config file is not valid JSON: ${file} (${(e as Error).message})`);
  }
}

export function saveConfig(config: BridgeConfig, file = configFile()): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
  fs.renameSync(tmp, file);
}

export type ApiKeySource = "environment" | "config";

/**
 * The key sessions use and where it comes from, or null when there is none. The saved key wins: it is the one
 * `wristle setup` checked, while an old ANTHROPIC_API_KEY can linger in the environment after a key is revoked.
 */
export function resolveApiKey(config: BridgeConfig, env: NodeJS.ProcessEnv = process.env): { key: string; source: ApiKeySource } | null {
  if (config.apiKey) return { key: config.apiKey, source: "config" };
  if (env.ANTHROPIC_API_KEY) return { key: env.ANTHROPIC_API_KEY, source: "environment" };
  return null;
}

/** Only the end of a key, for showing which one is in use. */
export function maskApiKey(key: string): string {
  return `…${key.slice(-4)}`;
}

/** Compares paths the way the OS does: case-insensitive on Windows and macOS. */
function samePath(a: string, b: string): boolean {
  const norm = (p: string) => (process.platform === "linux" ? path.resolve(p) : path.resolve(p).toLowerCase());
  return norm(a) === norm(b);
}

export function addProject(config: BridgeConfig, dir: string, name?: string): Project {
  const resolved = path.resolve(dir);
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
    throw new ConfigError(`not a directory: ${resolved}`);
  }
  if (config.projects.some((p) => samePath(p.path, resolved))) {
    throw new ConfigError(`already allowed: ${resolved}`);
  }
  const project: Project = {
    id: createHash("sha256").update(resolved).digest("hex").slice(0, 8),
    name: name || path.basename(resolved),
    path: resolved,
  };
  config.projects.push(project);
  return project;
}

/** Removes by id, name or path; returns the removed project. */
export function removeProject(config: BridgeConfig, key: string): Project {
  const index = config.projects.findIndex((p) => p.id === key || p.name === key || samePath(p.path, key));
  if (index < 0) throw new ConfigError(`no allowed project matches: ${key}`);
  const [removed] = config.projects.splice(index, 1);
  return removed as Project;
}

/** True when [dir] is an allowed project or inside one. Sessions may only start there. */
export function isAllowed(config: BridgeConfig, dir: string): boolean {
  const target = path.resolve(dir);
  return config.projects.some((p) => {
    const relative = path.relative(p.path, target);
    return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
  });
}
