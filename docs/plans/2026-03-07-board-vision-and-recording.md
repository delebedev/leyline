# Plan: Board Vision & Video Recording

## Status: Implemented (phase 1)

## Problem

The bug-fix pipeline has three observability gaps:
1. **No unified board state** — agent must hit 2-3 debug endpoints + OCR separately and correlate manually
2. **No video evidence** — screenshots miss temporal bugs (animations, timing, sync issues)
3. **No lock detection** — remote Mac sleeps, agent captures screen saver and wastes cycles

## What we built

### `arena board` — unified board state query

Single command that merges debug API + OCR + zone geometry into one JSON response.

**Sources:**
- `/api/game-states` (latest snapshot) — instanceId, name, zone, P/T, tap, combat state, actions
- `/api/state` — phase, turn, active player, life totals
- `arena ocr` — text bounding boxes in screen coordinates
- Zone rectangles from `arena-annotate` (hardcoded 960x568)

**Output shape:**
```json
{
  "match": {"phase": "Main1", "turn": 3, "activePlayer": "...", "gameOver": false},
  "life": {"ours": 20, "theirs": 18},
  "hand": [{"instanceId": 370, "name": "Grizzly Bears", "estimatedX": 380, "hasAction": true}],
  "our_battlefield": [{"instanceId": 119, "name": "Forest", "isTapped": true}],
  "opp_battlefield": [...],
  "stack": [...],
  "our_graveyard": {"count": 2, "cards": ["Lightning Bolt", "Shock"]},
  "opp_hand_count": 5,
  "actions": [{"actionType": "Cast", "instanceId": 370, "name": "Grizzly Bears"}]
}
```

**Hand card correlation:** protocol knows hand contents and order; OCR detects text in hand zone region (y > 490). Cluster OCR x-positions, assign to hand cards left-to-right. Fallback: estimate even spacing.

**Flags:** `--no-ocr` skips screen capture (faster, protocol-only).

**File:** `tooling/src/main/kotlin/leyline/arena/Board.kt` (registered in `ArenaCli.kt`)

### `arena-record` — video capture for bug reproduction

Records MTGA window as compact MP4 for GitHub issue attachments.

**Pipeline:**
1. Detect MTGA window bounds via `bin/window-bounds`
2. Capture screen frames via PIL (ImageGrab) at configurable FPS
3. Crop to MTGA window region
4. Encode H.264 MP4 via ffmpeg (`-crf 30 -preset fast -movflags +faststart`)
5. Output: ~30KB/s, GitHub-embeddable, inline video player

**Why MP4 over GIF:** MP4 is 5-10x smaller, better quality (full color vs 256), GitHub renders inline. GIF is a trap.

**Screen lock detection:** checks `running of screen saver preferences` via osascript. Warns if Mac is locked.

**File:** `bin/arena-record` (Python, executable)

**Usage:**
```bash
arena-record --duration 10 --out /tmp/repro.mp4
arena-record -d 5 --fps 10    # lighter capture
arena-record --no-crop         # full screen
```

## Phase 2 (not yet built)

- **Screen lock check in `Shell.kt`** — preflight in `captureWindow()`, fail fast with clear message instead of silently capturing locked screen
- **`arena board` live test** — needs active match to validate OCR correlation and zone mapping
- **YouTube training data pipeline** — `yt-dlp` + `ffmpeg` scene extraction + auto-labeling from protocol timestamps for card bounding box model training (documented in meeting notes, separate spike)
- **YOLO/CoreML card detector** — when protocol-informed zone mapping breaks down (multiple same-P/T creatures). Bootstrapped from our recordings + YouTube data.
