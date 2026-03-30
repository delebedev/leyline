import { appendFileSync, existsSync, readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { homedir } from "os";
import { loadEvents } from "../log";
import { detectGames } from "../games";
import { parseLog } from "../parser";
import { loadCatalog, readSavedGame, type CatalogEntry } from "../catalog";
import { loadMeta, saveMeta, type GameNote } from "../meta";

// Scratch notes persist across games — matched by matchId on save.
// Safe to play multiple games before saving. Only risk: if Player.log
// rotates (Arena restart) before `scry save --all`, the game is lost
// but orphaned scratch notes remain. They're harmless — unmatched
// notes are silently ignored by save.
const SCRATCH_NOTES = join(homedir(), ".scry", "scratch-notes.jsonl");

export async function noteCommand(args: string[]) {
  if (!args[0] || args[0] === "--help" || args[0] === "-h") {
    console.log('Usage: scry note "text" [flags]\n');
    console.log("Add a note anchored to a game moment.\n");
    console.log("Works during live games (notes saved to scratch, attached on scry save)\n");
    console.log("Flags:");
    console.log("  --game REF   Game reference (default: current/last)");
    console.log("  --gsid N     Anchor to specific gsId (default: last GSM)");
    return;
  }

  const text = args[0];

  let gameRef: string | null = null;
  let targetGsId: number | null = null;
  for (let i = 1; i < args.length; i++) {
    if (args[i] === "--game" && i + 1 < args.length) gameRef = args[++i];
    if (args[i] === "--gsid" && i + 1 < args.length) targetGsId = parseInt(args[++i], 10);
  }

  // Try saved game first
  const catalog = loadCatalog();
  let entry: CatalogEntry | undefined;
  if (gameRef) {
    entry = catalog.games.find((g) => g.file.includes(gameRef));
  } else {
    entry = catalog.games[catalog.games.length - 1];
  }

  // If no explicit --game, check if there's a live active game
  if (entry && !gameRef) {
    const events = await loadEvents();
    const liveGames = detectGames(events);
    const lastLive = liveGames[liveGames.length - 1];
    if (lastLive && lastLive.active) {
      return writeScratchNote(text, targetGsId, lastLive);
    }
  }

  if (entry) {
    return writeSavedNote(text, targetGsId, entry);
  }

  // No saved games — try live
  const events = await loadEvents();
  const liveGames = detectGames(events);
  if (liveGames.length > 0) {
    return writeScratchNote(text, targetGsId, liveGames[liveGames.length - 1]);
  }

  console.error("No games found. Play a game first.");
  process.exit(1);
}

function writeSavedNote(text: string, targetGsId: number | null, entry: CatalogEntry) {
  let gsId = targetGsId;
  let turn: number | null = null;
  let phase: string | null = null;

  if (gsId == null || turn == null) {
    const lines = readSavedGame(entry.file);
    const games = detectGames([...parseLog(lines)]);
    const game = games[games.length - 1];
    if (game && game.greMessages.length > 0) {
      if (gsId == null) {
        const last = game.greMessages[game.greMessages.length - 1];
        gsId = last.gsId;
        turn = last.turn;
        phase = last.step ? `${last.phase}/${last.step}` : last.phase;
      } else {
        const found = game.greMessages.find((g) => g.gsId === gsId);
        if (found) {
          turn = found.turn;
          phase = found.step ? `${found.phase}/${found.step}` : found.phase;
        }
      }
    }
  }

  const note: GameNote = { text, gsId, turn, phase, ts: new Date().toISOString() };
  const meta = loadMeta(entry.file);
  meta.notes.push(note);
  saveMeta(entry.file, meta);

  const anchor = gsId != null ? `T${turn ?? "?"} gs=${gsId} ${phase ?? ""}` : "unanchored";
  console.log(`Note added to ${entry.file.replace(".log", "")}:`);
  console.log(`  ${anchor}  "${text}"`);
}

function writeScratchNote(text: string, targetGsId: number | null, game: any) {
  let gsId = targetGsId;
  let turn: number | null = null;
  let phase: string | null = null;
  const matchId: string | null = game.matchId ?? null;

  if (game.greMessages?.length > 0) {
    if (gsId == null) {
      const last = game.greMessages[game.greMessages.length - 1];
      gsId = last.gsId;
      turn = last.turn;
      phase = last.step ? `${last.phase}/${last.step}` : last.phase;
    } else {
      const found = game.greMessages.find((g: any) => g.gsId === gsId);
      if (found) {
        turn = found.turn;
        phase = found.step ? `${found.phase}/${found.step}` : found.phase;
      }
    }
  }

  const note = { text, gsId, turn, phase, matchId, ts: new Date().toISOString() };
  appendFileSync(SCRATCH_NOTES, JSON.stringify(note) + "\n");

  const anchor = gsId != null ? `T${turn ?? "?"} gs=${gsId} ${phase ?? ""}` : "unanchored";
  console.log(`Note saved to scratch (will attach on scry save):`);
  console.log(`  ${anchor}  "${text}"`);
}

/** Read and clear scratch notes. Called by save command. */
export function consumeScratchNotes(): (GameNote & { matchId?: string })[] {
  if (!existsSync(SCRATCH_NOTES)) return [];
  const content = readFileSync(SCRATCH_NOTES, "utf-8");
  const notes = content
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  writeFileSync(SCRATCH_NOTES, "");
  return notes;
}
