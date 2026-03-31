// src/commands/wait.ts
// Poll until a condition is met.

import { waitFor, parseCondition } from "../wait";

export async function waitCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h") || args.length === 0) {
    console.log("arena wait — poll until condition met\n");
    console.log("Usage:");
    console.log("  arena wait scene=Home");
    console.log("  arena wait scene=InGame --timeout 30000");
    console.log("\nConditions:");
    console.log("  scene=<name>    Wait for Player.log scene change");
    console.log("\nFlags:");
    console.log("  --timeout <ms>  Timeout (default: 10000)");
    return;
  }

  const cond = args[0];
  const timeoutIdx = args.indexOf("--timeout");
  const timeout = timeoutIdx !== -1 ? parseInt(args[timeoutIdx + 1]) : 10_000;

  const predicate = parseCondition(cond);
  const elapsed = await waitFor(predicate, { timeout, label: cond });
  console.log(`${cond} (${elapsed}ms)`);
}
