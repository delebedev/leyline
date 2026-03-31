// src/commands/concede.ts
// Concede current match and return to lobby.
// Escape → Concede → click through DEFEAT → wait for Home.
// Idempotent — safe to call from any state.

import { click, activateMtga } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { currentScene } from "../scene";

const CONCEDE_BTN = [480, 344] as const;  // Options overlay
const DISMISS = [480, 284] as const;      // click through result/defeat screens

export async function concedeCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena concede — concede current match\n");
    console.log("Usage: arena concede");
    console.log("Idempotent — no-op if not in a game.");
    return;
  }

  const scene = await currentScene();
  if (!scene?.inGame) {
    console.log("not in a game");
    return;
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  // Activate + Escape to open Options overlay
  await activateMtga(true);
  Bun.spawnSync({ cmd: ["osascript", "-e",
    'tell application "System Events" to tell process "MTGA" to key code 53'] });
  await Bun.sleep(1500);

  // Click Concede
  const [cx, cy] = scaleToScreen(CONCEDE_BTN[0], CONCEDE_BTN[1], bounds);
  await click(cx, cy);
  console.log("conceded");

  // Click through DEFEAT + result screens until we leave the game
  // DEFEAT screen takes ~3s to become clickable, then "Waiting for Server" ~5-8s
  await Bun.sleep(3000);
  const [dx, dy] = scaleToScreen(DISMISS[0], DISMISS[1], bounds);

  for (let i = 0; i < 8; i++) {
    const s = await currentScene();
    if (s && !s.inGame) {
      console.log(`${s.scene}`);
      return;
    }
    await click(dx, dy);
    await Bun.sleep(1500);
  }

  const final = await currentScene();
  if (final && !final.inGame) {
    console.log(`${final.scene}`);
  } else {
    console.error("still in game — manual dismissal needed");
    process.exitCode = 1;
  }
}
