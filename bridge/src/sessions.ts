/**
 * Claude Code sessions started from the watch (P2-M3), run through the Claude Agent SDK.
 *
 * Read-only for now: the session can only read and search the project. Approving other tools from the
 * watch comes in P2-M4. Sessions must use an API key (ANTHROPIC_API_KEY): the Agent SDK may not run on a
 * claude.ai login, so a session that reports any other credential source is stopped.
 */
import { randomUUID } from "node:crypto";
import path from "node:path";
import type { Options, SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { type BridgeConfig, isAllowed } from "./config.js";
import type { Message } from "./crypto.js";
import type { Logger } from "./log.js";

export type QueryFn = (params: { prompt: string; options: Options }) => AsyncIterable<SDKMessage>;
export type Outgoing = Omit<Message, "ts">;

export const READ_ONLY_TOOLS = ["Read", "Glob", "Grep"];
const MAX_PROMPT_CHARS = 2_000;
const MAX_LINE_CHARS = 120;
const MAX_SUMMARY_CHARS = 1_500;
/** At most one progress update per interval, to stay well within Firestore's free daily writes. */
const PROGRESS_INTERVAL_MS = 2_500;
const LINES_PER_UPDATE = 4;

// The answer is read on a small watch screen and may be read aloud.
const WATCH_NOTE =
  "The user started this session from a smartwatch and reads your final answer there. " +
  "End with a short plain-text answer (a few sentences, no Markdown tables or code blocks).";

export interface SessionDeps {
  query: QueryFn;
  config: () => BridgeConfig;
  send: (message: Outgoing) => Promise<void>;
  log: Logger;
  env?: NodeJS.ProcessEnv;
  progressIntervalMs?: number;
}

function shorten(text: string, max = MAX_LINE_CHARS): string {
  const line = text.replace(/\s+/g, " ").trim();
  return line.length <= max ? line : line.slice(0, max - 1).trimEnd() + "…";
}

/** One short line per tool call, e.g. "Reading src/app.ts". */
export function describeToolUse(name: string, input: Record<string, unknown>, cwd: string): string {
  const rel = (p: unknown) => (typeof p === "string" ? path.relative(cwd, path.resolve(cwd, p)) || "." : "");
  switch (name) {
    case "Read":
      return `Reading ${rel(input.file_path)}`;
    case "Glob":
      return `Finding ${String(input.pattern ?? "files")}`;
    case "Grep":
      return `Searching for "${String(input.pattern ?? "")}"`;
    default:
      return `Using ${name}`;
  }
}

/** Progress lines for one SDK message: what the main agent says and which tools it calls. */
export function describe(message: SDKMessage, cwd: string): string[] {
  if (message.type !== "assistant" || message.parent_tool_use_id !== null) return [];
  const lines: string[] = [];
  for (const block of message.message.content) {
    if (block.type === "text" && block.text.trim()) lines.push(shorten(block.text));
    if (block.type === "tool_use") lines.push(shorten(describeToolUse(block.name, (block.input ?? {}) as Record<string, unknown>, cwd)));
  }
  return lines;
}

interface Running {
  id: string;
  abort: AbortController;
}

export class SessionManager {
  private running: Running | null = null;

  constructor(private readonly deps: SessionDeps) {}

  get busy(): boolean {
    return this.running !== null;
  }

  /** Handles a message from the watch. Unknown types are ignored, so newer watches don't break older bridges. */
  async handle(message: Message): Promise<void> {
    switch (message.type) {
      case "projects": {
        const config = this.deps.config();
        await this.deps.send({ type: "projects", projects: config.projects.map((p) => ({ id: p.id, name: p.name })), busy: this.busy });
        break;
      }
      case "start":
        await this.start(String(message.project ?? ""), String(message.prompt ?? ""));
        break;
      case "stop":
        if (this.running && message.sessionId === this.running.id) this.running.abort.abort();
        break;
    }
  }

  private async fail(text: string): Promise<void> {
    await this.deps.send({ type: "error", message: text });
  }

  private async start(projectId: string, rawPrompt: string): Promise<void> {
    const config = this.deps.config();
    const project = config.projects.find((p) => p.id === projectId);
    const prompt = rawPrompt.trim();
    const env = this.deps.env ?? process.env;
    if (!project || !isAllowed(config, project.path)) return this.fail("That project is no longer allowed on the computer.");
    if (!prompt) return this.fail("The instruction was empty.");
    if (prompt.length > MAX_PROMPT_CHARS) return this.fail("The instruction is too long.");
    if (this.running) return this.fail("A session is already running. Stop it first.");
    if (!env.ANTHROPIC_API_KEY) return this.fail("No API key on the computer: set ANTHROPIC_API_KEY and restart the bridge.");

    const running: Running = { id: randomUUID().slice(0, 8), abort: new AbortController() };
    this.running = running;
    this.deps.log(`session ${running.id} started in ${project.name}`);
    await this.deps.send({ type: "session", sessionId: running.id, project: project.name, prompt, state: "running" });
    void this.run(running, project.path, prompt).finally(() => {
      if (this.running === running) this.running = null;
    });
  }

  private async run(running: Running, cwd: string, prompt: string): Promise<void> {
    const { send, log } = this.deps;
    let pending: string[] = [];
    let timer: NodeJS.Timeout | undefined;
    let chain = Promise.resolve();
    const flush = () => {
      timer = undefined;
      if (pending.length === 0) return;
      const lines = pending.slice(-LINES_PER_UPDATE);
      pending = [];
      // Keep updates in order even when Firestore is slow.
      chain = chain.then(() => send({ type: "progress", sessionId: running.id, lines })).catch((e) => log(`progress not sent: ${String(e)}`));
    };
    const finish = async (done: Outgoing) => {
      if (timer) clearTimeout(timer);
      flush();
      await chain;
      // Free the slot before telling the watch, so it can start the next session right away.
      if (this.running === running) this.running = null;
      await send(done);
    };

    try {
      const messages = this.deps.query({
        prompt,
        options: {
          cwd,
          model: this.deps.config().model,
          tools: READ_ONLY_TOOLS,
          allowedTools: READ_ONLY_TOOLS,
          permissionMode: "dontAsk",
          // Project settings and CLAUDE.md only; user settings could swap in another credential.
          settingSources: ["project"],
          systemPrompt: { type: "preset", preset: "claude_code", append: WATCH_NOTE },
          abortController: running.abort,
          env: { ...(this.deps.env ?? process.env) },
        },
      });
      for await (const message of messages) {
        if (message.type === "system" && message.subtype === "init") {
          if (message.apiKeySource !== "ANTHROPIC_API_KEY") {
            running.abort.abort();
            await finish({ type: "done", sessionId: running.id, ok: false, summary: "The session did not use the API key, so it was stopped." });
            return;
          }
          await send({ type: "session", sessionId: running.id, state: "running", model: message.model });
          continue;
        }
        if (message.type === "result") {
          const ok = message.subtype === "success" && !message.is_error;
          const summary = message.subtype === "success" ? shorten(message.result, MAX_SUMMARY_CHARS) : `Ended early (${message.subtype}).`;
          log(`session ${running.id} finished: ${message.subtype}, $${message.total_cost_usd.toFixed(4)}`);
          await finish({ type: "done", sessionId: running.id, ok, summary, costUsd: message.total_cost_usd, turns: message.num_turns });
          return;
        }
        pending.push(...describe(message, cwd));
        if (pending.length > 0 && !timer) timer = setTimeout(flush, this.deps.progressIntervalMs ?? PROGRESS_INTERVAL_MS);
      }
      await finish({ type: "done", sessionId: running.id, ok: false, summary: running.abort.signal.aborted ? "Stopped." : "The session ended without a result." });
    } catch (e) {
      const stopped = running.abort.signal.aborted;
      log(`session ${running.id} ${stopped ? "stopped" : `failed: ${String(e)}`}`);
      await finish({ type: "done", sessionId: running.id, ok: false, summary: stopped ? "Stopped." : `Failed: ${shorten(String(e))}` }).catch(() => undefined);
    }
  }
}

/** The real Agent SDK, loaded on first use: it is large and only the daemon needs it. */
export const agentSdkQuery: QueryFn = (params) => {
  const iterator = (async function* () {
    const { query } = await import("@anthropic-ai/claude-agent-sdk");
    yield* query(params);
  })();
  return iterator;
};
