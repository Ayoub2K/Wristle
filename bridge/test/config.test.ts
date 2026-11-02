import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { addProject, type BridgeConfig, ConfigError, isAllowed, loadConfig, removeProject, saveConfig } from "../src/config.js";

let root: string;
let config: BridgeConfig;

beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-config-"));
  fs.mkdirSync(path.join(root, "proj"));
  fs.mkdirSync(path.join(root, "proj2"));
  config = { version: 1, projects: [] };
});

describe("projects", () => {
  it("adds a folder with its name and a stable id", () => {
    const project = addProject(config, path.join(root, "proj"));
    expect(project.name).toBe("proj");
    expect(project.id).toMatch(/^[0-9a-f]{8}$/);
    expect(config.projects).toHaveLength(1);
  });

  it("rejects missing folders and duplicates", () => {
    expect(() => addProject(config, path.join(root, "missing"))).toThrow(ConfigError);
    addProject(config, path.join(root, "proj"));
    expect(() => addProject(config, path.join(root, "proj"))).toThrow(ConfigError);
  });

  it("removes by id, name or path", () => {
    const a = addProject(config, path.join(root, "proj"));
    addProject(config, path.join(root, "proj2"), "second");
    expect(removeProject(config, a.id).path).toBe(a.path);
    expect(removeProject(config, "second").name).toBe("second");
    expect(() => removeProject(config, "nothing")).toThrow(ConfigError);
  });

  it("allows only the folder itself and folders inside it", () => {
    addProject(config, path.join(root, "proj"));
    expect(isAllowed(config, path.join(root, "proj"))).toBe(true);
    expect(isAllowed(config, path.join(root, "proj", "src"))).toBe(true);
    expect(isAllowed(config, path.join(root, "proj2"))).toBe(false);
    expect(isAllowed(config, root)).toBe(false);
  });

  it("round-trips through the config file", () => {
    const file = path.join(root, "config.json");
    addProject(config, path.join(root, "proj"));
    saveConfig(config, file);
    expect(loadConfig(file)).toEqual(config);
  });

  it("treats a missing config file as empty and a broken one as an error", () => {
    expect(loadConfig(path.join(root, "none.json")).projects).toEqual([]);
    const broken = path.join(root, "broken.json");
    fs.writeFileSync(broken, "{not json");
    expect(() => loadConfig(broken)).toThrow(ConfigError);
  });
});
