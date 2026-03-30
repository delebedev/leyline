// src/telemetry.ts
// Append-only JSONL command log. One file per day in ~/.arena/log/.

import { appendFileSync, mkdirSync } from "fs";
import { join } from "path";
import { homedir } from "os";

const LOG_DIR = join(homedir(), ".arena", "log");

interface LogEntry {
  ts: string;
  cmd: string;
  args: string[];
  ms: number;
  ok: boolean;
  err?: string;
}

function logPath(): string {
  const date = new Date().toISOString().slice(0, 10);
  return join(LOG_DIR, `${date}.jsonl`);
}

export function logCommand(entry: LogEntry): void {
  try {
    mkdirSync(LOG_DIR, { recursive: true });
    appendFileSync(logPath(), JSON.stringify(entry) + "\n");
  } catch {
    // Telemetry must never crash the tool
  }
}
