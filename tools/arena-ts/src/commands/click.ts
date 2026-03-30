// src/commands/click.ts
// Click by coordinates or named landmark. All in 960px reference space.

import { click as rawClick } from "../input";
import { getWindowBounds, scaleToScreen } from "../window";
import { resolve, landmarks } from "../landmarks";
import { waitFor, parseCondition } from "../wait";

export async function clickCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h") || args.length === 0) {
    console.log("arena click — click by coordinates or named landmark\n");
    console.log("Usage:");
    console.log("  arena click 480,300              # coordinates (960px reference)");
    console.log("  arena click home-cta             # named landmark");
    console.log("  arena click home-cta --wait scene=InGame --timeout 30000");
    console.log("\nLandmarks:");
    for (const [name, [x, y]] of Object.entries(landmarks)) {
      console.log(`  ${name.padEnd(20)} ${x},${y}`);
    }
    console.log("\nFlags:");
    console.log("  --wait <condition>   Wait after click (scene=<name>)");
    console.log("  --timeout <ms>       Wait timeout (default: 10000)");
    console.log("  --dry-run            Show resolved coords, don't click");
    return;
  }

  // Parse target (first non-flag arg)
  const target = args[0];
  const point = resolve(target);
  if (!point) {
    console.error(`Unknown target: "${target}". Use coordinates (480,300) or a landmark name.`);
    console.error(`Run 'arena click --help' to see available landmarks.`);
    process.exitCode = 1;
    return;
  }

  // Parse flags
  const dryRun = args.includes("--dry-run");
  const waitIdx = args.indexOf("--wait");
  const waitCond = waitIdx !== -1 ? args[waitIdx + 1] : null;
  const timeoutIdx = args.indexOf("--timeout");
  const timeout = timeoutIdx !== -1 ? parseInt(args[timeoutIdx + 1]) : 10_000;

  // Get window bounds
  const bounds = await getWindowBounds();
  if (!bounds) {
    console.error("MTGA window not found");
    process.exitCode = 1;
    return;
  }

  const [screenX, screenY] = scaleToScreen(point[0], point[1], bounds);

  if (dryRun) {
    console.log(`would click ${target} → ref(${point[0]},${point[1]}) → screen(${Math.round(screenX)},${Math.round(screenY)})`);
    return;
  }

  // Click
  await rawClick(screenX, screenY);
  console.log(`clicked ${target} (${point[0]},${point[1]})`);

  // Post-click wait
  if (waitCond) {
    const predicate = parseCondition(waitCond);
    const elapsed = await waitFor(predicate, { timeout, label: waitCond });
    console.log(`  ${waitCond} (${elapsed}ms)`);
  }
}
