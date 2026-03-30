#!/usr/bin/env bun

import { clickCommand } from "./src/commands/click";
import { preflightCommand } from "./src/commands/preflight";
import { sceneCommand } from "./src/commands/scene";
import { waitCommand } from "./src/commands/wait";
import { logCommand } from "./src/telemetry";

const commands: Record<string, { description: string; run: (args: string[]) => Promise<void> }> = {
  click:      { description: "Click coordinates or landmark",  run: clickCommand },
  preflight:  { description: "System readiness check",         run: preflightCommand },
  scene:      { description: "Current Arena scene",            run: sceneCommand },
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
