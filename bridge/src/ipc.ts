/** JSON-lines IPC between the daemon and bridge commands, over a named pipe (Windows) or Unix socket. */
import fs from "node:fs";
import net from "node:net";
import { ipcPath } from "./paths.js";

export type IpcMessage = Record<string, unknown>;
export type IpcHandler = (message: IpcMessage, reply: (response: IpcMessage) => void) => void;

export class DaemonAlreadyRunningError extends Error {}

function lines(socket: net.Socket, onLine: (line: string) => void): void {
  let buffer = "";
  socket.setEncoding("utf8");
  socket.on("data", (chunk: string) => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (line) onLine(line);
    }
  });
}

/** Whether something is already answering on the IPC endpoint. */
export async function isListening(endpoint = ipcPath()): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.createConnection(endpoint);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("error", () => resolve(false));
  });
}

export async function startIpcServer(handler: IpcHandler, endpoint = ipcPath()): Promise<net.Server> {
  if (await isListening(endpoint)) throw new DaemonAlreadyRunningError(`a bridge daemon is already running (${endpoint})`);
  // A leftover socket file from a crashed daemon would make listen() fail; named pipes vanish on their own.
  if (process.platform !== "win32") fs.rmSync(endpoint, { force: true });

  const server = net.createServer((socket) => {
    socket.on("error", () => socket.destroy());
    lines(socket, (line) => {
      let message: IpcMessage;
      try {
        message = JSON.parse(line) as IpcMessage;
      } catch {
        socket.write(JSON.stringify({ ok: false, error: "malformed message" }) + "\n");
        return;
      }
      handler(message, (response) => {
        if (!socket.destroyed) socket.write(JSON.stringify({ id: message.id, ...response }) + "\n");
      });
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(endpoint, () => {
      server.off("error", reject);
      resolve();
    });
  });
  if (process.platform !== "win32") fs.chmodSync(endpoint, 0o600);
  return server;
}

/** Sends one request and waits for its response; null when no daemon answers in time. */
export async function request(message: IpcMessage, endpoint = ipcPath(), timeoutMs = 2000): Promise<IpcMessage | null> {
  return new Promise((resolve) => {
    const id = Math.floor(Math.random() * 1e9);
    const socket = net.createConnection(endpoint);
    const done = (result: IpcMessage | null) => {
      clearTimeout(timer);
      socket.destroy();
      resolve(result);
    };
    const timer = setTimeout(() => done(null), timeoutMs);
    socket.once("error", () => done(null));
    socket.once("connect", () => socket.write(JSON.stringify({ ...message, id }) + "\n"));
    lines(socket, (line) => {
      try {
        const response = JSON.parse(line) as IpcMessage;
        if (response.id === id) done(response);
      } catch {
        /* ignore malformed lines */
      }
    });
  });
}
