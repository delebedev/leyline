// src/wait.ts
// Poll-based waitFor with timeout. No sleeps — always poll state.

import { currentScene } from "./scene";

export class TimeoutError extends Error {
  constructor(label: string, timeoutMs: number) {
    super(`timeout after ${timeoutMs}ms: ${label}`);
    this.name = "TimeoutError";
  }
}

/** Poll predicate until true or timeout. */
export async function waitFor(
  predicate: () => Promise<boolean>,
  opts: { timeout?: number; interval?: number; label?: string } = {},
): Promise<number> {
  const timeout = opts.timeout ?? 10_000;
  const interval = opts.interval ?? 500;
  const label = opts.label ?? "condition";
  const start = Date.now();

  while (Date.now() - start < timeout) {
    if (await predicate()) return Date.now() - start;
    await Bun.sleep(interval);
  }

  throw new TimeoutError(label, timeout);
}

/** Parse a wait condition string like "scene=Home". */
export function parseCondition(cond: string): () => Promise<boolean> {
  const sceneMatch = cond.match(/^scene=(.+)$/);
  if (sceneMatch) {
    const target = sceneMatch[1];
    return async () => {
      const s = await currentScene();
      return s?.scene === target;
    };
  }

  throw new Error(`Unknown wait condition: ${cond}. Supported: scene=<name>`);
}
