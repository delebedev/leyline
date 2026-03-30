// src/commands/scene.ts
// Show current Arena scene from Player.log.

import { currentScene } from "../scene";

export async function sceneCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena scene — show current Arena scene\n");
    console.log("Usage: arena scene [--json]");
    return;
  }

  const json = args.includes("--json");
  const scene = await currentScene();

  if (!scene) {
    if (json) {
      console.log(JSON.stringify({ scene: null }));
    } else {
      console.error("no scene detected in Player.log");
      process.exitCode = 1;
    }
    return;
  }

  if (json) {
    console.log(JSON.stringify(scene));
  } else {
    console.log(scene.scene);
  }
}
