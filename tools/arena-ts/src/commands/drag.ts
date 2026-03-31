// src/commands/drag.ts
// Drag between two points in 960px reference space.

import { drag as rawDrag } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { resolve } from "../landmarks";

export async function dragCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h") || args.length < 2) {
    console.log("arena drag — drag from one point to another\n");
    console.log("Usage:");
    console.log("  arena drag 300,480 480,250       # coordinates (960px reference)");
    console.log("  arena drag 300,480 center        # mix coords and landmarks");
    console.log("\nSmoothstep interpolation, 30 steps over 500ms.");
    return;
  }

  const from = resolve(args[0]);
  const to = resolve(args[1]);

  if (!from) {
    console.error(`Unknown source: "${args[0]}"`);
    process.exitCode = 1;
    return;
  }
  if (!to) {
    console.error(`Unknown destination: "${args[1]}"`);
    process.exitCode = 1;
    return;
  }

  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const [sx1, sy1] = scaleToScreen(from[0], from[1], bounds);
  const [sx2, sy2] = scaleToScreen(to[0], to[1], bounds);

  await rawDrag(sx1, sy1, sx2, sy2);
  console.log(`dragged (${from[0]},${from[1]}) → (${to[0]},${to[1]})`);
}
