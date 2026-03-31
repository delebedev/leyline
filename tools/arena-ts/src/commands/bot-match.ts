// src/commands/bot-match.ts
// Start a bot match.
// Home → Play → Find Match → Bot Match → select deck → Play.

import { click, activateMtga } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { currentScene } from "../scene";
import { ocrFindText } from "../ocr";

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

  const bounds = await getWindowBounds();
  if (!bounds) { console.error("MTGA window not found"); process.exitCode = 1; return; }

  async function clickRef(x: number, y: number) {
    const [sx, sy] = scaleToScreen(x, y, bounds!);
    await click(sx, sy);
  }

  const scene = await currentScene();
  if (scene?.inGame) { console.error("already in a game"); process.exitCode = 1; return; }

  await activateMtga(true);

  // 1. Click Play
  console.log("opening play blade...");
  await clickRef(866, 533);
  await Bun.sleep(2000);

  // 2. Click Find Match
  console.log("find match...");
  const fm = await ocrFindText("Find Match");
  if (!fm) { console.error("'Find Match' not found"); process.exitCode = 1; return; }
  await clickRef(fm[0], fm[1]);
  await Bun.sleep(2000);

  // 3. Click Play format tab (ensures we're on Play, not Ranked/Brawl)
  await clickRef(866, 192); // fmt-play
  await Bun.sleep(1500);

  // 4. Verify — "My Decks" visible means deck selector is showing
  if (!(await ocrFindText("My Decks"))) {
    console.error("deck selector not visible (no 'My Decks')");
    process.exitCode = 1;
    return;
  }

  // 5. Click Bot Match in format list
  console.log("selecting Bot Match...");
  const bm = await ocrFindText("Bot Match");
  if (!bm) { console.error("'Bot Match' not found in format list"); process.exitCode = 1; return; }
  await clickRef(bm[0], bm[1]);
  await Bun.sleep(1500);

  // 5. Select deck
  if (deckArg) {
    const deckNum = parseInt(deckArg);
    if (!isNaN(deckNum) && deckNum >= 1 && deckNum <= 8) {
      const gridX = [230, 376, 523, 668];
      const gridY = [300, 440];
      const row = Math.floor((deckNum - 1) / 4);
      const col = (deckNum - 1) % 4;
      console.log(`selecting deck #${deckNum}...`);
      await clickRef(gridX[col], gridY[row]);
    } else {
      const dp = await ocrFindText(deckArg);
      if (dp) {
        console.log(`selecting "${deckArg}"...`);
        await clickRef(dp[0], dp[1] - 40);
      } else {
        console.error(`deck "${deckArg}" not found — using first`);
        await clickRef(230, 300);
      }
    }
  } else {
    await clickRef(230, 300);
  }
  await Bun.sleep(1000);

  // 6. Verify deck selected — "Edit Deck" visible
  if (!(await ocrFindText("Edit Deck"))) {
    console.error("no deck selected (Edit Deck not visible)");
    process.exitCode = 1;
    return;
  }

  // 7. Click Play
  console.log("starting match...");
  await clickRef(866, 534);

  // 8. Wait for InGame
  for (let i = 0; i < 20; i++) {
    await Bun.sleep(1500);
    const s = await currentScene();
    if (s?.inGame) { console.log("in game"); return; }
  }

  console.error("timed out waiting for game");
  process.exitCode = 1;
}
