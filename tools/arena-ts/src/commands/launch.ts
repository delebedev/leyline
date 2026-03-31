// src/commands/launch.ts
// Launch MTGA client with controlled window size.

export async function launchCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena launch — launch MTGA client\n");
    console.log("Usage:");
    console.log("  arena launch                     # 1920x1080 windowed (default)");
    console.log("  arena launch --width 2560 --height 1440");
    console.log("  arena launch --fullscreen");
    console.log("  arena launch --kill              # quit existing instance first");
    return;
  }

  let width = 1920;
  let height = 1080;
  let fullscreen = false;
  let kill = false;

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case "--width":      width = parseInt(args[++i]); break;
      case "--height":     height = parseInt(args[++i]); break;
      case "--fullscreen": fullscreen = true; break;
      case "--kill":       kill = true; break;
    }
  }

  // Auto-detect: fullscreen on native 1080p (non-retina) where windowed overflows.
  // MTGA at 1920x1080 + title bar = 1920x1108 — 59px off-screen on 1080p.
  if (!fullscreen) {
    const result = Bun.spawnSync({ cmd: ["system_profiler", "SPDisplaysDataType"] });
    const displayInfo = result.stdout.toString();
    const native1080p =
      displayInfo.includes("1920 x 1080") &&
      !displayInfo.includes("Retina") &&
      !displayInfo.includes("UI Looks like");
    if (native1080p) fullscreen = true;
  }

  if (kill) {
    console.log("quitting existing MTGA...");
    Bun.spawnSync({ cmd: ["osascript", "-e", 'tell application "MTGA" to quit'] });
    await Bun.sleep(2000);
  }

  const fsFlag = fullscreen ? "1" : "0";
  const result = Bun.spawnSync({
    cmd: [
      "open", "-a", "MTGA", "--args",
      "-screen-width", String(width),
      "-screen-height", String(height),
      "-screen-fullscreen", fsFlag,
    ],
  });

  if (result.exitCode !== 0) {
    const stderr = result.stderr.toString().trim();
    throw new Error(`Failed to launch MTGA: ${stderr}`);
  }

  const mode = fullscreen ? "fullscreen" : "windowed";
  console.log(`MTGA launched (${width}x${height} ${mode})`);
}
