# arena-ts

MTGA UI automation tool. Clicks, drags, reads game state, plays cards. Works with real Arena (Sparky bot matches) and leyline server.

## Setup

Requires [Bun](https://bun.sh) (1.3+) and macOS (CGEvent for input, Vision for OCR).

```bash
just arena-ts --help
just arena-ts preflight    # verify system readiness
```

Native tools (C shim for mouse input, Swift for OCR) compile automatically on first use. Cached in `~/.arena/bin/` — recompiles when source changes.

## Commands

### System
```bash
arena preflight              # MTGA running? accessibility? window? Player.log?
arena scene [--json]         # current scene (detects InGame via GRE messages)
arena wait scene=Home        # poll until condition met
```

### Input
```bash
arena click 480,300          # click coordinates (960px reference space)
arena click home-cta         # click named landmark
arena click home-cta --dry-run  # show resolved coords, don't click
arena drag 300,480 480,250   # smoothstep drag between points
arena pass [--n 5]           # click action button (pass/resolve/next)
```

### Gameplay
```bash
arena hand [--json]          # hand cards with OCR-detected positions (visual order)
arena turn [--json]          # turn state: phase, hand, playable actions, life totals
arena land [card-name]       # play a land (OCR-located, verified)
arena cast <card-name>       # cast a spell (OCR-located, verified)
arena keep                   # keep hand during mulligan + handle card returns
arena concede                # concede match + dismiss result screens
```

### Landmarks

Named coordinates in 960px reference space. `arena click --help` lists all.

Key landmarks: `home-cta`, `action-btn`, `nav-decks`, `nav-back`, `game-concede`, `dismiss`.

## Architecture

```
cli.ts                    # entry point — noun-verb dispatch, telemetry
src/
  native/
    arena-shim.c          # CGEvent mouse + window bounds + accessibility (FFI)
    ocr.swift             # Vision framework OCR
  compile.ts              # compile-on-first-use, content-hash cache
  input.ts                # Bun FFI bindings, window-ID capture, drag
  window.ts               # coordinate scaling (960px ref → screen)
  scene.ts                # scene detection via scry-ts (GRE + SceneChange)
  gamestate.ts            # live game state via scry-ts accumulator
  hand.ts                 # OCR hand card detection (crop, upscale, fuzzy match)
  landmarks.ts            # named coordinates
  wait.ts                 # poll-based waitFor
  telemetry.ts            # JSONL command log (~/.arena/log/)
  commands/               # one file per command
```

### Key design decisions

**Window-ID capture.** `screencapture -l <wid> -o` captures MTGA even behind other windows. No foreground needed for OCR — only click/drag activate the window.

**OCR pipeline for hand cards.** Capture → crop bottom 20% + trim sides → upscale 2x → Vision OCR → fuzzy match against scry card names → arc-adjusted positions. Gap inference for cards too overlapped to read.

**Scry-ts internals, not subprocess.** Imports parser, accumulator, game detection directly. Game state in ~50ms, no process spawn overhead.

**960px reference space.** All coordinates defined at 960px logical width. Auto-scaled to actual window at click time. Landmarks are readable, portable.

**No sleeps in commands.** Every wait polls game state or scene via `waitFor`. Timeouts, not fixed delays.

## Telemetry

Every command logs to `~/.arena/log/YYYY-MM-DD.jsonl`:
```json
{"ts":"...","cmd":"cast","args":["Lightning Bolt"],"ms":4900,"ok":true}
```

## Known issues

- **Adventure cards** show adventure name in hand instead of card name (scry-ts resolver bug)
- **HTML tags** in card names from Arena DB (`<nobr>`) leak through
- **Cast actions** are unfiltered by mana — lists all legal casts, not affordable ones
- **Leftmost hand cards** may not OCR-detect if heavily overlapped (falls back to gap inference)
