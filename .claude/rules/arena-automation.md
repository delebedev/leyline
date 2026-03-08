# Arena UI Automation

Applies when running `arena` commands, reading `docs/arena-cli.md` or `docs/arena-nav.md`, or working in `tooling/**/arena/**`.

Reference: `docs/arena-cli.md` (commands), `docs/arena-nav.md` (screen flow + coords).

## Core principle

Arena automation is a state machine: **what screen am I on → what's my goal → what's the minimal action → wait for confirmation.**

## State detection

Signal priority (most authoritative → fallback):

1. **GRE messages** (Player.log via scry) — ConnectResp = match started, GSM = game state. Fastest in-game signal.
2. **Scene changes** (Player.log `Client.SceneChange`) — lobby navigation (Home, DeckBuilder, etc.)
3. **OCR** (`arena ocr`) — visual confirmation, UI text. ~0 tokens. Never use screenshots (~800 tokens).
4. **Debug API** (`:8090`) — Forge engine state. Use only for debugging game logic, not screen detection. Can be stale.

- `arena ocr` — full screen text with coords
- `arena ocr --find "text"` — targeted check (exit 0 = found, exit 1 = not found)
- `arena scene` — latest scene from Player.log
- `arena wait text="X" --timeout 10` — polls OCR with change detection (500ms interval, skips OCR on unchanged frames). Use this for transitions. Always set `--timeout 10` or less.

**Use `arena wait` for all transitions.** It's better than hand-rolled sleep+ocr loops — it has built-in change detection and skips redundant OCR. Just keep timeout <= 10s.

## Coords

**OCR coords are ground truth.** Window-relative logical coords, no scaling needed regardless of capture resolution.

