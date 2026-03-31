// src/commands/hand.ts
// Show hand cards with OCR-detected positions.

import { liveState } from "../gamestate";
import { findAllHandCards, estimateHandPosition } from "../hand";

export async function handCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena hand — show hand cards with visual positions\n");
    console.log("Usage: arena hand [--json]");
    return;
  }

  const json = args.includes("--json");

  const state = await liveState();
  if (!state) {
    console.error("no active game");
    process.exitCode = 1;
    return;
  }

  if (state.hand.length === 0) {
    if (json) console.log(JSON.stringify([]));
    else console.log("hand is empty");
    return;
  }

  // OCR once, get all positions
  const knownNames = state.hand.map(c => c.name);
  const ocrMap = await findAllHandCards(knownNames);

  // Assign positions to each card
  const results: { name: string; iid: number; cx: number; cy: number; method: string; score: number }[] = [];
  const usedPositions = new Map<string, number>(); // track which copy of a name we're on

  for (const card of state.hand) {
    const copyIdx = usedPositions.get(card.name) ?? 0;
    usedPositions.set(card.name, copyIdx + 1);

    const positions = ocrMap.get(card.name) ?? [];
    if (copyIdx < positions.length) {
      const pos = positions[copyIdx];
      results.push({ name: card.name, iid: card.instanceId, cx: pos.cx, cy: pos.cy, method: "ocr", score: pos.score });
    } else {
      const [cx, cy] = estimateHandPosition(card.index, state.handCount);
      results.push({ name: card.name, iid: card.instanceId, cx, cy, method: "est", score: 0 });
    }
  }

  // Sort by cx for visual left-to-right order
  results.sort((a, b) => a.cx - b.cx);

  // Assign visual order (1-based, left to right)
  const ordered = results.map((r, i) => ({ ...r, order: i + 1 }));

  if (json) {
    console.log(JSON.stringify(ordered, null, 2));
    return;
  }

  console.log(`Hand (${state.handCount}):`);
  for (const r of ordered) {
    const method = r.method === "ocr" ? `ocr ${r.score.toFixed(2)}` : "est";
    console.log(`  ${r.order}. ${r.name.padEnd(28)} ${r.cx},${r.cy}  [${method}]`);
  }
}
