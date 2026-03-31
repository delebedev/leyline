// src/commands/keep.ts
// Keep hand during mulligan. Handles the bottom-card return drag if needed.

import { click, drag } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { ocrWindow } from "../ocr";

// Mulligan button positions (960px ref space, from OCR measurements)
const KEEP_BTN = [567, 468] as const;

// Return card drag: rightmost card to Library zone
const LIBRARY_ZONE = [120, 250] as const;
const DONE_BTN = [480, 467] as const;

export async function keepCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena keep — keep hand during mulligan\n");
    console.log("Usage: arena keep");
    console.log("Clicks Keep, then handles bottom-card return by dragging rightmost cards.");
    return;
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  // Check we're on mulligan
  const items = await ocrWindow();
  const hasKeep = items.some(d => /\bKeep\b/i.test(d.text));
  const hasMulligan = items.some(d => /\bMulligan\b/i.test(d.text));
  if (!hasKeep || !hasMulligan) {
    console.error("not on mulligan screen");
    process.exitCode = 1;
    return;
  }

  // Click Keep
  const [kx, ky] = scaleToScreen(KEEP_BTN[0], KEEP_BTN[1], bounds);
  await click(kx, ky);
  console.log("clicked Keep");

  // Wait and check for Return screen
  await Bun.sleep(3000);
  const retItems = await ocrWindow();
  const returnItem = retItems.find(d => /Return \d+ Card/i.test(d.text));
  if (!returnItem) {
    console.log("no cards to return — ready to play");
    return;
  }

  const match = returnItem.text.match(/Return (\d+)/);
  const returnCount = match ? parseInt(match[1]) : 1;
  console.log(`returning ${returnCount} card(s) to library`);

  // Drag rightmost visible cards to Library zone
  for (let i = 0; i < returnCount; i++) {
    const cardX = 700 - i * 100;
    const [sx1, sy1] = scaleToScreen(cardX, 350, bounds);
    const [sx2, sy2] = scaleToScreen(LIBRARY_ZONE[0], LIBRARY_ZONE[1], bounds);
    await drag(sx1, sy1, sx2, sy2);
    await Bun.sleep(1500);
  }

  // Click Done
  await Bun.sleep(1000);
  const [dx, dy] = scaleToScreen(DONE_BTN[0], DONE_BTN[1], bounds);
  await click(dx, dy);
  console.log("clicked Done — ready to play");
}
