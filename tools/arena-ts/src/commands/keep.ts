// src/commands/keep.ts
// Keep hand during mulligan. Handles the bottom-card return drag if needed.

import { click, drag } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { compileSwift } from "../compile";

// Mulligan button positions (960px ref space, from OCR measurements)
const KEEP_BTN = [567, 468] as const;
const MULLIGAN_BTN = [393, 467] as const;

// Return card drag: rightmost card to Library zone
const LIBRARY_ZONE = [120, 250] as const;
const DONE_BTN = [480, 467] as const;

/** OCR the MTGA window, return detections as array. */
async function ocrScreen(): Promise<{ text: string; cx: number; cy: number }[]> {
  const { findMtgaWindow } = await import("../input");
  const bounds = await findMtgaWindow();
  if (!bounds) return [];

  const img = "/tmp/arena-keep-ocr.png";
  const cap = Bun.spawnSync({
    cmd: ["screencapture", "-R", `${bounds.x},${bounds.y},${bounds.w},${bounds.h}`, "-x", img],
  });
  if (cap.exitCode !== 0) return [];

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({ cmd: [ocrBin, img, "--json"], stdout: "pipe" });
  if (ocr.exitCode !== 0) return [];

  try {
    const items = JSON.parse(ocr.stdout.toString());
    // OCR returns 2x retina coords — scale to 960px space
    const scale = bounds.w / (bounds.w * 2); // retina: image is 2x window
    return items.map((d: any) => ({
      text: d.text,
      cx: Math.round(d.cx * scale),
      cy: Math.round(d.cy * scale),
    }));
  } catch {
    return [];
  }
}

/** Check if we're on a mulligan screen. */
async function isMulliganScreen(): Promise<boolean> {
  const items = await ocrScreen();
  return items.some(d => /\bKeep\b/i.test(d.text)) &&
         items.some(d => /\bMulligan\b/i.test(d.text));
}

/** Check if we're on a "Return N Card" screen. */
async function isReturnScreen(): Promise<{ returnCount: number } | null> {
  const items = await ocrScreen();
  const returnItem = items.find(d => /Return \d+ Card/i.test(d.text));
  if (!returnItem) return null;
  const match = returnItem.text.match(/Return (\d+)/);
  return match ? { returnCount: parseInt(match[1]) } : null;
}

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
  if (!(await isMulliganScreen())) {
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
  const ret = await isReturnScreen();
  if (!ret) {
    console.log("no cards to return — ready to play");
    return;
  }

  console.log(`returning ${ret.returnCount} card(s) to library`);

  // Drag rightmost visible cards to Library zone
  // Cards are laid out roughly from x=350 to x=800 in 960px space
  // We drag the rightmost ones first
  for (let i = 0; i < ret.returnCount; i++) {
    const cardX = 700 - i * 100; // rough rightmost card positions
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
