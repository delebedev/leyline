---
summary: "Guide for AI agents automating MTGA gameplay: server setup, OCR + synthetic clicks, screen detection, game interaction, debugging, and recovery."
read_when:
  - "setting up or running automated MTGA playtesting"
  - "debugging arena automation failures (OCR, clicks, navigation)"
  - "writing automation scripts that interact with the MTGA client"
---
# Agentic Playtesting

Self-contained guide for AI agents automating MTGA gameplay through leyline. Covers server setup, UI automation, game interaction, debugging, and recovery.

## Mental Model

State machine: **detect screen → pick action → execute → confirm result.** One action at a time. Never batch blindly.

Arena automation uses OCR + synthetic clicks (CGEvent). The MTGA client runs in a 960-wide logical window (macOS 2x Retina: 1920 render ÷ 2). All coords in this doc are 960-wide unless noted.

## Prerequisites

### Build & Start Server

```bash
just build                                    # proto-sync + compile + jar
tmux new-session -d -s leyline 'just serve'   # local Forge engine
arena launch                                  # start MTGA client
```

After code changes: `just stop` + rebuild + `just serve`. JVM holds old bytecode.

### Pre-flight Checks (all must pass)

```bash
curl -s http://localhost:8090/api/state       # returns JSON = debug server up
lsof -i :30010 | grep LISTEN                  # leyline listening
lsof -i :30010 | grep ESTABLISHED             # MTGA connected
```

### Config

`synthetic_opponent = true` must be set in `leyline.toml` — without it, event matchmaking queues forever. This is the checked-in default.

## State Detection (Signal Priority)

| Priority | Command | What | Cost |
|----------|---------|------|------|
| 1 | `arena scene` | Lobby screen from Player.log | ~0 |
| 2 | `arena ocr` | All text + coords on screen | ~0 |
| 3 | `arena where` | Combined scene + OCR screen ID | ~0 |
| 4 | Debug API `:8090` | Engine state (phase, turn, actions) | HTTP call |

Never use screenshots for state detection (~800 tokens). `arena ocr` is free.

## Coord Reference

| Element | Coords | Notes |
|---------|--------|-------|
| Action button (Pass/Next/End Turn/Resolve/All Attack) | 888,504 | Universal — all labels, same position |
| Play button (start match) | 866,533 | Bottom-right, deck select / event lobby |
| Cog/Settings icon | 940,42 | Top-right, no OCR text |
| Result dismiss | 210,482 | Click 3x with 2s gaps (bot match) |
| Result dismiss (events) | 480,300 | Click 3x with 2s gaps (sealed/draft) |
| Screen center | 480,300 | General dismiss target |

## Command Quick Reference

| Command | Purpose | Example |
|---------|---------|---------|
| `arena ocr` | All visible text + coords | `arena ocr --find "Play"` |
| `arena click` | Click by text or coord | `arena click "Play" --retry 3` |
| `arena click --exact` | Whole-word match | `arena click "Done" --exact` |
| `arena click --double` | Double-click | `arena click 400,200 --double` |
| `arena drag` | Drag between coords | `arena drag 450,530 480,300` |
| `arena play` | Play card by name (verified) | `arena play "Grizzly Bears"` |
| `arena wait` | Block until condition | `arena wait text="Keep" --timeout 10` |
| `arena scene` | Current scene from Player.log | `arena scene` |
| `arena where` | Detected screen name | `arena where` |
| `arena navigate` | Auto-navigate to screen | `arena navigate Home` |
| `arena board` | Full board state | `arena board --no-ocr` |
| `arena state` | Debug API game state | `arena state` |
| `arena issues` | Aggregate session errors | `arena issues` |
| `arena capture` | Screenshot | `arena capture --out /tmp/screen.png` |
| `arena launch` | Launch MTGA | `arena launch` |

### Key flags

- `--retry N` — recapture + re-OCR up to N times with 500ms gaps. Use during transitions.
- `--exact` — whole-word OCR match. Use for common words ("Done", "Play") to avoid card text collisions.
- `--double` — double-click. Required for draft picks.

## Bot Match (Step by Step)

