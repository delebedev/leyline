import { loadEvents } from "../log";
import { detectGames, type Game } from "../games";

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
    console.log("  list     List all games in Player.log");
    console.log("  <N>      Show game #N detail");
    console.log("  last     Show last game detail (default)");
    return;
  }

  if (verb === "list") {
    await gameList();
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
