/**
 * Game catalog — durable storage for Player.log game slices.
 *
 * Games are saved as raw log slices (verbatim Player.log lines between
 * game boundaries). A catalog.json index holds metadata for listing
 * without re-parsing.
 *
 * Storage: ~/.scry/games/<timestamp>.log + ~/.scry/catalog.json
 */

import { mkdirSync, existsSync, readdirSync, readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { homedir } from "os";

const SCRY_DIR = join(homedir(), ".scry");
const GAMES_DIR = join(SCRY_DIR, "games");
const CATALOG_PATH = join(SCRY_DIR, "catalog.json");

export interface CatalogEntry {
  /** Filename in games/ dir */
  file: string;
  /** Arena match ID */
  matchId: string | null;
  /** Game start timestamp from Player.log */
  startTimestamp: string | null;
  /** win/loss/draw/null */
  result: string | null;
  /** Max turn number */
  turns: number;
  /** Number of GRE messages */
  gsmCount: number;
  /** Saved at (ISO string) */
  savedAt: string;
  /** User notes */
  notes: string;
}

export interface Catalog {
  version: 1;
  games: CatalogEntry[];
}

export function ensureDirs(): void {
  mkdirSync(GAMES_DIR, { recursive: true });
}

export function loadCatalog(): Catalog {
  if (!existsSync(CATALOG_PATH)) {
    return { version: 1, games: [] };
  }
  try {
    return JSON.parse(readFileSync(CATALOG_PATH, "utf-8"));
  } catch {
    return { version: 1, games: [] };
  }
}

export function saveCatalog(catalog: Catalog): void {
  ensureDirs();
  writeFileSync(CATALOG_PATH, JSON.stringify(catalog, null, 2) + "\n");
}

/** Check if a game is already cataloged (by matchId + startTimestamp). */
export function isAlreadySaved(catalog: Catalog, matchId: string | null, startTimestamp: string | null): boolean {
  return catalog.games.some(
    (g) => g.matchId === matchId && g.startTimestamp === startTimestamp
  );
}

/** Generate filename from timestamp: 2026-03-29_16-10-08.log */
export function filenameFromTimestamp(ts: string | null): string {
  if (!ts) return `unknown_${Date.now()}.log`;
  // Input: "29/03/2026 16:10:08" → "2026-03-29_16-10-08"
  const match = ts.match(/(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2}):(\d{2})/);
  if (!match) return `unknown_${Date.now()}.log`;
  const [, dd, mm, yyyy, hh, min, ss] = match;
  return `${yyyy}-${mm}-${dd}_${hh}-${min}-${ss}.log`;
}

/** Save a raw log slice to disk and add to catalog. */
export function saveGame(
  rawLines: string[],
  meta: {
    matchId: string | null;
    startTimestamp: string | null;
    result: string | null;
    turns: number;
    gsmCount: number;
  },
): CatalogEntry {
  ensureDirs();

  const baseFilename = filenameFromTimestamp(meta.startTimestamp);
  // Dedup filename
  let filename = baseFilename;
  let finalPath = join(GAMES_DIR, filename);
  let counter = 1;
  while (existsSync(finalPath)) {
    filename = baseFilename.replace(".log", `_${counter}.log`);
    finalPath = join(GAMES_DIR, filename);
    counter++;
  }

  writeFileSync(finalPath, rawLines.join("\n") + "\n");

  const entry: CatalogEntry = {
    file: filename,
    matchId: meta.matchId,
    startTimestamp: meta.startTimestamp,
    result: meta.result,
    turns: meta.turns,
    gsmCount: meta.gsmCount,
    savedAt: new Date().toISOString(),
    notes: "",
  };

  const catalog = loadCatalog();
  catalog.games.push(entry);
  saveCatalog(catalog);

  return entry;
}

/** Read a saved game's raw log lines. */
export function readSavedGame(filename: string): string[] {
  const path = join(GAMES_DIR, filename);
  if (!existsSync(path)) {
    throw new Error(`Saved game not found: ${path}`);
  }
  return readFileSync(path, "utf-8").split("\n");
}

/** Path to games directory. */
export function gamesDir(): string {
  return GAMES_DIR;
}