```bash
# 1. Open play blade
arena click "Play" --retry 3
sleep 2

# 2. Find Match tab
arena click "Find Match" --retry 3
sleep 2

# 3. Select Bot Match
arena click "Bot Match" --retry 3
sleep 1

# 4. Select deck — click card art ~80px above label
arena ocr --fmt                              # find deck name + coords
arena click <cx>,<cy - 80>                   # card art above deck label
sleep 1

# 5. Start match
arena click 866,533                          # Play button
sleep 5                                      # match load (~3-5s Forge)

# 6. Mulligan
arena click "Keep" --retry 2 2>/dev/null; true
sleep 2

# 7. Play the game (see In-Game section) or concede:
arena click 940,42                           # cog icon
sleep 1
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10

# 8. Dismiss result
arena click 210,482 && sleep 2 && arena click 210,482 && sleep 2 && arena click 210,482
arena wait text="Play" --timeout 10          # back in lobby
```

## Sealed Event (Step by Step)

### Fresh DB required for clean event state

```bash
just stop; trash data/player.db; just seed-db
just serve                                    # (background/tmux)
arena launch
```

### Flow

```bash
# 1. Navigate to event
arena click "Play" --retry 3
sleep 2
arena click "Events" --retry 3
sleep 2

# 2. Find sealed tile — click card ART, not title
arena ocr --find "Sealed"                    # get title coords
arena click <cx>,<cy - 50>                   # card art ~50px above title
sleep 2

# 3. Join
arena click "Start" --retry 3
arena wait text="Open" --timeout 10

# 4. Open packs — use coord click (OCR "Open" hits header too)
arena ocr --fmt                              # find button "Open" near bottom (y > 450)
arena click <open_button_cx>,<open_button_cy>
sleep 4                                      # card reveal animation
arena click "Continue" --retry 3
arena wait text="Cards" --timeout 10         # "0/40 Cards" = deck builder loaded

# 5. Build deck — click cards in grid until 40/40
# Row 1: y~200, Row 2: y~420. x positions from OCR.
arena ocr --fmt                              # card names + positions
arena click <x1>,200 && sleep 0.3 && arena click <x2>,200  # ...repeat
# Check progress: arena ocr --find "40/40 Cards"
# Grid shifts as cards are added — re-OCR between batches

# 6. Submit
arena click "Done" --exact --retry 3
sleep 2

# 7. Start match — coord click (text "Play" matches description)
arena click 864,516                          # Play button coords
arena wait text="Keep" --timeout 15

# 8. Keep → Concede → Dismiss (same as bot match concede pattern)
arena click "Keep" --retry 3
sleep 3
arena click 940,42 && sleep 1 && arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10
arena click 480,300 && sleep 2 && arena click 480,300 && sleep 2 && arena click 480,300
sleep 3

# 9. Verify loss tracked
arena ocr --find "Loss"                      # "1 Loss" on event blade

# 10. Resign
arena click "Resign" --retry 3
sleep 2
arena ocr --fmt                              # find "OK" button coords
arena click <ok_cx>,<ok_cy>
sleep 2
arena click "Home" --retry 3
```

### Sealed gotchas

- **"Open" text click hits header, not button.** Find the button via OCR (higher cy, near bottom).
- **"Play" text click hits description.** Always use coord click `864,516`.
- **Deck grid shifts** as cards are added. Re-OCR between batches.
- **Result dismiss: `480,300` not `210,482`.** Event results use center dismiss.
- **Resign confirmation** has "Cancel" and "OK" — coords vary, use OCR.

## Quick Draft (Step by Step)

```bash
# 1. Navigate to Quick Draft tile
arena click "Play" --retry 3
sleep 2
arena ocr --find "Quick Draft"
arena click <cx>,<cy - 50>                   # card art above title
sleep 2

# 2. Pay entry (750 gems)
arena click 868,485                          # gem button bottom-right
sleep 2
arena click "oK" --retry 3                  # OCR reads "oK" not "OK"
sleep 3

# 3. Draft — 41 picks (14 + 14 + 13). Double-click to pick.
arena ocr --fmt                              # card names + coords
arena click <cx>,<cy> --double               # pick a card
sleep 1
arena click 480,50                           # dismiss tooltip
sleep 1
# Repeat per pick. Last card in each pack may have no OCR — tiny thumbnail at ~(68,140).

# 4. After final pick — dismiss vault progress if shown
arena click "Okay" --retry 3
sleep 2

# 5. Build deck
arena click "Done" --retry 3                 # submit → event blade
sleep 2

# 6. Play matches (same pattern as sealed)
```

