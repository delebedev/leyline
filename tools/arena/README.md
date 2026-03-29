# Arena CLI

MTGA client automation via screen capture, OCR, and synthetic clicks. Designed for agent-driven UI navigation — all output is machine-readable (JSON/structured text).

Run `arena --help` for the full command list.

## Architecture

```
arena click "Play"
  -> _activate_mtga()              (osascript, deduped within 2s)
  -> capture_window()              (screencapture -R, cropped to MTGA bounds)
  -> bin/ocr --find "Play"         (compiled Swift, Vision framework)
  -> bin/click x y                 (compiled Swift, CGEvent)
```

### Layers

1. **Swift binaries** (`tools/arena/swift/` source, `tools/arena/native/` compiled) — macOS platform APIs
2. **Python CLI** (`tools/py/src/leyline_tools/arena/`) — command dispatch, screen graph, compound flows
3. **Screen graph** (`screens_data.py`) — BFS navigation between MTGA screens with transitions and wait conditions

### Compiled Swift Tools

| Tool | Source | Purpose |
|------|--------|---------|
| `native/ocr` | `swift/ocr.swift` | macOS Vision OCR — text detection + bounding boxes |
| `native/click` | `swift/click.swift` | CGEvent synthetic mouse clicks (Sequoia+ compatible) |
| `native/window-bounds` | `swift/window-bounds.swift` | MTGA window bounds via CGWindowList |

Rebuild: `swiftc -O -o tools/arena/native/<name> tools/arena/swift/<name>.swift`

### Coordinate System

All coords are in **960-wide logical space** (MTGA macOS logical on 2x Retina: 1920 render / 2). OCR returns coords in this space, click expects them. Auto-scales on 1x displays via `window_width / 960`.

### Session Logging

Every command logs to `/tmp/arena/sessions/<date>/<time>.jsonl`:

```json
{"t":0,"ts":"...","cmd":"click","args":["Play"],"exit":0,"ms":1117,"out":"clicked ..."}
```

Fields: `t` (ms since session start), `cmd`, `args`, `exit`, `ms`, `error` (bool), `stderr` (500 char), `out` (if <200 chars).

`arena issues [days]` aggregates errors from session logs.

### Performance

- Window bounds: ~50ms, cached 5s
- Activate: deduped within 2s
- OCR polling: 500ms interval, skips when screen unchanged (file size check)
- Click retry: 500ms backoff

## Key Python Modules

```
cli.py          Command dispatch + session log capture
flows.py        Compound commands (concede, start-bot-match, start-puzzle)
nav.py          Screen detection (scene-first + OCR), BFS navigate, popup dismissal
screens_data.py Screen graph — screens, transitions, popups
interaction.py  Click/drag/move primitives with coord scaling
capture.py      Screenshot + OCR wrappers
gameplay.py     Card playing, land, attack-all
bot_match.py    Legacy bot-match (deprecated, use flows.py start-bot-match)
board.py        Unified board state (debug API + OCR merge)
hand.py         Hand card detection + fuzzy matching
```

## Hand Card OCR

`arena ocr --hand` uses zone-aware OCR for better hand card detection. Standard full-screen OCR misses rotated cards at fan edges. The hand pipeline:

1. Capture at native resolution
2. Crop hand strip (bottom ~30%)
3. Upscale 4x with LANCZOS
4. Run OCR with lowered `minimumTextHeight`
5. Match against known card names from scry (closed vocabulary)
6. Return coords mapped back to 960-space

Implementation: `hand.py` — `_find_hand_card_ocr()`.

## Adding a New Command

1. Write handler in the appropriate module (or new file for compound flows)
2. Add to `COMMANDS` dict in `cli.py`
3. Add help text to `COMMAND_HELP` dict
4. For compound commands: use `exec_step()` for sub-commands, `detect_screen()` for state, `wait_condition()` for blocking waits
5. Return structured JSON via `print(json.dumps(...))`

## Adding a Screen

1. Add screen definition to `SCREENS` in `screens_data.py` (scene, ocr_anchors, ocr_reject)
2. Add transitions to/from the screen in `TRANSITIONS`
3. If the scene is unambiguous (only one screen uses it), `detect_screen()` will use the fast path automatically
