// src/commands/cast.ts
// Cast a spell from hand. OCR-locates the card, drags to battlefield.
// Agent handles mana payment, targeting, modals separately.

import { liveState } from "../gamestate";
import { findHandCard, estimateHandPosition } from "../hand";
import { drag } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";

const BATTLEFIELD_DROP: [number, number] = [480, 300];

export async function castCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h") || args.length === 0) {
    console.log("arena cast — cast a spell from hand\n");
    console.log("Usage: arena cast <card-name> [--to x,y]");
    console.log("  Drags the named card to the battlefield (or --to target).");
    console.log("  Agent handles mana, targeting, modals separately.");
    return;
  }

  // Parse card name and --to flag
  const nameParts: string[] = [];
  let dropTo = BATTLEFIELD_DROP;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--to" && i + 1 < args.length) {
      const match = args[i + 1].match(/^(\d+)\s*,\s*(\d+)$/);
      if (match) dropTo = [parseInt(match[1]), parseInt(match[2])];
      i++;
    } else if (!args[i].startsWith("--")) {
      nameParts.push(args[i]);
    }
  }

  const cardName = nameParts.join(" ");
  if (!cardName) {
    console.error("no card name provided");
    process.exitCode = 1;
    return;
  }

  const state = await liveState();
  if (!state) {
    console.error("no active game");
    process.exitCode = 1;
    return;
  }

  // Find card position via OCR
  const knownNames = state.hand.map(c => c.name);
  // Find the card in hand (for instanceId)
  const handCard = state.hand.find(c =>
    c.name.toLowerCase().includes(cardName.toLowerCase()) ||
    cardName.toLowerCase().includes(c.name.toLowerCase())
  );
  if (!handCard) {
    console.error(`"${cardName}" not found in hand`);
    process.exitCode = 1;
    return;
  }

  const pos = await findHandCard(cardName, knownNames);

  let fromX: number, fromY: number;
  if (pos) {
    fromX = pos.cx;
    fromY = pos.cy;
    console.log(`casting ${handCard.name} from OCR (${fromX},${fromY}) score=${pos.score.toFixed(2)}`);
  } else {
    [fromX, fromY] = estimateHandPosition(handCard.index, state.handCount);
    console.log(`casting ${handCard.name} from estimate (${fromX},${fromY}) [OCR failed]`);
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const [sx1, sy1] = scaleToScreen(fromX, fromY, bounds);
  const [sx2, sy2] = scaleToScreen(dropTo[0], dropTo[1], bounds);
  await drag(sx1, sy1, sx2, sy2);

  // Verify card left hand (by instanceId)
  await Bun.sleep(2000);
  const after = await liveState();
  if (after) {
    const stillInHand = after.hand.some(c => c.instanceId === handCard.instanceId);
    if (stillInHand) {
      console.error(`drag may not have landed — ${handCard.name} still in hand`);
      process.exitCode = 1;
      return;
    }
  }

  console.log(`cast ${handCard.name}`);
}
