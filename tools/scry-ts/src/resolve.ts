/**
 * Unified game resolution — finds a game from live Player.log or saved catalog.
 *
 * Resolution order:
 * 1. If --game given, try catalog first (substring match on filename), then live log by index
 * 2. If no --game, use last game from live Player.log
 */

import { loadEvents } from "./log";
import { detectGames, type Game } from "./games";
import { loadCatalog, readSavedGame, type CatalogEntry } from "./catalog";
import { parseLog } from "./parser";

export interface ResolvedGame {
  game: Game;
  source: "live" | "saved";
  label: string;
}

/** Parse --game flag from args. Returns the value or null. */
export function parseGameFlag(args: string[]): string | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--game" && i + 1 < args.length) {
      return args[i + 1];
    }
  }
  return null;
}

/** Resolve a game reference to a Game object. */
export async function resolveGame(args: string[]): Promise<ResolvedGame> {
  const gameRef = parseGameFlag(args);

  // Try catalog first if a reference is given
  if (gameRef && gameRef !== "last") {
    const fromCatalog = resolveFromCatalog(gameRef);
    if (fromCatalog) return fromCatalog;

    // Try as numeric index into live log
    const asNum = parseInt(gameRef, 10);
    if (!isNaN(asNum)) {
      const live = await resolveFromLive(asNum);
      if (live) return live;
    }

    console.error(`Game not found: ${gameRef}`);
    process.exit(1);
  }

  // Default: last game from live log
  return await resolveFromLive(null);
}

function resolveFromCatalog(ref: string): ResolvedGame | null {
  const catalog = loadCatalog();
  // Substring match on filename
  const match = catalog.games.find((g) => g.file.includes(ref));
  if (!match) return null;

  const lines = readSavedGame(match.file);
  const events = [...parseLog(lines)];
  const games = detectGames(events);
  if (games.length === 0) return null;

  // Saved files contain one game (or take the last if multiple)
  const game = games[games.length - 1];
  return {
    game,
    source: "saved",
    label: match.file.replace(".log", ""),
  };
}

async function resolveFromLive(index: number | null): Promise<ResolvedGame> {
  const events = await loadEvents();
  const games = detectGames(events);

  if (games.length === 0) {
    console.error("No games found in Player.log");
    process.exit(1);
  }

  const idx = index ?? games.length;
  const game = games[idx - 1];
  if (!game) {
    console.error(`Game #${idx} not found (${games.length} in Player.log)`);
    process.exit(1);
  }

  return {
    game,
    source: "live",
    label: `Player.log #${idx}`,
  };
}
