// src/match-flow.ts
// Shared flow: open play blade → Find Match → format tab → format entry → deck → Play.
// Used by bot-match, brawl, and future queue commands.

import { click, activateMtga } from "./input";
import { getWindowBounds, scaleToScreen } from "./window";
import { currentScene } from "./scene";
import { ocrFindText } from "./ocr";
import { landmarks } from "./landmarks";

export interface MatchFlowOpts {
  formatTab: "fmt-play" | "fmt-ranked" | "fmt-brawl";
  formatEntry: string;     // OCR text to find in format list, e.g. "Bot Match", "Standard Brawl"
  deckArg?: string | null; // deck grid index or OCR name
}

/** Start a match. Returns true if InGame reached. */
export async function startMatch(opts: MatchFlowOpts): Promise<boolean> {
  const bounds = await getWindowBounds();
  if (!bounds) { console.error("MTGA window not found"); return false; }

  async function clickRef(x: number, y: number) {
    const [sx, sy] = scaleToScreen(x, y, bounds!);
    await click(sx, sy);
  }

  const scene = await currentScene();
  if (scene?.inGame) { console.error("already in a game"); return false; }

  await activateMtga(true);

  // 1. Open play blade — use OCR to find Play button (bottommost "Play" on home)
  console.log("play blade...");
  const playBtn = await ocrFindText("Play");
  if (playBtn) {
    await clickRef(playBtn[0], playBtn[1]);
  } else {
    await clickRef(866, 519); // home-cta fallback
  }
  await Bun.sleep(2000);

  // 2. Find Match tab — click it if we landed on Events
  console.log("find match...");
  const fm = await ocrFindText("Find Match");
  if (fm) {
    await clickRef(fm[0], fm[1]);
    await Bun.sleep(1000);
  }
  // If "Find Match" not found, we might already be on it (Bot Match card click goes straight there)

  // 3. Format tab
  const tabCoords = landmarks[opts.formatTab];
  await clickRef(tabCoords[0], tabCoords[1]);
  await Bun.sleep(1000);

  // 4. Verify deck selector
  if (!(await ocrFindText("My Decks"))) {
    console.error("deck selector not visible");
    return false;
  }

  // 5. Format entry
  console.log(`selecting ${opts.formatEntry}...`);
  const entry = await ocrFindText(opts.formatEntry);
  if (!entry) { console.error(`'${opts.formatEntry}' not found`); return false; }
  await clickRef(entry[0], entry[1]);
  await Bun.sleep(1000);

  // 6. Select deck
  if (opts.deckArg) {
    const deckNum = parseInt(opts.deckArg);
    if (!isNaN(deckNum) && deckNum >= 1 && deckNum <= 8) {
      const gridX = [230, 376, 523, 668];
      const gridY = [300, 440];
      const row = Math.floor((deckNum - 1) / 4);
      const col = (deckNum - 1) % 4;
      console.log(`selecting deck #${deckNum}...`);
      await clickRef(gridX[col], gridY[row]);
    } else {
      const dp = await ocrFindText(opts.deckArg);
      if (dp) {
        console.log(`selecting "${opts.deckArg}"...`);
        await clickRef(dp[0], dp[1] - 40);
      } else {
        console.error(`deck "${opts.deckArg}" not found — using first`);
        await clickRef(230, 300);
      }
    }
  } else {
    await clickRef(230, 300);
  }
  await Bun.sleep(1000);

  // 7. Verify deck selected
  if (!(await ocrFindText("Edit Deck"))) {
    console.error("no deck selected");
    return false;
  }

  // 8. Play
  console.log("starting match...");
  await clickRef(866, 534); // bot-play

  // 9. Wait for InGame
  for (let i = 0; i < 20; i++) {
    await Bun.sleep(1000);
    const s = await currentScene();
    if (s?.inGame) { console.log("in game"); return true; }
  }

  console.error("timed out waiting for game");
  return false;
}