### Draft gotchas

- **Double-click to pick.** Bypasses "Confirm Pick" button which may be off-screen on 1x.
- **Dismiss tooltip after each pick** — blocks Pack/Pick header. Click `480,50`.
- **41 picks, not 42.** Pack 0: 14, Pack 1: 14, Pack 2: 13. Last card auto-granted.
- **"Unable to finish draft"** after last pick is expected (stubs). Restart server + client. Event blade shows "Build Your Deck" on resume.

## In-Game Interaction

### The Universal Button: 888,504

All in-game action buttons share this coord: Pass, Next, End Turn, Resolve, All Attack, confirm attackers, No Blocks, My Turn, Opponent's Turn. Just click it to advance.

### Turn Cycle (Forge mode)

Forge gives priority at every phase stop — expect 5-10 clicks per turn vs 1-2 in real Arena.

```
Click 888,504 through upkeep/draw → MAIN1
  → Play lands/spells
Click 888,504 → combat → attackers → confirm → post-combat
  → Play more in MAIN2
Click 888,504 → end turn → opponent's turn (5s sleep for Sparky)
```

### Playing Cards

**Order of preference:**

1. **`arena play "Card Name"`** — best. Debug API lookup, verified drag, retries with jitter, confirms zone change.
2. **`arena drag <from> <to>`** — fallback. Cards in hand at y~530.
3. Never use raw click binary.

**Card type patterns:**

- **Lands:** `arena play "Plains"` — plays instantly, no resolve step.
- **Creatures/spells:** `arena play "Bear"` → goes on stack → click 888,504 (Resolve) → click 888,504 (Pass).
- **Targeting:** After cast, OCR shows "Choose any target" → click target → 888,504 to confirm.

### Combat (Forge mode)

- "All Attack" at 888,489 may not respond to clicks (known issue).
- "No Attacks" at 888,456 works reliably.
- Or: click individual creatures to select as attackers, then submit.

### Stuck?

If 888,504 doesn't advance, run `arena board` — check for pending actions (targeting, mandatory choices, modal prompts).

## Debug & Observability

### Debug Server (:8090)

| Endpoint | Returns |
|----------|---------|
| `/api/state` | Phase, turn, active player |
| `/api/id-map?active=true` | Card objects with zones (hand, battlefield, etc.) |
| `/api/game-states` | Timeline of structured snapshots |
| `/api/messages?since=N` | Protocol message ring buffer |
| `/api/priority-events?since=N` | Priority trace (auto-pass, combat, targets) |
| `/api/state-diff?last=N` | Zone deltas from N snapshots back |
| `/api/fd-messages?since=N` | Front Door C→S/S→C messages |
| `/api/events` | SSE stream (real-time) |

### Client-side

```bash
just scry state --no-cards                   # full client state + errors
arena board --no-ocr                         # protocol-only board state
```

### Server logs

`logs/leyline.log` (rotated daily). Read this instead of piping server stdout.

### Comparing server vs client

```bash
curl -s http://localhost:8090/api/state       # what server sent
just scry state --no-cards                    # what client received
```

Mismatch = gap in annotation → GRE message assembly (StateMapper/BundleBuilder).

## Forge Mode Quirks

| Quirk | Impact | Workaround |
|-------|--------|------------|
| Extra priority stops | 5-10 clicks/turn vs 1-2 | Click 888,504 repeatedly |
| Main1 auto-skipped | Lands must play in Main2 | Known issue; play lands after combat |
| Spells go on stack | Need "Resolve" click after cast | Click 888,504 after each spell |
| "All Attack" unresponsive | Can't auto-attack | Click individuals or "No Attacks" |
| Top-N choose cards freeze | 120s timeout | Avoid Commune with Beavers, Analyze the Pollen |
| AI turn timeout | 120s, game may destabilize | Intermittent; restart if stuck |

### Cards to Avoid in Test Decks

