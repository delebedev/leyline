# Zone-Aware OCR

Card identification by exploiting known spatial structure. Not general OCR — we have a closed vocabulary (scry gives card names) and known screen regions. The goal is matching each card name to a screen coordinate.

## Problem

Standard full-screen OCR detects 1-5 out of 7-8 hand cards. Fails on:
- Rotated cards at fan edges (leftmost worst)
- Overlapping card titles
- Short names ("Plains", "Swamp") that get lost in noise
- Battlefield card names confused with hand cards

## Core insight

We know MORE than a generic OCR pipeline assumes:
- **Card count** — from scry (exact)
- **Card names** — from scry (closed vocabulary, not open discovery)
- **Zone regions** — hand y-band, battlefield y-band, stack, etc.
- **Arc geometry** — cards follow a circular arc with predictable rotation per position
- **Card count → position formula** — `angle = startAngle + i * deltaAngle`, x from arc trig

## Zones

| Zone | Region (960-space) | Notes |
|------|-------------------|-------|
| Hand | y > ~460 | Arc fan, rotated cards, our primary target |
| Our battlefield | y ~250-400 | Horizontal row, less overlap |
| Opponent battlefield | y ~120-250 | Horizontal row, may be tapped/rotated |
| Stack | center, y ~200-350 | Spells resolving, usually 1-2 cards |

## Approach: hand zone

### Pipeline

```
1. Capture at native resolution (1920px on retina, screencapture -R)
2. Crop hand strip (bottom ~30% of image)
3. Upscale 2x with LANCZOS (→ 3840px wide strip)
4. [Optional] Slice into N columns (N = hand size from scry)
5. [Optional] Deskew each column by estimated arc rotation
6. Run OCR (minimumTextHeight lowered)
7. Match detected text against scry card names (fuzzy)
8. Output: [{name, cx, confidence}] mapped back to 960-space
```

### Deskew angles (estimated from arc geometry)

For N cards, rotation per card index i:
```
deltaAngle = min(FitAngle / N, MaxDeltaAngle)
startAngle = -(deltaAngle * (N - 1)) / 2
cardAngle[i] = startAngle + i * deltaAngle
```

Empirical estimates (degrees, negative = clockwise tilt):
- 7 cards: [-21, -14, -7, 0, 7, 14, 21] (roughly)
- 5 cards: [-14, -7, 0, 7, 14]
- 8 cards: [-24, -17, -10, -3, 3, 10, 17, 24]

Exact values need measurement from test images or Ghidra decompilation of `CardLayout_Hand` params (Radius, FitAngle, MaxDeltaAngle — baked in prefab, see `mtga-internals/docs/hand-layout-internals.md`).

### Matching strategy

We're not discovering text — we're confirming locations. For each scry card name:
1. Search OCR results for fuzzy match (Levenshtein or substring)
2. If found → cx is the card's screen position
3. If not found → card is in one of the unmatched column positions

With N cards and M successful OCR matches, we have N-M unknowns at known positions. If N-M ≤ 2, scan-and-cancel is cheap (2-4 tool calls vs 14-21).

## Approach: battlefield zones

Same principle, simpler geometry:
- Cards are horizontal (no arc rotation)
- Less overlap (cards are spaced or tapped at 90°)
- Tapped cards: name rotated 90° — need separate crop+rotate
- Our vs opponent: just different y-bands

Pipeline is the same minus deskew. Crop zone strip → upscale → OCR → match against scry zone objects.

## Test set

Screenshots saved at `/tmp/arena-hand-experiment/`:

| File | Hand size | Cards | Resolution |
|------|-----------|-------|-----------|
| `gamestate-1-native.png` | 7 | Plains, Hinterland Sanctifier, Scoured Barrens, Vengeful Bloodwitch, Scoured Barrens, Inspiring Overseer, Mortify | 1920x1136 |
| `gamestate-1-scry.json` | | ground truth | |
| `gamestate-2-native.png` | 5 | Dazzling Angel, Plains, Vengeful Bloodwitch, Inspiring Overseer, Mortify | 1920x1136 |
| `gamestate-2-scry.json` | | ground truth | |

Copy to Desktop for agent access:
```bash
cp /tmp/arena-hand-experiment/gamestate-*-native.png ~/Desktop/
cp /tmp/arena-hand-experiment/gamestate-*-scry.json ~/Desktop/
```

## Score metric

For each gamestate: how many scry hand cards get a correct cx mapping?

**Perfect = every card name matched to a screen x-coordinate.**

Test matrix (each row is an approach, each column is a gamestate):

| Approach | GS1 (7 cards) | GS2 (5 cards) | Notes |
|----------|---------------|---------------|-------|
| Baseline (960px full screen) | | | Current `arena ocr` |
| 1920px full screen | | | `arena ocr --hires` |
| Hand strip crop + 2x upscale | | | The 7/7 pipeline from earlier experiment |
| Per-card column slice | | | N columns, no deskew |
| Per-card column + deskew | | | Estimated rotation correction |
| minimumTextHeight = 0.01 | | | One-liner in ocr.swift |
| Targeted --find per name | | | Run OCR N times, once per card |
| Best combination | | | Winner |

## Integration target

The winner becomes `_find_hand_card_ocr()` in `bin/arena.py`, called by `_find_hand_card()` when detection model is unavailable or as primary card-finding strategy. Returns `(cx, cy)` in 960-space for a given card name.

Later: extend to `_find_battlefield_card_ocr()` for targeting (auras, removal spells, etc.).

## Opensourceability

This entire pipeline is clean — macOS Vision framework, Pillow image processing, no proprietary data. Can be released as part of the arena CLI toolkit. The decomp-derived arc parameters (if used) are empirical measurements, not decompiled code.
