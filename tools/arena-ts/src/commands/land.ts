// src/commands/land.ts
// Play a land from hand. Uses scry for playable actions, OCR for card position.

import { liveState } from "../gamestate";
import { findHandCard, estimateHandPosition } from "../hand";
import { drag } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";

const BATTLEFIELD_DROP: [number, number] = [480, 300];

export async function landCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena land — play a land from hand\n");
    console.log("Usage: arena land [card-name]");
    console.log("  No args: plays first available land");
    console.log("  With name: plays the named land");
    return;
  }

  const state = await liveState();
  if (!state) {
    console.error("no active game");
    process.exitCode = 1;
    return;
  }

  if (state.lands.length === 0) {
    console.error("no playable land in hand");
    process.exitCode = 1;
    return;
  }

  // Pick land — by name if given, otherwise first available
  const targetName = args.filter(a => !a.startsWith("--")).join(" ").toLowerCase();
  let land = state.lands[0];
  if (targetName) {
    const match = state.lands.find(l => l.name.toLowerCase().includes(targetName));
    if (!match) {
      console.error(`no playable land matching "${targetName}". Available: ${state.lands.map(l => l.name).join(", ")}`);
      process.exitCode = 1;
      return;
    }
    land = match;
  }

  // Find card position via OCR
  const knownNames = state.hand.map(c => c.name);
  const pos = await findHandCard(land.name, knownNames);

  let fromX: number, fromY: number;
  if (pos) {
    fromX = pos.cx;
    fromY = pos.cy;
    console.log(`playing ${land.name} (iid=${land.instanceId}) from OCR (${fromX},${fromY}) score=${pos.score.toFixed(2)}`);
  } else {
    // Fallback: estimate from hand index
    const handCard = state.hand.find(c => c.instanceId === land.instanceId);
    const idx = handCard?.index ?? 0;
    [fromX, fromY] = estimateHandPosition(idx, state.handCount);
    console.log(`playing ${land.name} (iid=${land.instanceId}) from estimate (${fromX},${fromY}) [OCR failed]`);
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const [sx1, sy1] = scaleToScreen(fromX, fromY, bounds);
  const [sx2, sy2] = scaleToScreen(BATTLEFIELD_DROP[0], BATTLEFIELD_DROP[1], bounds);

  await drag(sx1, sy1, sx2, sy2);

  // Verify
  await Bun.sleep(2000);
  const after = await liveState();
  if (after) {
    const stillInHand = after.hand.some(c => c.instanceId === land.instanceId);
    if (stillInHand) {
      console.error(`drag may not have landed — ${land.name} still in hand`);
      process.exitCode = 1;
    } else {
      console.log(`played ${land.name} (hand: ${after.handCount} cards)`);
    }
  }
}
