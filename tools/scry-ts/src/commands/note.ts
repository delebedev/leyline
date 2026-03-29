import { loadEvents } from "../log";
import { detectGames } from "../games";
import { parseLog } from "../parser";
import { loadCatalog, readSavedGame, type CatalogEntry } from "../catalog";
import { loadMeta, saveMeta, type GameNote } from "../meta";
import { stripPrefix } from "../format";

export async function noteCommand(args: string[]) {
  if (!args[0] || args[0] === "--help" || args[0] === "-h") {
    console.log('Usage: scry note "text" [flags]\n');
    console.log("Add a note anchored to a game moment.\n");
    console.log("Flags:");
    console.log("  --game REF   Game reference (default: last saved)");
    console.log("  --gsid N     Anchor to specific gsId (default: last GSM)");
    return;
  }

  const text = args[0];

  // Find target game in catalog
  let gameRef: string | null = null;
  let targetGsId: number | null = null;
  for (let i = 1; i < args.length; i++) {
    if (args[i] === "--game" && i + 1 < args.length) gameRef = args[++i];
    if (args[i] === "--gsid" && i + 1 < args.length) targetGsId = parseInt(args[++i], 10);
  }

  const catalog = loadCatalog();
  let entry: CatalogEntry | undefined;

  if (gameRef) {
    entry = catalog.games.find((g) => g.file.includes(gameRef));
  } else {
    // Default: last saved game
    entry = catalog.games[catalog.games.length - 1];
  }

  if (!entry) {
    // If no saved game, try to anchor to live log (just record the note without game file)
    console.error("No saved game found. Run: scry save");
    process.exit(1);
  }

  // Resolve gsId + turn/phase context
  let gsId = targetGsId;
  let turn: number | null = null;
  let phase: string | null = null;

  if (gsId == null || turn == null) {
    // Parse the saved game to find context
    const lines = readSavedGame(entry.file);
    const games = detectGames([...parseLog(lines)]);
    const game = games[games.length - 1];

    if (game && game.greMessages.length > 0) {
      if (gsId == null) {
        // Default: last GSM
        const last = game.greMessages[game.greMessages.length - 1];
        gsId = last.gsId;
        turn = last.turn;
        phase = last.step ? `${last.phase}/${last.step}` : last.phase;
      } else {
        // Find the specified gsId for context
        const found = game.greMessages.find((g) => g.gsId === gsId);
        if (found) {
          turn = found.turn;
          phase = found.step ? `${found.phase}/${found.step}` : found.phase;
        }
      }
    }
  }

  const note: GameNote = {
    text,
    gsId,
    turn,
    phase,
    ts: new Date().toISOString(),
  };

  const meta = loadMeta(entry.file);
  meta.notes.push(note);
  saveMeta(entry.file, meta);

  const anchor = gsId != null ? `T${turn ?? "?"} gs=${gsId} ${phase ?? ""}` : "unanchored";
  console.log(`Note added to ${entry.file.replace(".log", "")}:`);
  console.log(`  ${anchor}  "${text}"`);
}
