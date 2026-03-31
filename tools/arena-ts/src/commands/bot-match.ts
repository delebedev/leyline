// src/commands/bot-match.ts
// Start a bot match.
// Home → play blade → OCR "Bot Match" → select deck → Play.

import { click, activateMtga, captureMtga } from "../input";
import { getWindowBounds, scaleToScreen, REFERENCE_WIDTH } from "../window";
import { currentScene } from "../scene";
import { compileSwift } from "../compile";

export async function botMatchCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena bot-match — start a bot match\n");
    console.log("Usage:");
    console.log("  arena bot-match                  # first deck");
    console.log("  arena bot-match --deck 'Mono B'  # deck by name (OCR)");
    return;
  }

  const deckIdx = args.indexOf("--deck");
  const deckName = deckIdx !== -1 ? args[deckIdx + 1] : null;

  const bounds = await getWindowBounds();
  if (!bounds) { console.error("MTGA window not found"); process.exitCode = 1; return; }

  async function clickRef(x: number, y: number) {
    const [sx, sy] = scaleToScreen(x, y, bounds!);
    await click(sx, sy);
  }

  const scene = await currentScene();
  if (scene?.inGame) { console.error("already in a game"); process.exitCode = 1; return; }

  await activateMtga(true);

  // 1. Open play blade
  console.log("play blade...");
  await clickRef(866, 533);
  await Bun.sleep(2500);

  // 2. OCR find "Bot Match", click it
  console.log("finding Bot Match...");
  const botPos = await ocrFind("Bot Match");
  if (!botPos) { console.error("'Bot Match' not found"); process.exitCode = 1; return; }
  await clickRef(botPos[0], botPos[1]);
  await Bun.sleep(2500);

  // 3. Select deck
  if (deckName) {
    const deckPos = await ocrFind(deckName);
    if (deckPos) {
      console.log(`selecting "${deckName}"...`);
      await clickRef(deckPos[0], deckPos[1] - 40);
      await Bun.sleep(1000);
    } else {
      console.error(`deck "${deckName}" not found — using default`);
    }
  } else {
    // Click first deck in grid
    await clickRef(230, 300);
    await Bun.sleep(1000);
  }

  // 4. Verify deck selected — OCR for "Edit Deck"
  const editPos = await ocrFind("Edit Deck");
  if (!editPos) {
    console.error("no deck selected (Edit Deck not visible)");
    process.exitCode = 1;
    return;
  }

  // 5. Click Play
  console.log("starting match...");
  await clickRef(866, 534);

  // 6. Wait for InGame
  for (let i = 0; i < 20; i++) {
    await Bun.sleep(1500);
    const s = await currentScene();
    if (s?.inGame) { console.log("in game"); return; }
  }

  console.error("timed out waiting for game");
  process.exitCode = 1;
}

/** OCR capture MTGA, find text, return [cx, cy] in 960px ref. */
async function ocrFind(text: string): Promise<[number, number] | null> {
  const img = "/tmp/arena-ocr-find.png";
  if (!(await captureMtga(img))) return null;

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, img, "--find", text, "--json"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return null;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    if (items.length === 0) return null;
    const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", img], stdout: "pipe" });
    const wMatch = sipsInfo.stdout.toString().match(/pixelWidth:\s*(\d+)/);
    const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
    const scale = REFERENCE_WIDTH / imgW;
    // Pick bottommost match (the actual button, not header text)
    const sorted = items.sort((a: any, b: any) => b.cy - a.cy);
    return [Math.round(sorted[0].cx * scale), Math.round(sorted[0].cy * scale)];
  } catch { return null; }
}
