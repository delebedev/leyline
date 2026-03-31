// src/commands/pass.ts
// Pass priority / click action button.

import { click } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";

const ACTION_BTN = [888, 504] as const;

export async function passCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena pass — pass priority (click action button)\n");
    console.log("Usage: arena pass [--n N]");
    console.log("  --n N   Click N times with 1s delay between (default: 1)");
    return;
  }

  const nIdx = args.indexOf("--n");
  const n = nIdx !== -1 ? parseInt(args[nIdx + 1]) || 1 : 1;

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const [sx, sy] = scaleToScreen(ACTION_BTN[0], ACTION_BTN[1], bounds);

  for (let i = 0; i < n; i++) {
    await click(sx, sy);
    if (i < n - 1) await Bun.sleep(1000);
  }
  console.log(`passed${n > 1 ? ` (${n}x)` : ""}`);
}