Commune with Beavers, Commune with Nature, Adventurous Impulse, Analyze the Pollen (top-N choose), anything with kicker, modes, X costs, flashback, activated sacrifice, scry choices, choose-a-type.

### Cards That Work Well

Vanilla creatures (any CMC), mana dorks (Llanowar Elves, Druid of the Cowl), combat tricks (Giant Growth), pump spells (Sure Strike), auras (Pacifism, Blanchwood Armor), equipment (Quick-Draw Katana), tokens (Resolute Reinforcements), all lands.

## Recovery

| Symptom | Fix |
|---------|-----|
| Unknown screen | `arena where` → if recognized, continue. Else `arena navigate Home` |
| Modal/popup blocking | `arena click 480,300` or `arena click "Okay" --retry 3` |
| Stuck in-game | `arena board` → check actions. May need targeting or modal response |
| "Waiting for Server..." | Server down. Restart server, relaunch MTGA |
| Black screen | Check `logs/leyline.log`. Don't click blindly |
| Ghost match ("Resume" but no match) | `just stop` + restart + `arena launch` |
| Wrong coords (clicks miss) | Check display: `system_profiler SPDisplaysDataType`. Use text clicks as workaround |
| Banned Cards popups (first login) | `arena click "Okay" --retry 3` — repeat until lobby |
| "Connection Lost" dialog | `arena click "Reconnect"` or restart server |
| Ghost courses after mode switch | `just stop` + `trash data/player.db` + `just seed-db` + `arena launch` |

## Concede-Fast Recipe

When goal is just to lose quickly (smoke test, recording FD traffic):

```bash
arena click 866,533                          # Play from deck select
sleep 5
arena click "Keep" --retry 2 2>/dev/null; true
sleep 1
arena click 940,42                           # cog
sleep 0.5
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10
arena click 210,482 && sleep 2 && arena click 210,482 && sleep 2 && arena click 210,482
arena wait text="Play" --timeout 10
```

## Subagent Journaling

When dispatching automation to a subagent, have it journal to a file. If the session dies, the journal shows where it stopped.

```bash
echo "=== <Task Name> $(date) ===" > /tmp/<task>-playtest.log
# After every significant action:
echo "$(date +%H:%M:%S) [step N] what happened" >> /tmp/<task>-playtest.log
```

End with structured summary:
```
=== RESULT ===
outcome: success/failure
error: (if any)
final_screen: (OCR summary)
```

## Server Modes

| Mode | Command | Use case |
|------|---------|----------|
| Local (Forge engine) | `just serve` | Gameplay bugs, mechanic testing |
| Proxy (passthrough + recording) | `just serve-proxy` | Protocol conformance, recording |
| Puzzle | `just serve` + `just puzzle <file>` | Specific board state testing |
| Replay | `just serve-replay` | Replay recorded sessions |

After mode switch: `just stop` → restart → `arena launch`. Ghost matches from previous mode break event browser.

## Puzzle Playtesting

```bash
just serve                                      # start server (if not already running)
just puzzle bolt-face                            # hot-swaps or queues for next Sparky match
```

- Puzzles skip mulligan — goes straight to game board.
- Same nav: Play → Find Match → Bot Match → deck → Play to trigger match.
- `arena wait phase=MAIN1 --timeout 15` to detect game start.
- Validate puzzle cards before commit: `just puzzle-check <file>`.

## Key Rules

1. **One action at a time.** Run ONE command, check output, run next. Never batch arena commands.
2. **OCR first, then click.** Unsure of coords → `arena ocr --fmt` → click discovered coords.
3. **Text clicks > coord clicks.** `arena click "Play" --retry 3` adapts to window size. Coords assume 960-wide.
4. **888,504 is universal.** All in-game buttons. Don't OCR for label — just click the coord.
5. **`--retry 3` during transitions.** Animations delay text rendering.
6. **`--exact` for common words.** `arena click "Done" --exact` avoids card text matches.
7. **5s sleep for Sparky's turn.** Then resume clicking 888,504.
8. **After every automation loop:** `arena issues` to review failures.
9. **`arena play` is preferred over manual drag.** Uses debug API, verifies zone change, retries with jitter.
10. **Never use screenshots for state checks.** Use `arena ocr` (~0 tokens) or `arena scene`.
