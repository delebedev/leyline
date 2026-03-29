/**
 * Per-game metadata — cards manifest, notes, tags.
 *
 * Stored as ~/.scry/games/<timestamp>.meta.json alongside the raw log.
 * Cards are cached on first request (requires Arena SQLite DB).
 * Notes are anchored to gsId/turn for precise game-moment references.
 */

import { existsSync, readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { gamesDir } from "./catalog";

export interface GameMeta {
  cards: CardEntry[];
  tags: string[];
  notes: GameNote[];
}

export interface CardEntry {
  grpId: number;
  name: string;
  types: string[];
  subtypes: string[];
  power: string | null;
  toughness: string | null;
  isToken: boolean;
  ownerSeat: number;
  instanceIds: number[];
}

export interface GameNote {
  text: string;
  gsId: number | null;
  turn: number | null;
  phase: string | null;
  ts: string;
}

function metaPath(gameFile: string): string {
  return join(gamesDir(), gameFile.replace(".log", ".meta.json"));
}

export function loadMeta(gameFile: string): GameMeta {
  const path = metaPath(gameFile);
  if (!existsSync(path)) {
    return { cards: [], tags: [], notes: [] };
  }
  try {
    return JSON.parse(readFileSync(path, "utf-8"));
  } catch {
    return { cards: [], tags: [], notes: [] };
  }
}

export function saveMeta(gameFile: string, meta: GameMeta): void {
  writeFileSync(metaPath(gameFile), JSON.stringify(meta, null, 2) + "\n");
}
