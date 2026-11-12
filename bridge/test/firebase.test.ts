import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { parseGoogleServices, repoRoot, writeLocalProperties } from "../src/firebase.js";

const googleServices = JSON.stringify({
  project_info: { project_number: "123456789", project_id: "demo-relay" },
  client: [
    { client_info: { mobilesdk_app_id: "1:123:android:other", android_client_info: { package_name: "com.example.other" } }, api_key: [{ current_key: "other" }] },
    { client_info: { mobilesdk_app_id: "1:123:android:abc", android_client_info: { package_name: "dev.ayoub.wristle" } }, api_key: [{ current_key: "AIza-test" }] },
  ],
});

const config = { projectId: "demo-relay", appId: "1:123:android:abc", apiKey: "AIza-test", senderId: "123456789" };

describe("watch Firebase config", () => {
  it("takes the watch app's values from google-services.json", () => {
    expect(parseGoogleServices(googleServices)).toEqual(config);
    expect(() => parseGoogleServices("{}")).toThrow();
  });

  it("updates local.properties in place and keeps everything else", () => {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "wristle-props-")), "local.properties");
    fs.writeFileSync(file, "sdk.dir=C\\:/Android/Sdk\r\nfirebase.projectId=old\r\nclaude.apiKey=x\r\n");
    expect(writeLocalProperties(file, config)).toBe(true);
    expect(fs.readFileSync(file, "utf8")).toBe(
      "sdk.dir=C\\:/Android/Sdk\r\nfirebase.projectId=demo-relay\r\nclaude.apiKey=x\r\n" +
        "firebase.appId=1:123:android:abc\r\nfirebase.apiKey=AIza-test\r\nfirebase.senderId=123456789\r\n",
    );
    expect(writeLocalProperties(file, config)).toBe(false);
  });

  it("finds the repository checkout it runs from", () => {
    expect(repoRoot()).not.toBeNull();
  });
});
