// src/hand.ts
// Hand card detection via OCR.
// Captures the hand strip of the MTGA window, upscales for OCR accuracy,
// matches detected text against known card names from scry game state.

import { captureMtga } from "./input";
import { compileSwift } from "./compile";
import { REFERENCE_WIDTH } from "./window";

// Hand layout constants (960px reference space)
const HAND_CROP_BOTTOM = 0.20;   // capture bottom 20% of window
const HAND_CROP_SIDES = 280;     // trim 280px (retina) from each side
const HAND_X_MIN = 120;          // left bound for valid hand card detections
const HAND_X_MAX = 800;          // right bound
const HAND_X_CENTER = 500;       // visual center of hand fan
const HAND_CY_CENTER = 530;      // y center of hand cards
const HAND_ARC_DROP = 35;        // arc curvature (cards at edges sit lower)
const HAND_CX_BASE_SHIFT = 20;   // OCR text sits left of card center
const HAND_CX_NUDGE = 20;        // extra shift for edge cards
const OCR_TARGET_WIDTH = 3840;   // upscale target for better OCR

export interface HandCardPosition {
  name: string;
  cx: number;     // 960px reference x
  cy: number;     // 960px reference y
  score: number;  // match confidence 0-1
}

/** Adjust OCR-detected x to actual card center, accounting for arc. */
function handAdjust(ocrCx: number): [number, number] {
  const offsetRatio = (HAND_X_CENTER - ocrCx) / (HAND_X_CENTER - HAND_X_MIN);
  const cx = ocrCx + HAND_CX_BASE_SHIFT + Math.round(HAND_CX_NUDGE * offsetRatio);
  const dx = (cx - HAND_X_CENTER) / (HAND_X_MAX - HAND_X_MIN);
  const arcOffset = HAND_ARC_DROP * (1 - Math.cos(dx * Math.PI));
  const cy = HAND_CY_CENTER + Math.round(arcOffset);
  return [cx, cy];
}

/**
 * Capture MTGA hand strip, upscale, OCR.
 * Pipeline: capture by window ID → crop (bottom 20%, trim 280px sides) → upscale → OCR.
 * Works even if MTGA is behind other windows.
 */
async function ocrHandStrip(): Promise<{ items: any[]; } | null> {
  const fullImg = "/tmp/arena-hand-full.png";
  const stripImg = "/tmp/arena-hand-strip.png";
  const stripUp = "/tmp/arena-hand-strip-up.png";

  // Capture by window ID
  if (!(await captureMtga(fullImg))) return null;

  // Get pixel dimensions
  const sipsInfo = Bun.spawnSync({ cmd: ["sips", "-g", "pixelWidth", "-g", "pixelHeight", fullImg], stdout: "pipe" });
  const sipsOut = sipsInfo.stdout.toString();
  const wMatch = sipsOut.match(/pixelWidth:\s*(\d+)/);
  const hMatch = sipsOut.match(/pixelHeight:\s*(\d+)/);
  if (!wMatch || !hMatch) return null;
  const fullW = parseInt(wMatch[1]);
  const fullH = parseInt(hMatch[1]);

  // Crop: bottom 20%, trim sides via Swift CGImage.cropping
  const cropH = Math.round(fullH * HAND_CROP_BOTTOM);
  const cropY = fullH - cropH;
  const cropX = HAND_CROP_SIDES;
  const cropW = fullW - HAND_CROP_SIDES * 2;

  const cropProc = Bun.spawnSync({
    cmd: ["swift", "-e", `
import AppKit
let img = NSImage(contentsOfFile: "${fullImg}")!
let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil)!
let cropped = cg.cropping(to: CGRect(x: ${cropX}, y: ${cropY}, width: ${cropW}, height: ${cropH}))!
let rep = NSBitmapImageRep(cgImage: cropped)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "${stripImg}"))
`],
    stderr: "pipe",
  });
  if (cropProc.exitCode !== 0) return null;

  // Upscale strip for OCR (target ~3840px wide)
  const scale = Math.max(2, Math.round(3840 / cropW));
  Bun.spawnSync({ cmd: ["sips", "--resampleWidth", String(cropW * scale), stripImg, "--out", stripUp] });

  // OCR
  const ocrBin = await compileSwift("ocr");
  const ocr = Bun.spawnSync({
    cmd: [ocrBin, stripUp, "--json", "--min-confidence", "0.10"],
    stdout: "pipe",
  });
  if (ocr.exitCode !== 0) return null;

  try {
    const items = JSON.parse(ocr.stdout.toString());
    // Convert OCR coords (upscaled-strip space) → 960px ref space
    for (const item of items) {
      item.cx = (item.cx / scale + cropX) / fullW * REFERENCE_WIDTH;
    }
    return { items }; // cx already in 960px ref
  } catch {
    return null;
  }
}

