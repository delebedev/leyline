#!/usr/bin/env bun

import { boardCommand } from "./src/commands/board";
import { eventsCommand } from "./src/commands/events";
import { gameCommand } from "./src/commands/game";
import { gsmCommand } from "./src/commands/gsm";
import { lobbyCommand } from "./src/commands/lobby";
import { noteCommand } from "./src/commands/note";
import { saveCommand } from "./src/commands/save";
import { traceCommand } from "./src/commands/trace";

const commands: Record<string, { description: string; run: (args: string[]) => Promise<void> }> = {
  board:  { description: "Accumulated board state",          run: boardCommand },
  events: { description: "Summarize Player.log event types", run: eventsCommand },
  game:   { description: "Game summaries and details",       run: gameCommand },
  gsm:    { description: "Query game state messages",        run: gsmCommand },
  lobby:  { description: "Lobby request/response pairs",     run: lobbyCommand },
  note:   { description: "Add a note to a saved game",       run: noteCommand },
  save:   { description: "Save games to durable storage",    run: saveCommand },
  trace:  { description: "Trace a card's journey",           run: traceCommand },
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
    console.log("scry — MTGA Player.log parser\n");
    console.log("Usage: scry <command> [flags]\n");
    console.log("Commands:");
    for (const [name, cmd] of Object.entries(commands)) {
      console.log(`  ${name.padEnd(16)} ${cmd.description}`);
    }
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
