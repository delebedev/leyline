import { loadEvents } from "../log";
import { detectGames, type Game } from "../games";
import { loadCatalog, readSavedGame, type CatalogEntry } from "../catalog";
import { loadMeta, saveMeta, buildCardManifest, type CardEntry } from "../meta";
import { parseLog } from "../parser";
import { formatProvenanceSummary, formatSourceBadge, matchesSource, parseSavedSourceFilter } from "../provenance";
import { parseGameFlag, resolveGame } from "../resolve";

function firstPositional(args: string[]): string | null {
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--source" || arg === "--game") {
      i++;
      continue;
    }
    if (arg === "--saved" || arg === "--all") continue;
    if (!arg.startsWith("-")) return arg;
  }
  return null;
}

function selectSavedEntry(catalog: { games: CatalogEntry[] }, gameRef: string | null, args: string[]): CatalogEntry | undefined {
  if (gameRef) {
    return catalog.games.find((g) => g.file.includes(gameRef));
  }

  const allowedSources = parseSavedSourceFilter(args);
  for (let i = catalog.games.length - 1; i >= 0; i--) {
    const entry = catalog.games[i];
    if (matchesSource(loadMeta(entry.file), allowedSources)) {
      return entry;
    }
  }
  return undefined;
}

export function normalizeGameShowArgs(args: string[]): string[] {
  if (parseGameFlag(args)) return args;

  const ref = firstPositional(args);
  if (!ref || ref === "last") return args;

  return ["--game", ref, ...args.slice(1)];
}

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
    console.log("  --source S   Saved-game filter: real|leyline|puzzle|unknown|any");
    console.log("  --all        Alias for --source any");
    return;
  }

  if (verb === "list") {
    const useSaved = args.includes("--saved");
    if (useSaved) {
      await gameListSaved(args.slice(1));
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
    await gameShow(args);
  }
}

