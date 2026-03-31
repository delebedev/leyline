#!/usr/bin/env bun

import { castCommand } from "./src/commands/cast";
import { clickCommand } from "./src/commands/click";
import { concedeCommand } from "./src/commands/concede";
import { dragCommand } from "./src/commands/drag";
import { handCommand } from "./src/commands/hand";
import { keepCommand } from "./src/commands/keep";
import { landCommand } from "./src/commands/land";
import { passCommand } from "./src/commands/pass";
import { preflightCommand } from "./src/commands/preflight";
import { sceneCommand } from "./src/commands/scene";
import { turnCommand } from "./src/commands/turn";
import { waitCommand } from "./src/commands/wait";
import { logCommand } from "./src/telemetry";

const commands: Record<string, { description: string; run: (args: string[]) => Promise<void> }> = {
  cast:       { description: "Cast a spell from hand",         run: castCommand },
  click:      { description: "Click coordinates or landmark",  run: clickCommand },
  concede:    { description: "Concede current match",          run: concedeCommand },
  drag:       { description: "Drag between two points",        run: dragCommand },
  hand:       { description: "Hand cards with positions",      run: handCommand },
  keep:       { description: "Keep hand during mulligan",      run: keepCommand },
  land:       { description: "Play a land from hand",          run: landCommand },
  pass:       { description: "Pass priority (action button)",  run: passCommand },
  preflight:  { description: "System readiness check",         run: preflightCommand },
  scene:      { description: "Current Arena scene",            run: sceneCommand },
  turn:       { description: "Current turn state",             run: turnCommand },
  wait:       { description: "Wait for condition",             run: waitCommand },
};

async function main() {
  const args = process.argv.slice(2);
  const command = args[0];

  if (!command || command === "--help" || command === "-h") {
    console.log("arena — MTGA automation tool\n");
    console.log("Usage: arena <command> [args] [--flags]\n");
    console.log("Commands:");
    for (const [name, cmd] of Object.entries(commands)) {
      console.log(`  ${name.padEnd(16)} ${cmd.description}`);
    }
    console.log("\nWorkflow:");
    console.log("  preflight → start match → keep → turn/hand → land/cast/pass → concede");
    console.log("  All coordinates in 960px reference space (auto-scaled to window).");
    console.log("\nCommon flags:");
    console.log("  --json       Raw JSON output (where supported)");
    console.log("  --help       Help for any command (e.g. arena hand --help)");
    process.exit(0);
  }

  const cmd = commands[command];
  if (!cmd) {
    console.error(`Unknown command: ${command}\nRun 'arena --help' for usage.`);
    process.exit(1);
  }

  const start = performance.now();
  let ok = true;
  let err: string | undefined;
  try {
    await cmd.run(args.slice(1));
  } catch (e: unknown) {
    ok = false;
    err = e instanceof Error ? e.message : String(e);
    console.error(`Error: ${err}`);
    process.exitCode = 1;
  } finally {
    logCommand({
      ts: new Date().toISOString(),
      cmd: command,
      args: args.slice(1),
      ms: Math.round(performance.now() - start),
      ok,
      err,
    });
  }
}

main();
