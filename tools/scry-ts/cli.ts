#!/usr/bin/env bun

import { abilityCommand } from "./src/commands/ability";
import { boardCommand } from "./src/commands/board";
import { eventsCommand } from "./src/commands/events";
import { gameCommand } from "./src/commands/game";
import { gsmCommand } from "./src/commands/gsm";
import { lobbyCommand } from "./src/commands/lobby";
import { noteCommand } from "./src/commands/note";
import { saveCommand } from "./src/commands/save";
import { traceCommand } from "./src/commands/trace";
import { usageCommand } from "./src/commands/usage";

const commands: Record<string, { description: string; run: (args: string[]) => Promise<void> }> = {
  ability: { description: "Resolve abilityGrpId to text",    run: abilityCommand },
  board:  { description: "Accumulated board state",          run: boardCommand },
  events: { description: "Summarize Player.log event types", run: eventsCommand },
  game:   { description: "Game summaries and details",       run: gameCommand },
  gsm:    { description: "Query game state messages",        run: gsmCommand },
  lobby:  { description: "Lobby request/response pairs",     run: lobbyCommand },
  note:   { description: "Add a note to a saved game",       run: noteCommand },
  save:   { description: "Save games to durable storage",    run: saveCommand },
  trace:  { description: "Trace a card's journey",           run: traceCommand },
  usage:  { description: "Command usage heatmap",             run: usageCommand },
};

import { appendFileSync, mkdirSync } from "fs";
import { join } from "path";
import { homedir } from "os";

function logUsage(args: string[]) {
  try {
    const dir = join(homedir(), ".scry");
    mkdirSync(dir, { recursive: true });
    const line = `${new Date().toISOString()}  ${args.join(" ")}\n`;
    appendFileSync(join(dir, "usage.log"), line);
  } catch {}
}

async function main() {
  const args = process.argv.slice(2);
  const command = args[0];

  if (!command || command === "--help" || command === "-h") {
    console.log("scry — MTGA Player.log parser and game state tool\n");
    console.log("Usage: scry <command> [args] [--flags]\n");
    console.log("Commands:");
    for (const [name, cmd] of Object.entries(commands)) {
      console.log(`  ${name.padEnd(16)} ${cmd.description}`);
    }
    console.log("\nWorkflow:");
    console.log("  Play games → scry save → games are cataloged in ~/.scry/games/");
    console.log("  Most commands default to last game in Player.log.");
    console.log("  Use --game <ref> to target a saved game (substring match on filename).");
    console.log("\nCommon flags:");
    console.log("  --json       Lossless raw JSON output (where supported)");
    console.log("  --game REF   Target a specific game (e.g. 2026-03-29, or live index)");
    console.log("  --help       Help for any command (e.g. scry gsm --help)");
    process.exit(0);
  }

  const cmd = commands[command];
  if (!cmd) {
    console.error(`Unknown command: ${command}\nRun 'scry --help' for usage.`);
    process.exit(1);
  }

  logUsage(args);
  await cmd.run(args.slice(1));
}

main();