async function gameList() {
  const games = await loadAllGames();
  const newestFirst = [...games].reverse();

  console.log(`${"#".padStart(3)}  ${"Start".padEnd(18)}  ${"Rounds".padStart(6)}  ${"GSMs".padStart(4)}  ${"Result".padEnd(6)}  Status`);
  console.log("—".repeat(64));

  for (const game of newestFirst) {
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

async function gameListSaved(args: string[]) {
  const catalog = loadCatalog();
  if (catalog.games.length === 0) {
    console.log("No saved games. Run: scry save");
    return;
  }

  const allowedSources = parseSavedSourceFilter(args);
  const entries = catalog.games.filter((entry) => matchesSource(loadMeta(entry.file), allowedSources));
  if (entries.length === 0) {
    console.log("No saved games matched the source filter.");
    return;
  }
  const newestFirst = [...entries].reverse();

  console.log(`${"File".padEnd(26)}  ${"Source".padEnd(18)}  ${"Rounds".padStart(6)}  ${"GSMs".padStart(4)}  ${"Result".padEnd(6)}  Notes`);
  console.log("—".repeat(90));

  for (const entry of newestFirst) {
    const rounds = Math.ceil(entry.turns / 2);
    const meta = loadMeta(entry.file);
    const noteCount = meta.notes.length > 0 ? `${meta.notes.length} notes` : "";
    const tags = meta.tags.length > 0 ? meta.tags.map((t) => `#${t}`).join(" ") : "";
    const extra = [noteCount, tags].filter(Boolean).join("  ");
    console.log(
      `${entry.file.replace(".log", "").padEnd(26)}  ${formatSourceBadge(meta.provenance).padEnd(18)}  ${String(rounds).padStart(6)}  ${String(entry.gsmCount).padStart(4)}  ${(entry.result ?? "—").padEnd(6)}  ${extra}`
    );
  }
}

async function gameShow(args: string[]) {
  const { game, source, label } = await resolveGame(normalizeGameShowArgs(args));
  const meta = source === "saved" ? loadMeta(`${label}.log`) : null;

  const maxTurn = game.greMessages.reduce((m, g) => Math.max(m, g.turn), 0);
  const rounds = Math.ceil(maxTurn / 2);
  const status = game.active ? " (active)" : "";
  const result = game.result ? `  result=${game.result}` : "";
  const heading = source === "saved" ? `Game ${label}` : `Game #${game.index}`;

  console.log(`${heading}${status}${result}`);
  if (source === "saved") {
    console.log(`  source: saved`);
    if (meta?.provenance) {
      console.log(`  provenance: ${formatProvenanceSummary(meta.provenance)}`);
    }
  }
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

  const gameRef = firstPositional(args);

  // Try catalog
  const catalog = loadCatalog();
  let entry: CatalogEntry | undefined = selectSavedEntry(catalog, gameRef, args);

  if (entry) {
    gameFile = entry.file;
    const meta = loadMeta(gameFile);
    // Return cached cards if available
    if (meta.cards.length > 0) {
      // Need ourSeat — parse to get it
      const lines = readSavedGame(gameFile);
      const games = detectGames([...parseLog(lines)]);
      const ourSeat = games[games.length - 1]?.ourSeat ?? 1;
      printCards(meta.cards, gameFile, ourSeat);
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

  printCards(cards, gameFile ?? "live", game.ourSeat);
}

// TODO: performance — currently reads every saved log file sequentially.
// For large catalogs (100+ games), consider building a search index at save
// time, or parallelizing reads with Bun's worker threads.
async function gameSearch(args: string[]) {
  const term = firstPositional(args);
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

  const allowedSources = parseSavedSourceFilter(args);
  let totalHits = 0;

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, allowedSources)) continue;
    const label = entry.file.replace(".log", "");
    const sourceBadge = formatSourceBadge(meta.provenance);

    // Phase 1: search card names in meta (fast, enriched)
    const matchedCards = meta.cards
      .filter((c) => c.name.toLowerCase().includes(termLower))
      .map((c) => c.name);

    if (matchedCards.length > 0) {
      console.log(`${label}  [${sourceBadge}]  cards: ${matchedCards.join(", ")}`);
      totalHits++;
      continue; // card match is sufficient — skip raw grep
    }

    // Phase 2: search notes
    const matchedNotes = meta.notes.filter((n) => n.text.toLowerCase().includes(termLower));
    if (matchedNotes.length > 0) {
      for (const note of matchedNotes) {
        const anchor = note.gsId != null ? `T${note.turn} gs=${note.gsId}` : "";
        console.log(`${label}  [${sourceBadge}]  note: "${note.text}" ${anchor}`);
      }
      totalHits++;
      continue;
    }

    // Phase 3: search tags
    if (meta.tags.some((t) => t.toLowerCase().includes(termLower))) {
      console.log(`${label}  [${sourceBadge}]  tag: ${meta.tags.filter((t) => t.toLowerCase().includes(termLower)).join(", ")}`);
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
            console.log(`${label}  [${sourceBadge}]  raw: ${snippet}`);
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

function printCards(cards: CardEntry[], label: string, ourSeat: number = 1) {
  console.log(`Cards (${label}):\n`);

  const BASICS = new Set(["Plains", "Island", "Swamp", "Mountain", "Forest"]);

  for (const seat of [1, 2]) {
    const seatCards = cards
      .filter((c) => c.ownerSeat === seat && !BASICS.has(c.name))
      .sort((a, b) => a.name.localeCompare(b.name));

    if (seatCards.length === 0) continue;

    const seatLabel = seat === ourSeat ? `Seat ${seat} (you)` : `Seat ${seat} (opponent)`;
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
  const gameRef = firstPositional(args);
  const catalog = loadCatalog();
  const entry: CatalogEntry | undefined = selectSavedEntry(catalog, gameRef, args);

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
  console.log(`  provenance: ${formatProvenanceSummary(meta.provenance)}`);
  console.log("");
  for (const note of meta.notes) {
    const anchor = note.gsId != null ? `T${note.turn ?? "?"} gs=${note.gsId} ${note.phase ?? ""}` : "";
    console.log(`  ${anchor.padEnd(30)}  "${note.text}"`);
    console.log(`  ${"".padEnd(30)}  ${note.ts}`);
    console.log("");
  }
}
