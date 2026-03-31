// src/input.ts
// Bun FFI bindings to arena-shim.dylib. Lazy-loads on first call.

import { dlopen, FFIType, ptr } from "bun:ffi";
import { compileC } from "./compile";

let lib: ReturnType<typeof dlopen> | null = null;
let lastActivate = 0;
const ACTIVATE_DEDUP_MS = 2000;

/** Bring MTGA to foreground. Deduped unless force=true. */
export async function activateMtga(force = false): Promise<void> {
  const now = Date.now();
  if (!force && now - lastActivate < ACTIVATE_DEDUP_MS) return;
  Bun.spawnSync({ cmd: ["osascript", "-e", 'tell application "MTGA" to activate'] });
  await Bun.sleep(300); // let window come forward
  lastActivate = Date.now();
}

async function getLib() {
  if (lib) return lib;
  const path = await compileC("arena-shim");
  lib = dlopen(path, {
    shim_check_accessibility: { returns: FFIType.i32, args: [] },
    shim_find_mtga: {
      returns: FFIType.i32,
      args: [FFIType.ptr, FFIType.ptr, FFIType.ptr, FFIType.ptr],
    },
    shim_click: { returns: FFIType.void, args: [FFIType.f64, FFIType.f64] },
    shim_move: { returns: FFIType.void, args: [FFIType.f64, FFIType.f64] },
    shim_mouse_down: { returns: FFIType.void, args: [FFIType.f64, FFIType.f64] },
    shim_mouse_up: { returns: FFIType.void, args: [FFIType.f64, FFIType.f64] },
    shim_right_click: { returns: FFIType.void, args: [FFIType.f64, FFIType.f64] },
    shim_mtga_window_id: { returns: FFIType.i32, args: [] },
  });
  return lib;
}

export async function checkAccessibility(): Promise<boolean> {
  const l = await getLib();
  return l.symbols.shim_check_accessibility() === 1;
}

export interface WindowBounds {
  x: number;
  y: number;
  w: number;
  h: number;
}

export async function findMtgaWindow(): Promise<WindowBounds | null> {
  const l = await getLib();
  const xBuf = new Float64Array(1);
  const yBuf = new Float64Array(1);
  const wBuf = new Float64Array(1);
  const hBuf = new Float64Array(1);
  const found = l.symbols.shim_find_mtga(
    ptr(xBuf), ptr(yBuf), ptr(wBuf), ptr(hBuf),
  );
  if (!found) return null;
  return { x: xBuf[0], y: yBuf[0], w: wBuf[0], h: hBuf[0] };
}

export async function click(x: number, y: number): Promise<void> {
  await activateMtga();
  const l = await getLib();
  l.symbols.shim_click(x, y);
}

export async function move(x: number, y: number): Promise<void> {
  const l = await getLib();
  l.symbols.shim_move(x, y);
}

/** Get MTGA CGWindowID. Returns 0 if not found. */
export async function mtgaWindowId(): Promise<number> {
  const l = await getLib();
  return l.symbols.shim_mtga_window_id();
}

/** Capture MTGA window by window ID — works even behind other windows. */
export async function captureMtga(outPath: string): Promise<boolean> {
  const wid = await mtgaWindowId();
  if (wid === 0) return false;
  const proc = Bun.spawnSync({ cmd: ["screencapture", "-l", String(wid), "-o", "-x", outPath] });
  return proc.exitCode === 0;
}

/** Smoothstep drag from (x1,y1) to (x2,y2). steps=30, totalMs=500. */
export async function drag(
  x1: number, y1: number,
  x2: number, y2: number,
  opts?: { steps?: number; totalMs?: number },
): Promise<void> {
  await activateMtga();
  const l = await getLib();
  const steps = opts?.steps ?? 30;
  const totalMs = opts?.totalMs ?? 500;

  // Pre-move + settle (Unity needs hover to register the card)
  l.symbols.shim_move(x1, y1);
  await Bun.sleep(500);

  // Mouse down + hold
  l.symbols.shim_mouse_down(x1, y1);
  await Bun.sleep(150);

  // Smoothstep interpolation
  for (let i = 1; i <= steps; i++) {
    const linear = i / steps;
    const t = linear * linear * (3 - 2 * linear); // smoothstep
    const cx = x1 + (x2 - x1) * t;
    const cy = y1 + (y2 - y1) * t;
    l.symbols.shim_move(cx, cy);
    // Variable timing: slower at start/end
    const sleepFactor = 0.6 + 0.8 * (1 - Math.abs(linear - 0.5) * 2);
    await Bun.sleep((totalMs / steps) * sleepFactor);
  }

  // Hold at destination + release
  await Bun.sleep(120);
  l.symbols.shim_mouse_up(x2, y2);
}
