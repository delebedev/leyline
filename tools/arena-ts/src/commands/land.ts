// src/commands/land.ts
// Play a land from hand. Uses scry game state to find playable land,
// then drags from estimated hand position to battlefield.

import { liveState } from "../gamestate";
import { drag } from "../input";
import { getWindowBounds, scaleToScreen, REFERENCE_WIDTH } from "../window";

// Hand card X positions in 960px space, indexed by hand size and card index.
// Cards fan in an arc; these are approximate centers.
function handCardX(handSize: number, index: number): number {
  if (handSize <= 0) return 480;
  // Cards spread roughly 350-620 for a 7-card hand, centering at 480
  const totalWidth = Math.min(handSize * 60, 400);
  const startX = 480 - totalWidth / 2;
  const spacing = handSize > 1 ? totalWidth / (handSize - 1) : 0;
  return Math.round(startX + spacing * index);
}

const HAND_Y = 510;          // approximate y for cards in hand
const BATTLEFIELD_Y = 300;   // drop target y

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

  // Find card position in hand
  const handCard = state.hand.find(c => c.instanceId === land.instanceId);
  if (!handCard) {
    console.error(`land ${land.name} not found in hand objects`);
    process.exitCode = 1;
    return;
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const fromX = handCardX(state.handCount, handCard.index);
  const [sx1, sy1] = scaleToScreen(fromX, HAND_Y, bounds);
  const [sx2, sy2] = scaleToScreen(480, BATTLEFIELD_Y, bounds);

  console.log(`playing ${land.name} (iid=${land.instanceId}) from hand[${handCard.index}] (${fromX},${HAND_Y})`);
  await drag(sx1, sy1, sx2, sy2);

  // Verify by re-reading state after a delay
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