/** Levenshtein distance between two strings. */
function levenshtein(a: string, b: string): number {
  if (a.length < b.length) return levenshtein(b, a);
  if (b.length === 0) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 0; i < a.length; i++) {
    const curr = [i + 1];
    for (let j = 0; j < b.length; j++) {
      curr.push(Math.min(
        prev[j + 1] + 1,
        curr[j] + 1,
        prev[j] + (a[i] === b[j] ? 0 : 1),
      ));
    }
    prev = curr;
  }
  return prev[b.length];
}

/** Fuzzy match OCR text against a card name. Returns 0-1 score. */
function fuzzyCardMatch(ocrText: string, cardName: string): number {
  let ot = ocrText.toLowerCase().replace(/['''\u2019()\[\].,]/g, "").trim();
  let cn = cardName.toLowerCase().replace(/['''\u2019]/g, "").trim();

  if (!ot || !cn) return 0;
  if (ot === cn) return 1.0;
  if (cn.includes(ot) && ot.length >= 3) return 0.9;
  if (ot.includes(cn)) return 0.9;
  if (ot.length >= 3 && cn.includes(ot)) return 0.7 * ot.length / cn.length;

  let best = 0;

  // Word-level matching
  const cnWords = cn.split(/\s+/);
  const otWords = ot.split(/\s+/);
  if (cnWords.length > 1 && otWords.length > 0) {
    let hits = 0;
    for (const w of cnWords) {
      if (w.length <= 2) continue;
      for (const ow of otWords) {
        if (w.includes(ow) || ow.includes(w) || levenshtein(w, ow) <= 1) {
          hits++;
          break;
        }
      }
    }
    if (hits > 0) best = Math.max(best, 0.6 * hits / cnWords.length);
  }

  // Short name exact-ish match
  if (cn.length <= 8 && ot.length <= 12) {
    const dist = levenshtein(ot, cn);
    if (dist <= 1) best = Math.max(best, 0.8);
    else if (dist <= 2 && cn.length >= 5) best = Math.max(best, 0.5);
  }

  // Similar length fuzzy
  if (Math.abs(ot.length - cn.length) <= 3 && cn.length >= 6) {
    const dist = levenshtein(ot, cn);
    if (dist <= 2) best = Math.max(best, 0.7);
    else if (dist <= 3 && cn.length >= 10) best = Math.max(best, 0.5);
  }

  return best;
}

/**
 * Find a specific card in the hand by name using OCR.
 * Returns position in 960px reference space, or null if not found.
 */
