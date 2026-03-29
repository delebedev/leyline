/**
 * Shared log loading. Single entry point for all commands.
 */

import { resolve } from "path";
import { homedir } from "os";
import { parseLog, type LogEvent } from "./parser";

export const DEFAULT_LOG = resolve(homedir(), "Library/Logs/Wizards of the Coast/MTGA/Player.log");

/** Read Player.log and yield all events. */
export async function loadEvents(logPath?: string): Promise<LogEvent[]> {
  const path = logPath ?? DEFAULT_LOG;
  const file = Bun.file(path);
  if (!(await file.exists())) {
    console.error(`Player.log not found: ${path}`);
    process.exit(1);
  }
  const text = await file.text();
  return [...parseLog(text.split("\n"))];
}
