# Arena CLI — Agent UI Automation Tool

Binary: `bin/arena` (Kotlin, built with `./gradlew installDist`)

Automates MTGA client interaction via screen capture + OCR + synthetic clicks. Designed for agent use — all output is machine-readable (JSON/structured text).

## Commands

### `arena capture`
Capture MTGA window screenshot.

```
arena capture [--out /tmp/arena/screen.png] [--resolution 1280]
```

- Activates MTGA, captures full screen, crops to window bounds
- Default resolution: 1280px wide (good for agent vision, ~800 tokens)
- Full resolution (1920px): coords map 1:1 to click coordinates

### `arena ocr`
OCR the MTGA window. Returns JSON array of detections.

```
arena ocr                          # all text on screen
arena ocr --find "Play"            # find specific text (substring)
```

Each detection: `{text, cx, cy, x, y, w, h, confidence}`. Coordinates are window-relative.

### `arena click`
Click a UI element by text or coordinates.

```
arena click "Play"                 # OCR-based text click
arena click "Done" --exact         # whole-word match only (avoids card text collisions)
arena click 1446,871               # window-relative coords
arena click "Pass" --retry 3       # retry with 500ms backoff if text not found
arena click "Play" --double        # double click
arena click "Play" --right         # right click
```

### `arena drag`
Drag from one window-relative coord to another. Used for playing cards (Unity requires drag, not click).

```
arena drag 450,530 480,350         # drag card from hand to battlefield
```

- Text clicks: capture → OCR → click center of first match
- Coord clicks: offset by window origin for screen-absolute CGEvent
- `--exact`: match whole OCR text, not substring. Use for common words (Done, Play) in screens with card text.
- `--retry N`: recapture + re-OCR up to N times with 500ms gaps. Use during transitions/animations.

### `arena wait`
Block until a condition is met.

```
arena wait text="Keep" --timeout 15       # wait for text to appear
arena wait no-text="Loading" --timeout 10 # wait for text to disappear
arena wait phase=MAIN1 --timeout 30       # wait for game phase (via debug API)
arena wait turn=3 --timeout 60            # wait for turn number
```

- Text conditions: polls via capture + OCR every 500ms. Skips OCR if screen unchanged.
- State conditions: polls debug API (`/api/state`) every 200ms.
- Default timeout: 30s.

### `arena-annotate`
Annotate an MTGA screenshot with game zones and numbered hand cards. Standalone Python script (`bin/arena-annotate`), requires Pillow.

```
arena-annotate                              # capture live + annotate
arena-annotate /tmp/screenshot.png          # annotate existing screenshot
arena-annotate --out /tmp/annotated.png     # custom output path
arena-annotate --open                       # open result in Preview
arena-annotate --cards                      # also number cards in hand
```

Draws 7 fixed zones (Opp Life, Opp Battlefield, River/Stack, Our Battlefield, Our Life, Our Hand, Action Button). `--cards` adds numbered markers on hand cards using OCR positions. Zone coords are stable across all games at 960x568 logical resolution.

### `arena board`
Unified board state — merges debug API (`/api/id-map`, `/api/state`, `/api/game-states`) and optionally OCR into one JSON response. Shows hand, battlefield, stack, graveyard, exile, life totals, library counts, and available actions.

```
arena board                        # full board state (with OCR)
arena board --no-ocr               # protocol-only (faster, no screen capture)
```

**Data sources:**
- `/api/id-map?active=true` — card objects with zones (bridge accumulator, always current)
- `/api/state` — phase, turn, active player
- `/api/game-states` — actions + life totals (latest snapshot)
- OCR — hand card x-positions for click targeting

### `arena state`
Query game state from debug API (`:8090`).

```
arena state                        # JSON game state
```

### `arena errors`
Query client errors from debug API.

```
arena errors                       # JSON array of client-side errors
```

## Architecture

```
arena click "Play"
  → _activate_mtga()              (osascript, deduped within 2s)
  → capture_window()              (screencapture -R + sips resize to logical)
  → mtga_window_bounds()          (osascript, cached 5s)
  → bin/ocr --find "Play"         (compiled Swift, Vision framework)
  → bin/click x y                 (compiled Swift, CGEvent)
```

### Compiled Tools

| Tool | Source | Purpose |
|------|--------|---------|
| `bin/ocr` | `bin/ocr.swift` | macOS Vision OCR, text detection + bounding boxes |
| `bin/click` | `bin/click.swift` | CGEvent synthetic mouse clicks (Sequoia+ compatible) |
| `tools/window-bounds` | `tools/window-bounds.swift` | MTGA window bounds via CGWindowList |

Rebuild: `swiftc -O -o tools/<name> tools/<name>.swift`

### Performance

- Window bounds: compiled binary (~50ms), cached 5s
- Activate: deduped within 2s window (150ms sleep on activate)
- Wait polling: 500ms OCR interval, skips OCR when screen unchanged (file size check)
- Click retry: 500ms backoff between attempts

### Session Logging

Every `arena` command is logged to `/tmp/arena/sessions/<date>/<time>.jsonl`. Each entry:

```json
{"t":0,"ts":"...","cmd":"click","args":["Play"],"exit":0,"ms":1117,"out":"clicked ..."}
{"t":1,"ts":"...","cmd":"wait","args":["text=Play"],"exit":1,"ms":15478,"error":true,"stderr":"timeout ..."}
```

Fields: `t` = ms since session start, `cmd` = command name, `args` = arguments, `exit` = exit code, `ms` = duration, `error` = true on failure, `stderr` = error message (truncated to 500 chars), `out` = stdout (if <200 chars).

### `arena issues`
Aggregate errors from recent session logs.

```
arena issues          # last 1 day
arena issues 7        # last 7 days
```

Output: session count, command count, error count, then issues grouped by command+args with frequency.

**Agent workflow:** After an automation loop, run `arena issues` to review failures. Use the output to update TODO items or adjust the automation sequence.

## TODO (Improvements)

- [ ] `--bottom-right` bias for `arena click` text matching — prefer action buttons (bottom-right) over card text (center/left) without requiring `--exact`. Would eliminate most false matches automatically.
- [ ] `arena click "Cat Attack" --deck` — auto-offset click target ~80px above text label center to hit card art instead of label. Deck labels aren't clickable; the card image above them is.
- [ ] Result screen needs 3 clicks to dismiss (not 2). Update nav guide after confirming pattern is consistent.
