// src/input.ts
// Bun FFI bindings to arena-shim.dylib. Lazy-loads on first call.

import { dlopen, FFIType, ptr } from "bun:ffi";
import { compileC } from "./compile";

let lib: ReturnType<typeof dlopen> | null = null;

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
  const l = await getLib();
  l.symbols.shim_click(x, y);
}

export async function move(x: number, y: number): Promise<void> {
  const l = await getLib();
  l.symbols.shim_move(x, y);
}
