// src/ocr.ts
// Shared OCR utilities. Captures MTGA by window ID, runs Vision OCR.

import { captureMtga } from "./input";
import { compileSwift } from "./compile";
import { captureToRef } from "./window";

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

  const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", "-g", "pixelHeight", img], stdout: "pipe" });
  const sipsOut = sipsInfo.stdout.toString();
  const wMatch = sipsOut.match(/pixelWidth:\s*(\d+)/);
  const hMatch = sipsOut.match(/pixelHeight:\s*(\d+)/);
  const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
  const imgH = hMatch ? parseInt(hMatch[1]) : 1080;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    return items.map((d: any) => {
      const [cx, cy] = captureToRef(d.cx, d.cy, imgW, imgH);
      return { text: d.text, cx, cy, confidence: d.confidence };
    });
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
    const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", "-g", "pixelHeight", img], stdout: "pipe" });
    const sipsOut = sipsInfo.stdout.toString();
    const wMatch = sipsOut.match(/pixelWidth:\s*(\d+)/);
    const hMatch = sipsOut.match(/pixelHeight:\s*(\d+)/);
    const imgW = wMatch ? parseInt(wMatch[1]) : 1920;
    const imgH = hMatch ? parseInt(hMatch[1]) : 1080;
    // Bottommost match
    const sorted = items.sort((a: any, b: any) => b.cy - a.cy);
    return captureToRef(sorted[0].cx, sorted[0].cy, imgW, imgH);
  } catch { return null; }
}
