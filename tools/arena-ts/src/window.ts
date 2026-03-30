// src/window.ts
// MTGA window detection and coordinate scaling.

import { findMtgaWindow, type WindowBounds } from "./input";

export const REFERENCE_WIDTH = 960;

export async function getWindowBounds(): Promise<WindowBounds | null> {
  return findMtgaWindow();
}

/** Scale coordinates from 960px reference space to screen space. */
export function scaleToScreen(
  refX: number, refY: number,
  bounds: WindowBounds,
): [number, number] {
  const scale = bounds.w / REFERENCE_WIDTH;
  return [bounds.x + refX * scale, bounds.y + refY * scale];
}
