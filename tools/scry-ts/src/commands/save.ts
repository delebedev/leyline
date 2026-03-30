import { resolve } from "path";
import { homedir } from "os";
import { sliceGames } from "../slicer";
import { parseLog } from "../parser";
import { detectGames } from "../games";
import { loadCatalog, isAlreadySaved, saveGame } from "../catalog";
import { loadMeta, saveMeta, buildCardManifest } from "../meta";
import { DEFAULT_LOG } from "../log";
import { consumeScratchNotes } from "./note";

const PREV_LOG = resolve(homedir(), "Library/Logs/Wizards of the Coast/MTGA/Player-prev.log");

export async function saveCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry save [flags]\n");
    console.log("Save games from Player.log to durable storage.\n");
    console.log("Flags:");
    console.log("  --all        Include Player-prev.log too");
    console.log("  --dry-run    Show what would be saved without saving");
    return;
  }

  const includeAll = args.includes("--all");
  const dryRun = args.includes("--dry-run");

  const catalog = loadCatalog();
  let totalSaved = 0;
  let totalSkipped = 0;

  // Process log files
  const logFiles = [DEFAULT_LOG];
  if (includeAll) {
    const prevFile = Bun.file(PREV_LOG);
    if (await prevFile.exists()) {
      logFiles.unshift(PREV_LOG); // prev first (older games)
    }
  }

  for (const logPath of logFiles) {
    const file = Bun.file(logPath);
    if (!(await file.exists())) continue;

    const text = await file.text();
    const lines = text.split("\n");
    const label = logPath.endsWith("Player-prev.log") ? "Player-prev.log" : "Player.log";

    // Get game slices (line ranges)
    const slices = sliceGames(lines);

    // Also detect games via parser for metadata (result, turns, gsm count)
    const events = [...parseLog(lines)];
    const games = detectGames(events);

    for (let i = 0; i < slices.length; i++) {
      const slice = slices[i];
      // Cross-match by timestamp — sliceGames and detectGames may diverge
      // on edge cases (standalone GRE JSON without header)
      const game = games.find(
        (g) => g.startTimestamp === slice.startTimestamp
      ) ?? games[i] ?? null;

      const matchId = game?.matchId ?? slice.matchId;
      const startTs = game?.startTimestamp ?? slice.startTimestamp;

      if (isAlreadySaved(catalog, matchId, startTs)) {
        totalSkipped++;
        continue;
      }

      // Skip active games (no result yet, in current log)
      if (game && game.active && logPath === DEFAULT_LOG) {
        console.log(`  skip: game at ${startTs ?? "?"} (still active)`);
        continue;
      }

      const maxTurn = game?.greMessages.reduce((m, g) => Math.max(m, g.turn), 0) ?? 0;
      const gsmCount = game?.greMessages.length ?? 0;
      const result = game?.result ?? null;

      const rawLines = lines.slice(slice.startLine, slice.endLine + 1);

      if (dryRun) {
        console.log(`  would save: ${startTs ?? "?"} — ${Math.ceil(maxTurn / 2)} rounds, ${result ?? "no result"} (${rawLines.length} lines)`);
        totalSaved++;
        continue;
      }

      const entry = saveGame(rawLines, {
        matchId,
        startTimestamp: startTs,
        result,
        turns: maxTurn,
        gsmCount,
      });

      // Auto-resolve cards at save time
      if (game) {
        const cards = buildCardManifest(game);
        if (cards) {
          const meta = loadMeta(entry.file);
          meta.cards = cards;
          saveMeta(entry.file, meta);
        }
      }

      console.log(`  saved: ${entry.file} — ${Math.ceil(maxTurn / 2)} rounds, ${result ?? "no result"}`);
      totalSaved++;

      // Refresh catalog reference (saveGame mutates the file)
      catalog.games.push(entry);
    }
  }

  // Attach scratch notes to saved games
  if (!dryRun && totalSaved > 0) {
    const scratchNotes = consumeScratchNotes();
    if (scratchNotes.length > 0) {
      let attached = 0;
      for (const note of scratchNotes) {
        const target = catalog.games.find((g) => note.matchId && g.matchId === note.matchId);
        if (target) {
          const meta = loadMeta(target.file);
          meta.notes.push({ text: note.text, gsId: note.gsId, turn: note.turn, phase: note.phase, ts: note.ts });
          saveMeta(target.file, meta);
          attached++;
        }
      }
      if (attached > 0) {
        console.log(`  ${attached} scratch note${attached > 1 ? "s" : ""} attached`);
      }
    }
  }

  if (totalSaved === 0 && totalSkipped === 0) {
    console.log("No games found in Player.log");
  } else if (totalSaved === 0) {
    console.log(`All ${totalSkipped} games already saved.`);
  } else {
    const verb = dryRun ? "would save" : "saved";
    console.log(`\n${totalSaved} ${verb}, ${totalSkipped} already cataloged.`);
  }
}
