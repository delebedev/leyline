// src/commands/concede.ts
// Concede current match. Escape → Concede → dismiss result screens → Home.

import { click } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { currentScene } from "../scene";
import { waitFor } from "../wait";

const GAME_CONCEDE = [480, 344] as const;
const DISMISS = [480, 284] as const;

export async function concedeCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena concede — concede current match\n");
    console.log("Usage: arena concede");
    return;
  }

  const scene = await currentScene();
  if (!scene?.inGame) {
    console.log("not in a game — nothing to concede");
    return;
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  // Press Escape to open options
  Bun.spawnSync({ cmd: ["osascript", "-e", 'tell application "System Events" to key code 53'] });
  await Bun.sleep(2000);

  // Click Concede
  const [cx, cy] = scaleToScreen(GAME_CONCEDE[0], GAME_CONCEDE[1], bounds);
  await click(cx, cy);
  console.log("conceded");

  // Dismiss result screens — click center repeatedly until not in game
  await Bun.sleep(3000);
  const [dx, dy] = scaleToScreen(DISMISS[0], DISMISS[1], bounds);

  for (let i = 0; i < 5; i++) {
    await click(dx, dy);
    await Bun.sleep(2000);
    const s = await currentScene();
    if (s && !s.inGame) {
      console.log(`back to ${s.scene}`);
      return;
    }
  }

  // Final check
  const final = await currentScene();
  console.log(final?.inGame ? "still in game — may need manual dismissal" : `back to ${final?.scene ?? "unknown"}`);
}
