// src/commands/bot-match.ts

import { startMatch } from "../match-flow";

export async function botMatchCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena bot-match — start a bot match\n");
    console.log("Usage:");
    console.log("  arena bot-match                  # first deck");
    console.log("  arena bot-match --deck 2         # 2nd deck in grid");
    console.log("  arena bot-match --deck 'Mono B'  # deck by name (OCR)");
    return;
  }

  const deckIdx = args.indexOf("--deck");
  const deckArg = deckIdx !== -1 ? args[deckIdx + 1] : null;

  const ok = await startMatch({
    formatTab: "fmt-play",
    formatEntry: "Bot Match",
    deckArg,
  });
  if (!ok) process.exitCode = 1;
}
