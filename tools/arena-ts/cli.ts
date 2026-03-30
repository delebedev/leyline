#!/usr/bin/env bun

import { preflightCommand } from "./src/commands/preflight";

const commands: Record<string, { description: string; run: (args: string[]) => Promise<void> }> = {
  preflight: { description: "System readiness check", run: preflightCommand },
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

  await cmd.run(args.slice(1));
}

main();
