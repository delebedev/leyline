// src/ocr.ts
// Shared OCR utilities. Captures MTGA by window ID, runs Vision OCR.

import { captureMtga } from "./input";
import { compileSwift } from "./compile";
import { REFERENCE_WIDTH } from "./window";

export interface OcrDetection {
  text: string;
  cx: number;  // 960px ref
  cy: number;  // 960px ref
  confidence: number;
}

/** OCR the MTGA window. Returns detections in 960px ref space. */
export async function ocrWindow(): Promise<OcrDetection[]> {
  const img = "/tmp/arena-ocr.png";
  if (!(await captureMtga(img))) return [];

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, img, "--json", "--min-confidence", "0.3"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return [];

  const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", img], stdout: "pipe" });
  const wMatch = sipsInfo.stdout.toString().match(/pixelWidth:\s*(\d+)/);
  const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
  const scale = REFERENCE_WIDTH / imgW;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    return items.map((d: any) => ({
      text: d.text,
      cx: Math.round(d.cx * scale),
      cy: Math.round(d.cy * scale),
      confidence: d.confidence,
    }));
  } catch { return []; }
}

/** Find text on screen. Returns [cx, cy] in 960px ref (bottommost match). Null if not found. */
export async function ocrFindText(text: string): Promise<[number, number] | null> {
  const img = "/tmp/arena-ocr-find.png";
  if (!(await captureMtga(img))) return null;

  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, img, "--find", text, "--json"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return null;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    if (items.length === 0) return null;
    const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", img], stdout: "pipe" });
    const wMatch = sipsInfo.stdout.toString().match(/pixelWidth:\s*(\d+)/);
    const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
    const scale = REFERENCE_WIDTH / imgW;
    // Bottommost match
    const sorted = items.sort((a: any, b: any) => b.cy - a.cy);
    return [Math.round(sorted[0].cx * scale), Math.round(sorted[0].cy * scale)];
  } catch { return null; }
}
