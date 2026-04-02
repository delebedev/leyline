/**
 * Per-game metadata — cards manifest, notes, tags.
 *
 * Stored as ~/.scry/games/<timestamp>.meta.json alongside the raw log.
 * Cards are cached on first request (requires Arena SQLite DB).
 * Notes are anchored to gsId/turn for precise game-moment references.
 */

import { Database } from "bun:sqlite";
import { existsSync, readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { gamesDir } from "./catalog";
import { findArenaDb, resolveCardInfo } from "./cards";
import { Accumulator } from "./accumulator";
import type { Game } from "./games";
import type { Provenance } from "./provenance";

export interface GameMeta {
  cards: CardEntry[];
  tags: string[];
  notes: GameNote[];
  provenance?: Provenance;
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

/**
 * Build card manifest for a game by replaying GSMs through the accumulator,
 * then resolving grpIds via Arena SQLite DB. Returns null if DB unavailable.
 */
export function buildCardManifest(game: Game): CardEntry[] | null {
  const dbPath = findArenaDb();
  if (!dbPath) return null;
  const db = new Database(dbPath, { readonly: true });

  const cardData = new Map<number, { ownerSeat: number; instanceIds: Set<number> }>();
  const acc = new Accumulator();
  for (const gsm of game.greMessages) {
    acc.apply(gsm.raw);
    if (!acc.current) continue;
    for (const [, obj] of acc.current.objects) {
      if (!obj.grpId) continue;
      if (obj.type.includes("Ability")) continue;
      let entry = cardData.get(obj.grpId);
      if (!entry) {
        entry = { ownerSeat: obj.ownerSeatId, instanceIds: new Set() };
        cardData.set(obj.grpId, entry);
      }
      entry.instanceIds.add(obj.instanceId);
    }
  }

  const cardInfos = resolveCardInfo(db, [...cardData.keys()]);

  const cards: CardEntry[] = [];
  for (const [grpId, data] of cardData) {
    const info = cardInfos.get(grpId);
    cards.push({
      grpId,
      name: info?.name ?? `grp=${grpId}`,
      types: info?.types ?? [],
      subtypes: info?.subtypes ?? [],
      power: info?.power ?? null,
      toughness: info?.toughness ?? null,
      isToken: info?.isToken ?? false,
      ownerSeat: data.ownerSeat,
      instanceIds: [...data.instanceIds].sort((a, b) => a - b),
    });
  }

  return cards;
}
