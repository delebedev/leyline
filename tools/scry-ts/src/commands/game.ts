import { loadEvents } from "../log";
import { detectGames, type Game } from "../games";
import { loadCatalog, readSavedGame, type CatalogEntry } from "../catalog";
import { loadMeta, saveMeta, buildCardManifest, type CardEntry } from "../meta";
import { parseLog } from "../parser";

async function loadAllGames(): Promise<Game[]> {
  const events = await loadEvents();
  const games = detectGames(events);
  if (games.length === 0) {
    console.error("No games found in Player.log");
    process.exit(1);
  }
  return games;
}

export async function gameCommand(args: string[]) {
  const verb = args[0];

  if (verb === "--help" || verb === "-h") {
    console.log("Usage: scry game [command]\n");
    console.log("Commands:");
    console.log("  list         List all games in Player.log");
    console.log("  search TERM  Search across saved games");
    console.log("  cards        Card manifest for a game");
    console.log("  notes        Show notes for a game");
    console.log("  <N>          Show game #N detail");
    console.log("  last         Show last game detail (default)");
    console.log("\nFlags:");
    console.log("  --game REF   Game reference (catalog filename or live index)");
    console.log("  --saved      List from catalog instead of live log");
    return;
  }

  if (verb === "list") {
    const useSaved = args.includes("--saved");
    if (useSaved) {
      await gameListSaved();
    } else {
      await gameList();
    }
  } else if (verb === "search") {
    await gameSearch(args.slice(1));
  } else if (verb === "cards") {
    await gameCards(args.slice(1));
  } else if (verb === "notes") {
    await gameNotes(args.slice(1));
  } else {
    await gameShow(verb ?? "last");
  }
}

async function gameList() {
  const games = await loadAllGames();

  console.log(`${"#".padStart(3)}  ${"Start".padEnd(18)}  ${"Rounds".padStart(6)}  ${"GSMs".padStart(4)}  ${"Result".padEnd(6)}  Status`);
  console.log("—".repeat(64));

  for (const game of games) {
    const start = game.startTimestamp ?? "—";
    const maxTurn = game.greMessages.reduce((m, g) => Math.max(m, g.turn), 0);
    const rounds = Math.ceil(maxTurn / 2);
    const result = game.result ?? "—";
    const status = game.active ? "active" : "";
    console.log(
      `${String(game.index).padStart(3)}  ${start.padEnd(18)}  ${String(rounds).padStart(6)}  ${String(game.greMessages.length).padStart(4)}  ${result.padEnd(6)}  ${status}`
    );
  }
}

async function gameListSaved() {
  const catalog = loadCatalog();
  if (catalog.games.length === 0) {
    console.log("No saved games. Run: scry save");
    return;
  }

  console.log(`${"File".padEnd(26)}  ${"Rounds".padStart(6)}  ${"GSMs".padStart(4)}  ${"Result".padEnd(6)}  Notes`);
  console.log("—".repeat(70));

  for (const entry of catalog.games) {
    const rounds = Math.ceil(entry.turns / 2);
    const meta = loadMeta(entry.file);
    const noteCount = meta.notes.length > 0 ? `${meta.notes.length} notes` : "";
    const tags = meta.tags.length > 0 ? meta.tags.map((t) => `#${t}`).join(" ") : "";
    const extra = [noteCount, tags].filter(Boolean).join("  ");
    console.log(
      `${entry.file.replace(".log", "").padEnd(26)}  ${String(rounds).padStart(6)}  ${String(entry.gsmCount).padStart(4)}  ${(entry.result ?? "—").padEnd(6)}  ${extra}`
    );
  }
}

