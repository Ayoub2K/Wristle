import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { describe, expect, it } from "vitest";
import type { BridgeConfig } from "../src/config.js";
import { describeToolUse, type Outgoing, type QueryFn, READ_ONLY_TOOLS, SessionManager } from "../src/sessions.js";

const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "wristle-session-"));
const config: BridgeConfig = { version: 1, projects: [{ id: "p1", name: "demo", path: projectDir }], model: "claude-opus-5-5" };

// Only the fields the session manager reads; the SDK's types are much wider.
const init = (apiKeySource: string) => ({ type: "system", subtype: "init", apiKeySource, model: "claude-opus-5-5" }) as unknown as SDKMessage;
const assistant = (content: unknown[]) => ({ type: "assistant", parent_tool_use_id: null, message: { content } }) as unknown as SDKMessage;
const result = (text: string) =>
  ({ type: "result", subtype: "success", is_error: false, result: text, total_cost_usd: 0.0123, num_turns: 3 }) as unknown as SDKMessage;

function setup(messages: SDKMessage[], env: NodeJS.ProcessEnv = { ANTHROPIC_API_KEY: "test" }) {
  const sent: Outgoing[] = [];
  const calls: Parameters<QueryFn>[0][] = [];
  let finished!: () => void;
  const done = new Promise<void>((resolve) => (finished = resolve));
  const query: QueryFn = (params) => {
    calls.push(params);
    return (async function* () {
      yield* messages;
    })();
  };
  const manager = new SessionManager({
    query,
    config: () => config,
    env,
    log: () => {},
    progressIntervalMs: 1,
    send: async (message) => {
      sent.push(message);
      if (message.type === "done") finished();
    },
  });
  return { manager, sent, calls, done };
}

describe("progress lines", () => {
  it("describe tool calls relative to the project", () => {
    expect(describeToolUse("Read", { file_path: path.join(projectDir, "src", "a.ts") }, projectDir)).toBe(`Reading ${path.join("src", "a.ts")}`);
    expect(describeToolUse("Grep", { pattern: "TODO" }, projectDir)).toBe('Searching for "TODO"');
    expect(describeToolUse("Bash", {}, projectDir)).toBe("Using Bash");
  });
});

describe("watch-started sessions", () => {
  it("lists the allowed projects", async () => {
    const { manager, sent } = setup([]);
    await manager.handle({ type: "projects", ts: Date.now() });
    expect(sent).toEqual([{ type: "projects", projects: [{ id: "p1", name: "demo" }], busy: false }]);
  });

  it("runs a read-only session with the API key and reports progress and the answer", async () => {
    const { manager, sent, calls, done } = setup([
      init("ANTHROPIC_API_KEY"),
      assistant([
        { type: "text", text: "Let me look." },
        { type: "tool_use", name: "Glob", input: { pattern: "**/*.ts" } },
      ]),
      result("There are two TypeScript files."),
    ]);
    await manager.handle({ type: "start", ts: Date.now(), project: "p1", prompt: "  How many ts files?  " });
    await done;

    const options = calls[0]!.options;
    expect(calls[0]!.prompt).toBe("How many ts files?");
    expect(options).toMatchObject({ cwd: projectDir, model: "claude-opus-5-5", tools: READ_ONLY_TOOLS, permissionMode: "dontAsk", settingSources: ["project"] });
    expect(sent.map((m) => m.type)).toEqual(["session", "session", "progress", "done"]);
    expect(sent[2]).toMatchObject({ lines: ["Let me look.", "Finding **/*.ts"] });
    expect(sent[3]).toMatchObject({ ok: true, summary: "There are two TypeScript files.", costUsd: 0.0123, turns: 3 });
    expect(manager.busy).toBe(false);
  });

  it("stops a session that does not use the API key", async () => {
    const { manager, sent, done } = setup([init("none"), result("should not get here")]);
    await manager.handle({ type: "start", ts: Date.now(), project: "p1", prompt: "hi" });
    await done;
    expect(sent.at(-1)).toMatchObject({ type: "done", ok: false });
    expect(sent.some((m) => m.summary === "should not get here")).toBe(false);
  });

  it("refuses without an API key, unknown projects and empty instructions", async () => {
    const noKey = setup([], {});
    await noKey.manager.handle({ type: "start", ts: Date.now(), project: "p1", prompt: "hi" });
    expect(noKey.sent).toEqual([{ type: "error", message: expect.stringMatching(/ANTHROPIC_API_KEY/) }]);

    const { manager, sent, calls } = setup([]);
    await manager.handle({ type: "start", ts: Date.now(), project: "elsewhere", prompt: "hi" });
    await manager.handle({ type: "start", ts: Date.now(), project: "p1", prompt: "   " });
    expect(sent.map((m) => m.type)).toEqual(["error", "error"]);
    expect(calls).toHaveLength(0);
  });
});
