// src/commands/bot-match.ts
// Start a bot match. Navigate from Home → play blade → deck selector → play.
// Handles deck selection by index or OCR name match.

import { click, activateMtga, captureMtga } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { currentScene } from "../scene";
import { compileSwift } from "../compile";
import { REFERENCE_WIDTH } from "../window";

// Deck grid positions in the bot match selector (960px ref)
// Row 1: y~300, Row 2: y~440
const DECK_GRID = [
  [230, 300], [376, 300], [523, 300], [668, 300],   // row 1
  [230, 440], [376, 440], [523, 440], [668, 440],   // row 2
] as const;

export async function botMatchCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena bot-match — start a bot match\n");
    console.log("Usage:");
    console.log("  arena bot-match                  # first deck");
    console.log("  arena bot-match --deck 2         # 2nd deck in grid");
    console.log("  arena bot-match --deck 'Mono B'  # deck by name (OCR)");
    return;
  }

  // Parse --deck flag
  const deckIdx = args.indexOf("--deck");
  const deckArg = deckIdx !== -1 ? args[deckIdx + 1] : null;

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  // Helper: click a 960px ref point
  async function clickRef(x: number, y: number) {
    const [sx, sy] = scaleToScreen(x, y, bounds!);
    await click(sx, sy);
  }

  // Step 1: ensure we're on Home
  const scene = await currentScene();
  if (scene?.inGame) {
    console.error("already in a game — concede first");
    process.exitCode = 1;
    return;
  }

  await activateMtga(true);

  // Step 2: open play blade
  console.log("opening play blade...");
  await clickRef(866, 533);  // home-cta
  await Bun.sleep(2000);

  // Step 3: OCR the play blade to find "Bot Match", click its card body
  console.log("finding Bot Match...");
  const botPos = await ocrFindInBlade("Bot Match", bounds);
  if (!botPos) {
    console.error("'Bot Match' not found on play blade");
    process.exitCode = 1;
    return;
  }
  // Click card body — above the text label
  await clickRef(botPos[0], botPos[1] - 80);
  await Bun.sleep(2000);

  // Step 4: select deck
  if (deckArg) {
    const deckNum = parseInt(deckArg);
    if (!isNaN(deckNum) && deckNum >= 1 && deckNum <= DECK_GRID.length) {
      // Select by grid index
      const [dx, dy] = DECK_GRID[deckNum - 1];
      console.log(`selecting deck #${deckNum}...`);
      await clickRef(dx, dy);
      await Bun.sleep(1000);
    } else {
      // Select by name — OCR the deck selector, find matching deck
      const found = await findDeckByName(deckArg, bounds);
      if (found) {
        console.log(`selecting "${deckArg}" at ${found[0]},${found[1]}...`);
        await clickRef(found[0], found[1]);
        await Bun.sleep(1000);
      } else {
        console.error(`deck "${deckArg}" not found — using selected deck`);
      }
    }
  }
  // No --deck: use whatever is already selected (last used deck)

  // Step 5: click Play
  console.log("starting match...");
  await clickRef(866, 534);  // bot-play

  // Step 6: wait for game to load (VS screen ~5-10s)
  console.log("waiting for game...");
  for (let i = 0; i < 20; i++) {
    await Bun.sleep(1500);
    const s = await currentScene();
    if (s?.inGame) {
      console.log("in game");
      return;
    }
  }

  console.error("timed out waiting for game");
  process.exitCode = 1;
}

/** OCR the play blade to find text (e.g. "Bot Match"). Returns [cx, cy] in 960px ref or null. */
async function ocrFindInBlade(
  text: string,
  bounds: { x: number; y: number; w: number; h: number },
): Promise<[number, number] | null> {
  const img = "/tmp/arena-blade-ocr.png";
  if (!(await captureMtga(img))) return null;

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, img, "--json", "--min-confidence", "0.3"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return null;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", img], stdout: "pipe" });
    const wMatch = sipsInfo.stdout.toString().match(/pixelWidth:\s*(\d+)/);
    const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
    const scale = REFERENCE_WIDTH / imgW;

    const needle = text.toLowerCase();
    for (const item of items) {
      if (item.text.toLowerCase().includes(needle)) {
        return [Math.round(item.cx * scale), Math.round(item.cy * scale)];
      }
    }
  } catch {}
  return null;
}

/** OCR the deck selector to find a deck by name. Returns [cx, cy] in 960px ref or null. */
async function findDeckByName(
  name: string,
  bounds: { x: number; y: number; w: number; h: number },
): Promise<[number, number] | null> {
  const img = "/tmp/arena-deck-select.png";
  if (!(await captureMtga(img))) return null;

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, img, "--json", "--min-confidence", "0.3"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return null;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    // Get image width for scaling
    const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", img], stdout: "pipe" });
    const wMatch = sipsInfo.stdout.toString().match(/pixelWidth:\s*(\d+)/);
    const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
    const scale = REFERENCE_WIDTH / imgW;

    const needle = name.toLowerCase();
    for (const item of items) {
      if (item.text.toLowerCase().includes(needle)) {
        const cx = Math.round(item.cx * scale);
        // Click card art above the text label (~70px up)
        const cy = Math.round(item.cy * scale) - 70;
        return [cx, cy];
      }
    }
  } catch {}
  return null;
}