async function gameShow(which: string) {
  const games = await loadAllGames();

  const idx = which === "last" ? games.length : parseInt(which, 10);
  const game = games[idx - 1];
  if (!game) {
    console.error(`Game #${idx} not found (${games.length} available)`);
    process.exit(1);
  }

  const maxTurn = game.greMessages.reduce((m, g) => Math.max(m, g.turn), 0);
  const rounds = Math.ceil(maxTurn / 2);
  const status = game.active ? " (active)" : "";
  const result = game.result ? `  result=${game.result}` : "";

  console.log(`Game #${game.index}${status}${result}`);
  console.log(`  match:  ${game.matchId ?? "—"}`);
  console.log(`  start:  ${game.startTimestamp ?? "—"}`);
  console.log(`  turns:  ${maxTurn} (${rounds} rounds)`);
  console.log(`  GSMs:   ${game.greMessages.length}`);
  console.log("");

  // Annotation histogram
  const counts = new Map<string, number>();
  for (const gsm of game.greMessages) {
    for (const t of gsm.annotationTypes) {
      counts.set(t, (counts.get(t) ?? 0) + 1);
    }
  }

  if (counts.size > 0) {
    const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
    const max = sorted[0][1];
    const barWidth = 24;

    console.log("Annotations:");
    for (const [type, count] of sorted) {
      const bar = "█".repeat(Math.max(1, Math.round((count / max) * barWidth)));
      console.log(`  ${String(count).padStart(4)}  ${bar}  ${type}`);
    }
  }
}

async function gameCards(args: string[]) {
  // Resolve game — prefer saved, fall back to live
  let gameFile: string | null = null;
  let game: Game | null = null;

  const gameRef = args.find((a) => !a.startsWith("-"));

  // Try catalog
  const catalog = loadCatalog();
  let entry: CatalogEntry | undefined;
  if (gameRef) {
    entry = catalog.games.find((g) => g.file.includes(gameRef));
  } else {
    entry = catalog.games[catalog.games.length - 1];
  }

  if (entry) {
    gameFile = entry.file;
    const meta = loadMeta(gameFile);
    // Return cached cards if available
    if (meta.cards.length > 0) {
      printCards(meta.cards, gameFile);
      return;
    }
    // Parse saved game
    const lines = readSavedGame(gameFile);
    const games = detectGames([...parseLog(lines)]);
    game = games[games.length - 1] ?? null;
  }

  if (!game) {
    const games = await loadAllGames();
    game = games[games.length - 1];
  }

  const cards = buildCardManifest(game);
  if (!cards) {
    console.error("Arena card DB not found. Launch Arena at least once.");
    process.exit(1);
  }

  // Cache in meta if saved game
  if (gameFile) {
    const meta = loadMeta(gameFile);
    meta.cards = cards;
    saveMeta(gameFile, meta);
  }

  printCards(cards, gameFile ?? "live");
}

