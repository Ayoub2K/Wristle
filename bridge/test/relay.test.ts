import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { credentialsFile, installServiceAccount, loadServiceAccount, parseServiceAccount, RelayError } from "../src/relay.js";

const key = { type: "service_account", project_id: "demo", client_email: "bridge@demo.iam.gserviceaccount.com", private_key: "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----\n" };

beforeEach(() => {
  process.env.WRISTLE_BRIDGE_HOME = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-relay-"));
});

afterEach(() => {
  delete process.env.WRISTLE_BRIDGE_HOME;
});

describe("service account key", () => {
  it("rejects files that are not a service account key", () => {
    expect(() => parseServiceAccount("{not json")).toThrow(RelayError);
    expect(() => parseServiceAccount(JSON.stringify({ type: "authorized_user" }))).toThrow(RelayError);
  });

  it("is copied into the config folder and loaded back", () => {
    const source = path.join(os.tmpdir(), `key-${Date.now()}.json`);
    fs.writeFileSync(source, JSON.stringify(key));
    installServiceAccount(source);
    expect(loadServiceAccount().project_id).toBe("demo");
    if (process.platform !== "win32") expect(fs.statSync(credentialsFile()).mode & 0o777).toBe(0o600);
  });

  it("explains how to set it up when missing", () => {
    expect(() => loadServiceAccount()).toThrow(/relay setup/);
  });
});
