// src/scene.ts
// Current Arena scene via scry-ts parser.
// Reads Player.log, returns last SceneChange event's target scene.

import { parseLog, type LogEvent } from "../../scry-ts/src/parser";
import { DEFAULT_LOG } from "../../scry-ts/src/log";
import { existsSync, statSync } from "fs";

export interface SceneInfo {
  scene: string;
  from: string;
  ts: Date;
}

/** Returns current scene or null if Player.log missing/no scenes found. */
export async function currentScene(): Promise<SceneInfo | null> {
  if (!existsSync(DEFAULT_LOG)) return null;

  const text = await Bun.file(DEFAULT_LOG).text();
  const events = [...parseLog(text.split("\n"))];
  const stat = statSync(DEFAULT_LOG);

  let last: LogEvent | null = null;
  for (const ev of events) {
    if (ev.type === "scene") last = ev;
  }
  if (!last || last.type !== "scene") return null;

  return {
    scene: last.to,
    from: last.from,
    ts: stat.mtime,
  };
}

/** Check Player.log exists and return basic info. */
export function playerLogStatus(): { exists: boolean; path: string; age_s?: number } {
  const exists = existsSync(DEFAULT_LOG);
  if (!exists) return { exists, path: DEFAULT_LOG };
  const stat = statSync(DEFAULT_LOG);
  const age_s = Math.round((Date.now() - stat.mtime.getTime()) / 1000);
  return { exists, path: DEFAULT_LOG, age_s };
}
