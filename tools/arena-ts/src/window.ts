// src/window.ts
// MTGA window detection and coordinate scaling.

import { findMtgaWindow, type WindowBounds } from "./input";

export const REFERENCE_WIDTH = 960;

export async function getWindowBounds(): Promise<WindowBounds | null> {
  return findMtgaWindow();
}

/**
 * Detect the title bar height in the window capture image (pixels).
 *
 * Window captures (`screencapture -l`) include the macOS title bar.
 * The game content is 16:9 (1920×1080 at native res). The title bar
 * is the leftover height: captureH - (captureW × 9/16).
 */
export function captureTitleBarPx(captureW: number, captureH: number): number {
  const contentH = Math.round(captureW * 9 / 16);
  return Math.max(0, captureH - contentH);
}

/**
 * Scale coordinates from 960px reference space to screen space.
 *
 * Ref coords are game-content-relative: (0,0) = top-left of the game
 * area, not the window frame. This makes landmarks portable across
 * Retina and non-Retina displays (title bar is different pixel counts
 * but game content is always 16:9).
 *
 * CGWindowBounds includes the title bar. On Retina (2x) bounds are
 * in points; on non-Retina (1x) they're pixels. CGEvent uses the
 * same coordinate system as bounds.
 */
export function scaleToScreen(
  refX: number, refY: number,
  bounds: WindowBounds,
): [number, number] {
  const scale = bounds.w / REFERENCE_WIDTH;
  // Title bar in screen coords (points or pixels, matching bounds)
  const contentH = Math.round(bounds.w * 9 / 16);
  const titleBar = Math.max(0, bounds.h - contentH);
  return [bounds.x + refX * scale, bounds.y + titleBar + refY * scale];
}

/**
 * Convert window-capture image pixel coords to game-content ref space.
 * Strips title bar pixels before scaling to 960px ref.
 */
export function captureToRef(
  imgX: number, imgY: number,
  imgW: number, imgH: number,
): [number, number] {
  const titleBar = captureTitleBarPx(imgW, imgH);
  const scale = REFERENCE_WIDTH / imgW;
  return [Math.round(imgX * scale), Math.round((imgY - titleBar) * scale)];
}
