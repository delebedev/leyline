// src/commands/brawl.ts

import { startMatch } from "../match-flow";

export async function brawlCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena brawl — start a Standard Brawl match\n");
    console.log("Usage:");
    console.log("  arena brawl                      # first deck");
    console.log("  arena brawl --deck 2             # 2nd deck in grid");
    console.log("  arena brawl --deck 'Esper'       # deck by name (OCR)");
    return;
  }

  const deckIdx = args.indexOf("--deck");
  const deckArg = deckIdx !== -1 ? args[deckIdx + 1] : null;

  const ok = await startMatch({
    formatTab: "fmt-brawl",
    formatEntry: "Standard Brawl",
    deckArg,
  });
  if (!ok) process.exitCode = 1;
}