- Use `arena ocr --find` to discover coords dynamically
- Hardcoded coords from the nav guide only work at the window resolution they were measured at
- Deck thumbnails: click ~80px above the deck name label (label isn't clickable, card art above it is)

## Clicking

- Every click needs a purpose and a confirmation (`arena ocr --find` or `arena wait` after)
- `--retry 3` for text clicks during transitions (animations delay text rendering)
- **Play cards with click-move-click**, not drag gestures. Unity responds better to: click card in hand → move cursor to above-center battlefield → click to drop.
  ```bash
  # 1. Activate MTGA
  osascript -e 'tell application "System Events" to set frontmost of (first process whose name is "MTGA") to true'
  sleep 0.15
  # 2. Click card in hand (screen coords = window origin + OCR coords)
  bin/click <screen_x> <screen_y>
  # 3. Move to above-center of screen (no click) — higher than center works better
  sleep 0.2
  bin/click <center_screen_x> <above_center_screen_y> move
  # 4. Small pause, then click to confirm placement
  sleep 0.15
  bin/click <center_screen_x> <above_center_screen_y>
  ```
  - Screen coords = window origin (from `mtga_window_bounds()`) + OCR coords.
  - Move target should be horizontally centered and above vertical center (~y offset -100 from center).
  - Works reliably for leftmost and rightmost cards in hand.
  - Lands play instantly; spells get "Pay" prompt → auto-pays if mana available.
  - Fallback: `arena drag <from> <to>` still works but is less reliable.
- **Prefer blind coord clicks over OCR-matching when the position is stable.** In-game action buttons always appear at ~888,504 regardless of label. Don't OCR to find "Next" vs "End Turn" — just click the coord.

## Recovery

1. Check `just scry state --no-cards` and `logs/leyline.log`
2. `arena ocr` to assess current state
3. If recognizable screen — resume from that point
4. Black screen — check server logs, don't click
5. Unrecognizable — stop and ask

## Server

Ensure server is running before automation. `just serve` for local, `just serve-proxy` for recording. Never kill a server you didn't start.

### Which mode?

- **`just serve`** — local Forge engine. Use for gameplay bugs (#30-#42 style), mechanic testing, anything that exercises our code.
- **`just serve-proxy`** — passthrough to real Arena servers, captures traffic. Use for recording sessions, FD protocol conformance, comparing our output to real server.

### Confirming the server is up and connected

1. **Debug API:** `curl -s http://localhost:8090/api/state` — should return JSON (even `{"matchId":null}` means server is up)
2. **Port binding:** `lsof -i :30010 | grep LISTEN` — our Java process must be listening
3. **Client connected:** `lsof -i :30010 | grep ESTABLISHED` — MTGA process connected to localhost
4. **Check mode:** `ps aux | grep leyline | grep -o '\-\-proxy-[a-z]*'` — if `--proxy-fd`/`--proxy-md` appear, it's proxy mode. Empty = local mode.
5. **Server logs:** `tail -20 logs/leyline.log` — look for "Front Door: client connected" or startup messages

### Stale match state after mode switch

**Problem:** switching from `serve-proxy` to `just serve` (or vice versa) leaves the client with ghost matches from the previous server. The event browser shows "In Progress" / "Resume" for matches that don't exist on the new server. Clicking "Bot Match" in the event list may show a duplicate — one real, one ghost.

**Fix:** after switching server modes, use `just stop` (kills server + client), start the new server, then `arena launch`. The fresh client login clears stale match state.

**Detection:** if OCR shows "Resume" or "In Progress" on the event browser but `curl -s http://localhost:8090/api/state` shows `matchId: null`, the match is a ghost.

### Starting the server from Claude Code

`just serve` is a foreground process. Use tmux:
```bash
tmux new-session -d -s leyline 'cd /Users/denislebedev/src/leyline && just serve'
```
Check: `tmux capture-pane -t leyline -p | tail -5` for output. Kill: `just stop`.

## Screenshots

**Always use `arena capture`** — not `screencapture`, not `peekaboo` directly. It handles window activation, crops to MTGA window bounds, and produces a clean game-only image at logical resolution (960x568) where OCR coords map 1:1.

```bash
bin/arena capture --out /tmp/screenshot.png --resolution 1920   # full-res
bin/arena capture --out /tmp/screenshot.png                      # default 1280
```

For annotating screenshots (red boxes, labels):
```python
from PIL import Image, ImageDraw, ImageFont
img = Image.open('/tmp/screenshot.png')  # 960x568 at default res
draw = ImageDraw.Draw(img)
# OCR coords map directly to pixel coords in arena capture output
draw.rectangle([x1, y1, x2, y2], outline='red', width=3)
```

## Starting a Bot Match (Find Match flow)

The event list shows ghost matches after mode switches. Use the **Find Match** tab instead:

1. From home screen: click "Play" (bottom-right)
2. Click "Find Match" tab (top-right, ~867,112)
3. Click "Bot Match" in the format sidebar (right side, ~842,410)
4. Click a deck thumbnail (e.g. ~230,350 — above the deck name label)
5. Click "Play" button (bottom-right, ~866,533)
6. Wait for mulligan: `arena wait text="Keep" --timeout 30`

**Deck selection is required** — Play button won't work without selecting a deck first.

## Phase stops for opponent-turn bugs

To pause the game during Sparky's turn (e.g. reproducing visual bugs on opponent's turn):

- Phase stop icons are along the right edge of the game window — small squares, no OCR text
- Click to toggle (they turn blue when active)
- Once set, the game pauses on that phase and gives you priority to inspect/screenshot

## In-game: concede fast

When the goal is to lose quickly (recording FD traffic, not MD):

```bash
bin/arena click 866,533                          # Play (deck select screen button)
sleep 5                                          # match load (~3-5s local Forge)
# If mulligan appears, click Keep; if server auto-keeps, this is a no-op
bin/arena click "Keep" --retry 2 2>/dev/null; true
sleep 1
bin/arena click 940,42                           # cog icon (top-right, no OCR text)
sleep 0.5
bin/arena click "Concede" --retry 3
bin/arena wait text="DEFEAT" --timeout 10
bin/arena click 210,482 && sleep 2 && bin/arena click 210,482 && sleep 2 && bin/arena click 210,482
bin/arena wait text="Home" --timeout 10          # back to lobby
```

**Notes:**
- Mulligan screen may not appear — server sometimes auto-keeps. Don't block on it.
- `sleep 5` is a blunt wait for match load. TODO: add `arena wait gre=ConnectResp` for precise detection.
- Timeout 10s max for local Forge. Proxy mode may need 15s.

## In-game: playing cards

**The action button is always at ~888,504.** Next, End Turn, Pass, Cancel, All Attack, confirm attackers, No Blocks — all same coord. Never OCR to find the label.

### Turn structure

1. **Click 888,504** repeatedly through upkeep/draw (Next → main phase)
2. **Play a land:** `arena drag <x>,530 480,400` — lands play instantly, no prompt
3. **Play creatures/spells:** `arena drag <x>,530 480,350` — if mana available, auto-pays; if not, "Cancel" appears at 888,504
4. **Attack:** "Choose attackers" → click 889,504 ("All Attack") → click again to confirm
5. **Post-combat:** try more drags for second main
6. **End turn:** click 888,504

### Playing cards from hand

Cards span **x ~350-620, y ~530**, spaced ~40px apart. **Don't figure out what's playable — just try left to right and see what happens.**

**Use click-move-click** (not drag) to play cards. Get card coords from OCR, convert to screen coords (add window origin), then: click card → `sleep 0.2` → move to above-center → `sleep 0.15` → click.

- Card played successfully → board changes, new priority button appears
- "Pay" prompt + "Cancel" → out of mana, click 888,504 to cancel, move on
- Modal choice (kicker, scry, surveil) → handle the prompt, then "Done"

### Targeting spells

When a spell says "Choose any target":
- **Opponent's face:** click ~478,112 (life total area)
- **A creature:** click its coords from OCR (power/toughness numbers)
- **Then confirm** at 888,504 if needed

### Combat

- **Attack:** "All Attack" at 889,504, then confirm ("N Attackers") at same coord
- **Block:** drag your creature to their attacker (works but unreliable — "No Blocks" is safer)
- **Damage assignment:** if prompted, tick "Auto Assign Damage" checkbox first (persists for the session), then "Done". If missed, "Auto Allocate" (~844,101) then "Done".

### Triggers and modals

- **Surveil:** "Surveil" header, card shown with "Graveyard" (left) and "Library" (right) zones. Drag card to Library to keep on top, or Graveyard to bin it. Click "Done" after. Good cards → Library, bad cards → Graveyard.
- **Scry:** "TOP" / "BOTTOM" buttons visible, click one, then "Done"
- **Kicker:** "Choose One" screen — click the option (left = without, right = with kicker)
- **"[Click to Continue]"** — click it at the shown coords

### Discard phase

When not playing cards, hand exceeds 7. "Submit 0" appears at 886,504.
Click 400,530 (hand area, selects a card), then 886,504 (Submit).

### Result screen

"DEFEAT" or "VICTORY" → click (210,482) 3x with 2s gaps to dismiss:
```bash
bin/arena click 210,482 && sleep 2 && bin/arena click 210,482 && sleep 2 && bin/arena click 210,482
```

### Two modes

**Autopilot mode** — when the goal is just to play through (recording, losing fast):
- Drag cards left to right, cancel if can't pay, don't read card text
- Click 888,504 for every button without OCR
- Chain actions: drag → 1s sleep → drag → check once → next phase
- End turn when out of mana, no need to optimize plays

**Thinking mode** — when you need to make decisions (testing mechanics, targeting matters):
- OCR after each action to read card names, prompts, board state
- Read "Pay" costs to know if you can afford it
- Choose targets deliberately (face vs creature vs specific creature)
- Handle modals (scry, kicker, damage assignment) based on the situation
- Use screenshots sparingly for complex board states

Default to **autopilot**. Switch to **thinking** when:
- A prompt appears you haven't seen before
- Targeting is required ("Choose any target")
- A modal choice appears (scry, kicker, modes)
- Board state matters (blocking decisions, damage assignment)

### General

- **Step by step.** One command at a time, check result, next command. No bash loop scripts.
- **888,504 is the universal button.** Next, End Turn, Pass, Cancel, All Attack, confirm — all same coord.
- **5s sleep for Sparky's turn.** Human opponents need `arena ocr` polling.

## After every automation loop

`arena issues` to review failures.