// TODO: performance — currently reads every saved log file sequentially.
// For large catalogs (100+ games), consider building a search index at save
// time, or parallelizing reads with Bun's worker threads.
async function gameSearch(args: string[]) {
  const term = args.find((a) => !a.startsWith("-"));
  if (!term) {
    console.error("Usage: scry game search <term>");
    process.exit(1);
  }
  const termLower = term.toLowerCase();

  const catalog = loadCatalog();
  if (catalog.games.length === 0) {
    console.log("No saved games. Run: scry save");
    return;
  }

  let totalHits = 0;

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    const label = entry.file.replace(".log", "");

    // Phase 1: search card names in meta (fast, enriched)
    const matchedCards = meta.cards
      .filter((c) => c.name.toLowerCase().includes(termLower))
      .map((c) => c.name);

    if (matchedCards.length > 0) {
      console.log(`${label}  cards: ${matchedCards.join(", ")}`);
      totalHits++;
      continue; // card match is sufficient — skip raw grep
    }

    // Phase 2: search notes
    const matchedNotes = meta.notes.filter((n) => n.text.toLowerCase().includes(termLower));
    if (matchedNotes.length > 0) {
      for (const note of matchedNotes) {
        const anchor = note.gsId != null ? `T${note.turn} gs=${note.gsId}` : "";
        console.log(`${label}  note: "${note.text}" ${anchor}`);
      }
      totalHits++;
      continue;
    }

    // Phase 3: search tags
    if (meta.tags.some((t) => t.toLowerCase().includes(termLower))) {
      console.log(`${label}  tag: ${meta.tags.filter((t) => t.toLowerCase().includes(termLower)).join(", ")}`);
      totalHits++;
      continue;
    }

    // Phase 4: raw log grep (protocol terms — grpIds, annotation types, etc.)
    try {
      const lines = readSavedGame(entry.file);
      let rawHits = 0;
      for (const line of lines) {
        if (line.toLowerCase().includes(termLower)) {
          rawHits++;
          if (rawHits === 1) {
            // Show first match context
            const idx = line.toLowerCase().indexOf(termLower);
            const start = Math.max(0, idx - 30);
            const end = Math.min(line.length, idx + term.length + 50);
            const snippet = (start > 0 ? "..." : "") + line.substring(start, end).trim() + (end < line.length ? "..." : "");
            console.log(`${label}  raw: ${snippet}`);
          }
        }
      }
      if (rawHits > 0) {
        if (rawHits > 1) console.log(`${" ".repeat(label.length)}  (${rawHits} matches)`);
        totalHits++;
      }
    } catch {}
  }

  if (totalHits === 0) {
    console.log(`No matches for "${term}" across ${catalog.games.length} saved games.`);
  } else {
    console.log(`\n${totalHits} games matched`);
  }
}

function printCards(cards: CardEntry[], label: string) {
  console.log(`Cards (${label}):\n`);

  const BASICS = new Set(["Plains", "Island", "Swamp", "Mountain", "Forest"]);

  for (const seat of [1, 2]) {
    const seatCards = cards
      .filter((c) => c.ownerSeat === seat && !BASICS.has(c.name))
      .sort((a, b) => a.name.localeCompare(b.name));

    if (seatCards.length === 0) continue;

    const seatLabel = seat === 1 ? "Seat 1 (you)" : "Seat 2 (opponent)";
    console.log(`${seatLabel}:`);
    for (const c of seatCards) {
      const pt = c.power != null ? ` ${c.power}/${c.toughness}` : "";
      const token = c.isToken ? " [token]" : "";
      const sub = c.subtypes.length > 0 ? ` — ${c.subtypes.join(" ")}` : "";
      const ids = c.instanceIds.length > 1 ? ` (${c.instanceIds.length} instances)` : "";
      console.log(`  ${c.name}${pt}${sub}${token}${ids}`);
    }
    console.log("");
  }

  const basics = cards.filter((c) => BASICS.has(c.name));
  if (basics.length > 0) {
    const summary = basics.map((c) => `${c.name} x${c.instanceIds.length}`).join(", ");
    console.log(`Lands: ${summary}`);
  }
}

async function gameNotes(args: string[]) {
  const gameRef = args.find((a) => !a.startsWith("-"));
  const catalog = loadCatalog();
  let entry: CatalogEntry | undefined;
  if (gameRef) {
    entry = catalog.games.find((g) => g.file.includes(gameRef));
  } else {
    entry = catalog.games[catalog.games.length - 1];
  }

  if (!entry) {
    console.error("Game not found. Run: scry save");
    process.exit(1);
  }

  const meta = loadMeta(entry.file);
  if (meta.notes.length === 0) {
    console.log(`No notes for ${entry.file.replace(".log", "")}`);
    return;
  }

  console.log(`Notes (${entry.file.replace(".log", "")}):\n`);
  for (const note of meta.notes) {
    const anchor = note.gsId != null ? `T${note.turn ?? "?"} gs=${note.gsId} ${note.phase ?? ""}` : "";
    console.log(`  ${anchor.padEnd(30)}  "${note.text}"`);
    console.log(`  ${"".padEnd(30)}  ${note.ts}`);
    console.log("");
  }
}
