// src/scene.ts
// Current Arena scene via scry-ts parser.
// Combines SceneChange events with GRE game detection for real server support.

import { parseLog, type LogEvent } from "../../scry-ts/src/parser";
import { detectGames } from "../../scry-ts/src/games";
import { DEFAULT_LOG } from "../../scry-ts/src/log";
import { existsSync, statSync } from "fs";

export interface SceneInfo {
  scene: string;
  from: string;
  ts: Date;
  inGame: boolean;
  matchId: string | null;
}

/** Returns current scene. Detects InGame via GRE events even without SceneChange. */
export async function currentScene(): Promise<SceneInfo | null> {
  if (!existsSync(DEFAULT_LOG)) return null;

  const text = await Bun.file(DEFAULT_LOG).text();
  const events = [...parseLog(text.split("\n"))];
  const stat = statSync(DEFAULT_LOG);

  // Find last scene change
  let lastScene: LogEvent | null = null;
  for (const ev of events) {
    if (ev.type === "scene") lastScene = ev;
  }

  // Detect active game via scry-ts game boundary detection
  const games = detectGames(events);
  const activeGame = games.find(g => g.active);

  // If there's an active game, we're InGame regardless of scene
  if (activeGame) {
    return {
      scene: "InGame",
      from: lastScene?.type === "scene" ? lastScene.to : "unknown",
      ts: stat.mtime,
      inGame: true,
      matchId: activeGame.matchId,
    };
  }

  if (!lastScene || lastScene.type !== "scene") return null;

  return {
    scene: lastScene.to,
    from: lastScene.from,
    ts: stat.mtime,
    inGame: false,
    matchId: null,
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