export async function findHandCard(
  cardName: string,
  knownNames: string[],
): Promise<HandCardPosition | null> {
  const result = await ocrHandStrip();
  if (!result) return null;

  const { items } = result;

  // Match all OCR detections against all known hand card names
  const cardMatches = new Map<string, { score: number; cx: number }[]>();
  for (const item of items) {
    const cx960 = Math.round(item.cx);
    if (cx960 < HAND_X_MIN || cx960 > HAND_X_MAX) continue;

    for (const name of knownNames) {
      const score = fuzzyCardMatch(item.text, name);
      if (score >= 0.4) {
        if (!cardMatches.has(name)) cardMatches.set(name, []);
        cardMatches.get(name)!.push({ score, cx: cx960 });
      }
    }
  }

  // Resolve: assign best non-overlapping position to each card
  const resolved = new Map<string, number>();
  const usedCx = new Set<number>();

  const sortedNames = [...cardMatches.keys()].sort((a, b) => {
    const aMax = Math.max(...cardMatches.get(a)!.map(m => m.score));
    const bMax = Math.max(...cardMatches.get(b)!.map(m => m.score));
    return bMax - aMax;
  });

  for (const name of sortedNames) {
    const candidates = cardMatches.get(name)!.sort((a, b) => b.score - a.score);
    for (const { cx } of candidates) {
      if (![...usedCx].some(ux => Math.abs(cx - ux) < 30)) {
        resolved.set(name, cx);
        usedCx.add(cx);
        break;
      }
    }
    // Fallback: use best match even if overlapping
    if (!resolved.has(name) && candidates.length > 0) {
      resolved.set(name, candidates[0].cx);
    }
  }

  // Find target card
  const targetLower = cardName.toLowerCase();
  for (const [name, ocrCx] of resolved) {
    if (name.toLowerCase() === targetLower || name.toLowerCase().includes(targetLower) || targetLower.includes(name.toLowerCase())) {
      const [cx, cy] = handAdjust(ocrCx);
      const matches = cardMatches.get(name) ?? [];
      const bestScore = matches.length > 0 ? Math.max(...matches.map(m => m.score)) : 0;
      return { name, cx, cy, score: bestScore };
    }
  }

  // Gap inference: if we found N-1 of N cards, the missing one is at the gap
  if (resolved.size === knownNames.length - 1) {
    const count = knownNames.length;
    const spacing = Math.min(80, Math.floor(400 / Math.max(count, 1)));
    const positions = Array.from({ length: count }, (_, i) =>
      480 - Math.floor((count - 1) * spacing / 2) + i * spacing
    );
    for (const pos of positions) {
      if (![...resolved.values()].some(rx => Math.abs(pos - rx) < 30)) {
        const [cx, cy] = handAdjust(pos);
        return { name: cardName, cx, cy, score: 0.3 };
      }
    }
  }

  return null;
}

/**
 * Find positions for ALL hand cards in one OCR pass.
 * Handles duplicate card names by assigning distinct positions.
 */
export async function findAllHandCards(
  knownNames: string[],
): Promise<Map<string, HandCardPosition[]>> {
  const resultMap = new Map<string, HandCardPosition[]>();
  const ocrResult = await ocrHandStrip();
  if (!ocrResult) return resultMap;

  const { items, ocrTo960 } = ocrResult;

  // Match OCR detections to card names
  const cardMatches = new Map<string, { score: number; cx: number }[]>();
  for (const item of items) {
    const cx960 = Math.round(item.cx);
    if (cx960 < HAND_X_MIN || cx960 > HAND_X_MAX) continue;
    for (const name of new Set(knownNames)) {
      const score = fuzzyCardMatch(item.text, name);
      if (score >= 0.4) {
        if (!cardMatches.has(name)) cardMatches.set(name, []);
        cardMatches.get(name)!.push({ score, cx: cx960 });
      }
    }
  }

  // Count copies needed per name
  const nameCounts = new Map<string, number>();
  for (const name of knownNames) {
    nameCounts.set(name, (nameCounts.get(name) ?? 0) + 1);
  }

  // Assign positions with dedup
  const usedCx = new Set<number>();
  for (const [name, needed] of nameCounts) {
    const positions: HandCardPosition[] = [];
    const candidates = (cardMatches.get(name) ?? []).sort((a, b) => b.score - a.score);
    for (const { cx, score } of candidates) {
      if (positions.length >= needed) break;
      if (![...usedCx].some(ux => Math.abs(cx - ux) < 30)) {
        const [adjCx, adjCy] = handAdjust(cx);
        positions.push({ name, cx: adjCx, cy: adjCy, score });
        usedCx.add(cx);
      }
    }
    resultMap.set(name, positions);
  }

  return resultMap;
}

/** Estimate hand card position without OCR. Last resort fallback. */
export function estimateHandPosition(index: number, handSize: number): [number, number] {
  const spacing = Math.min(80, Math.floor(400 / Math.max(handSize, 1)));
  const cx = 480 - Math.floor((handSize - 1) * spacing / 2) + index * spacing;
  return handAdjust(cx);
}
