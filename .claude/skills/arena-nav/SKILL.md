---
name: arena-nav
description: Load before any MTGA UI automation — bot matches, sealed events, draft, concede loops, or in-game card playing. Unified coords, flows, recovery.
---

## What I do

Provide the complete reference for automating the MTGA client — screen detection, navigation, card playing, concede flows, and recovery. Single source of truth for coords, commands, and flows.

## When to use me

- Before running ANY `arena` commands (bot match, sealed, draft, reproduce, etc.)
- When an automation flow gets stuck and you need recovery recipes
- When you need to navigate between MTGA screens

## Core Principle

Arena automation is a state machine: **detect current screen → pick target → minimal action → wait for confirmation.**

Never batch commands blindly. One action, one check, next action.

## Signal Priority (state detection)

| Priority | Source | Use for |
|----------|--------|---------|
| 1 | `arena scene` | Which lobby screen (Home, DeckBuilder, etc.) — reads Player.log |
| 2 | `arena ocr` | Visual text on screen — coords, button labels. ~0 tokens |
| 3 | `arena where` | Combines scene + OCR to identify screen name |
| 4 | Debug API (`:8090`) | Game engine state — phase, turn, actions. Can be stale |

**Never use screenshots for state detection** (~800 tokens each). `arena ocr` costs ~0.

## Commands Quick Reference

| Command | Purpose | Example |
|---------|---------|---------|
| `arena ocr` | All text + coords on screen | `arena ocr --find "Play"` |
| `arena click` | Click by text or coord | `arena click "Play" --retry 3` or `arena click 888,504` |
| `arena clear-field` | Clear focused input | `arena clear-field` |
| `arena paste` | Paste into focused input | `arena paste forge@local` |
| `arena key` | Send a key press | `arena key tab` |
| `arena drag` | Drag between coords | `arena drag 450,530 480,300` |
| `arena play` | Play a card by name (verified) | `arena play "Grizzly Bears"` |
| `arena wait` | Block until condition | `arena wait text="Keep" --timeout 10` |
| `arena scene` | Current scene from Player.log | `arena scene` |
| `arena where` | Detected screen name | `arena where` |
| `arena navigate` | Auto-navigate to target screen | `arena navigate Home` |
| `arena board` | Full board state (API + OCR) | `arena board --no-ocr` |
| `arena state` | Debug API game state | `arena state` |
| `arena capture` | Screenshot | `arena capture --out /tmp/screen.png` |
| `arena launch` | Launch MTGA | `arena launch` |
| `arena issues` | Review session errors | `arena issues` |
| `arena errors` | Client errors from Player.log | `arena errors` |

Run `arena --help` for the full command list with flags.

## Coordinate System

**All coords are in 960-wide logical space.** This is the MTGA window's macOS logical size on a 2x Retina display (1920 render ÷ 2 = 960 logical).

- `arena ocr` returns coords in this space
- `arena click <x>,<y>` expects coords in this space
- `arena_screens.py` transitions use this space
- All coords in this document are in this space

**On 1x displays:** `arena ocr` auto-detects scale factor and adjusts. If coords seem wrong, check `system_profiler SPDisplaysDataType | grep "UI Looks like"`.

### Canonical Coord Table

| Element | Coords (960-wide) | Notes |
|---------|-------------------|-------|
| Action button (Pass/Next/End Turn/All Attack) | 888,504 | Universal — all labels, same position |
| Play button (start match) | 866,533 | Bottom-right, deck select / event lobby |
| Cog/Settings icon (lobby) | 900,42 | Top-right, opens Options / Log Out |
| Cog/Settings icon (in-game) | 940,42 | Top-right, opens Concede menu |
| Result dismiss | 210,482 | Click 3x with 2s gaps |
| Find Match tab | 867,112 | Right-side tab |
| Bot Match sidebar | 842,410 | Right-side format list |
| Play blade open | 866,533 | Same as Play button |
| Close play blade (X) | 746,93 | Top-right of blade |
| Back arrow (event lobby) | 53,57 | Top-left |
| Screen center | 480,300 | General dismiss target |
| Booster open button | 480,533 | Center-bottom |
| Continue (pack reveal) | 867,532 | Bottom-right |
| Opponent portrait (targeting) | 480,85 | Click during targeting prompt to select opponent as target |
| Resolve (stack) | 888,490 | Same area as Pass — resolves top of stack |
| Submit (targeting) | 888,489 | Confirm target selection ("Submit N") |
| Cancel (targeting/kicker) | 888,456 | Back out of targeting or decline optional cost |
| Kicker choice (right card) | 560,250 | "Choose One" modal — right option = Cast With Kicker |

## How to Play Cards

**Use these commands in order of preference:**

1. **`arena play "Card Name"`** — Best. Finds card via debug API, verified drag, retries with jitter. Confirms zone change.
2. **`arena drag <from_x>,<from_y> <to_x>,<to_y>`** — Fallback. Manual coords. Cards in hand are at y~530, x spans 350-620.
3. **Never use raw click binary** — that's arena's internal tool. Agents use `arena` commands only.

### Card playing patterns

- **Lands:** `arena play "Plains"` or `arena drag <x>,530 480,350` — plays instantly, no prompt
- **Spells:** Same drag, gets "Pay" prompt if mana available → auto-resolves. If no mana, "Cancel" appears at 888,504
- **Targeting:** After cast, targeting prompt shows "Submit 0". Click target to select, then Submit to confirm:
  - **Player targeting:** click opponent portrait at 480,85. OCR won't show a text label — it's a portrait click.
  - **Creature targeting:** click the creature card on the battlefield.
  - After selecting, "Submit 1" appears at 888,489 — click to confirm.
- **Optional costs (kicker, buyback, bargain):** "Choose One" modal appears — left=normal cast, right=with cost. Click the right card (~560,250) to pay, or left to decline. Then targeting follows.
- **Modal choices (scry, surveil, modal ETB):** "Choose One" modal — click the option card. Then click "Done".

### Combat interactions (attackers & blockers)

- **Blocker assignment is click-click, NOT drag.** Click your blocker creature, then click the attacking creature. Drag opens card preview instead.
- **Interactive prompts (DeclareAttackers/Blockers) have a 15s bridge timeout.** The engine auto-passes if no response within 15s. Script combat assignments as fast sequences with `sleep 0.5` between clicks — do NOT OCR between each click.
- **Fast block example:**
  ```bash
  # Click blocker, click attacker, submit — all under 15s
  arena click <blocker_cx>,<blocker_cy> && sleep 0.5
  arena click <attacker_cx>,<attacker_cy> && sleep 1
  arena click 887,504   # "1 Blocker" submit button
  ```

## In-Game Turn Cycle

Our Forge engine gives priority at every phase stop — expect 5-10 button clicks per turn vs 1-2 in real Arena.

**888,504 is the universal button.** Pass, Next, End Turn, Resolve, All Attack, confirm attackers, No Blocks — all same coord. Just click it repeatedly to advance.

**Turn structure:**
1. Click 888,504 through upkeep/draw → reach MAIN1
2. Play lands/spells (see above)
3. Click 888,504 → combat → "All Attack" → confirm → post-combat
4. Play more in MAIN2
5. Click 888,504 → end turn → opponent's turn (5s sleep for Sparky)

**Stuck?** If clicking 888,504 doesn't advance, run `arena board` — check for pending actions (targeting, mandatory choices).

## Navigation

### `arena navigate <screen>`

Auto-navigates using BFS through the screen graph. Handles popups, reroutes on unexpected screens (max 3 attempts).

Available screens: `Home`, `FindMatch`, `DeckSelected`, `Events`, `EventLanding`, `EventLobby`, `InGame`, `ConcedMenu`, `Result`, `Mulligan`, `DraftPick`, `DeckBuilder`, `SealedBoosterOpen`

**If navigate times out:** fall back to manual text clicks (more reliable). `arena navigate` is convenient but can be flaky across display types.

### Manual navigation (preferred for reliability)

**Prefer text clicks over coord clicks for navigation.** OCR-discovered coords adapt to any window size; hardcoded coords assume 960-wide.

| From | To | Steps |
|------|----|-------|
| Home → Play blade | `arena click "Play" --retry 3` |
| Play blade → FindMatch | `arena click "Find Match" --retry 3` |
| FindMatch → Bot Match | `arena click "Bot Match" --retry 3` → click deck → `arena click 866,533` |
| Home → Log Out | `arena click 900,42` → sleep 1 → `arena click "Log Out" --retry 3` |
| InGame → Concede | `arena click 940,42` → sleep 1 → `arena click "Concede" --retry 3` |
| Result → Home | `arena click 210,482` × 3 with 2s gaps |

## Bot Match Flow (Complete Recipe)

**Step by step — run ONE command, check output, run next. Do not batch.**

```bash
# 1. Navigate to Find Match (use text clicks — more reliable than arena navigate)
arena click "Play" --retry 3                 # Home → play blade
sleep 2
arena click "Find Match" --retry 3           # switch to Find Match tab
sleep 2

# 2. Select Bot Match + deck
arena click "Bot Match" --retry 3
sleep 1
arena ocr --fmt                              # find deck names and coords
# Click card art ~80px above the deck label's cy coordinate:
arena click <cx>,<cy - 80>                   # e.g., deck "LegendVD lifegain" at (229,386) → click (229,306)
sleep 1

# 3. Start match — use OCR to find Play button coords, or use 866,533
arena click 866,533                          # Play button (960-wide coord, auto-scaled)
sleep 5                                      # match load (~3-5s local Forge)

# 4. Handle mulligan — text is "Keep N" (e.g., "Keep 7"). Substring match works.
arena click "Keep" --retry 2 2>/dev/null; true
sleep 2

# 5. In-game: pass to concede
arena click 888,504                          # pass priority
sleep 2

# 6. Concede
arena click 940,42                           # cog icon
sleep 1
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10

# 7. Dismiss result
arena click 210,482 && sleep 2 && arena click 210,482 && sleep 2 && arena click 210,482
arena wait text="Play" --timeout 10          # back in lobby
```

## Sealed Event Flow (Complete Recipe)

### Prerequisites
```bash
just stop; trash data/player.db; just seed-db   # fresh DB — no stale courses
just serve                                       # (in tmux background)
just arena launch
```

**`synthetic_opponent = true` must be set in `leyline.toml`** — without it, Event_EnterPairing queues the player but never finds an opponent. This is the default in the checked-in config.

### Step by step

```bash
# 1. Navigate to Events tab
arena click "Play" --retry 3
sleep 2
arena click "Events" --retry 3
sleep 2

# 2. Find and click sealed event tile
arena ocr --fmt                              # find "Sealed" text and its coords
# Click card ART ~50px above the title text (clickable area is the image, not label)
arena click <cx>,<cy - 50>                   # e.g., "Sealed" at (530,241) → click (530,191)
sleep 2

# 3. Join event
arena click "Start" --retry 3               # joins the event
arena wait text="Open" --timeout 10         # pack opening screen loads

# 4. Open packs — TEXT CLICK HITS WRONG TARGET, use coords
# "Open" text appears in both the header and the button. Use OCR to find the button:
arena ocr --fmt                              # look for "Open" near bottom (y > 450)
arena click <open_button_cx>,<open_button_cy>  # e.g., (479,516)
sleep 4                                      # card reveal animation
arena click "Continue" --retry 3             # → deck builder
arena wait text="Cards" --timeout 10         # "0/40 Cards" confirms builder loaded

# 5. Build deck (add 40 cards)
# Cards in a grid, 2 visible rows. Click to add. Grid shifts as cards are added.
# Row 1: y≈200. Row 2: y≈420. x positions: use OCR cx values from card names.
# Quick approach: batch-click 5 positions per row, check progress, repeat.
arena ocr --fmt                              # see card names and x positions
arena click <x1>,200 && sleep 0.3 && arena click <x2>,200 && sleep 0.3 && ...  # Row 1
arena click <x1>,420 && sleep 0.3 && arena click <x2>,420 && sleep 0.3 && ...  # Row 2
arena ocr --find "40/40 Cards"               # check if done
# Repeat batches until "40/40 Cards" appears (usually 3 batches: 10+10+3=23 clicks)

# 6. Submit deck
arena click "Done" --exact --retry 3         # --exact avoids card text matches
sleep 2
# Event blade shows: "0 LOSSES", "Sealed Deck", "Resign", "Play"

# 7. Start match — DON'T use text click "Play" (matches description text)
arena click 864,516                          # Play button coords (bottom-right)
arena wait text="Keep" --timeout 15          # mulligan screen

# 8. Keep hand
arena click "Keep" --retry 3
sleep 3

# 9. Concede
arena click 940,42                           # cog icon
sleep 1
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10

# 10. Dismiss result
arena click 480,300 && sleep 2 && arena click 480,300 && sleep 2 && arena click 480,300
sleep 3

# 11. Verify loss tracked
arena ocr --find "Loss"                      # "1 Loss" on event blade

# 12. Resign from event
arena click "Resign" --retry 3
sleep 2
# Confirmation dialog: "Cancel" and "OK"
arena ocr --fmt                              # find OK button coords
arena click <ok_cx>,<ok_cy>                  # e.g., (558,327)
sleep 2

# 13. Return to lobby
arena click "Home" --retry 3
arena wait text="Play" --timeout 10          # back in lobby
```

### Sealed-specific gotchas

- **"Open" text click hits header, not button.** The pack opening screen has "Open" in both the description and the button. Use `arena ocr --fmt` to find the button (higher cy value, near bottom) and click by coords.
- **"Play" text click hits description.** Event blade description contains "Play" in body text. Always use coord click `864,516` for the Play button.
- **Deck building: grid shifts.** After adding cards, remaining cards shift left. Click same row positions again — new cards fill in.
- **Result screen dismiss uses (480,300) not (210,482).** Sealed/event result screens differ from bot match — center click works more reliably.
- **Resign confirmation:** Dialog has "Cancel" and "OK" buttons. Use OCR to find "OK" coords — they vary.

## Quick Draft Flow (Complete Recipe)

### Prerequisites
```bash
just build && just serve  # (background, tmux)
just arena launch
# Requires 750+ gems or 5000+ gold in start-hook.json inventory
```

### Step by step

```bash
# 1. Navigate to Quick Draft
arena click "Play" --retry 3
sleep 2
arena ocr --find "Quick Draft"            # get coords of title text
arena click <cx>,<cy - 50>                # click card art above title
sleep 2

# 2. Join (pay entry fee)
arena click 868,485                       # 750 gem button (bottom-right)
sleep 2
arena click "oK" --retry 3               # confirm purchase (OCR reads "oK")
sleep 3                                   # → draft screen loads

# 3. Draft picks — DOUBLE-CLICK to pick (bypasses Confirm Pick button)
# Per pick:
arena ocr --fmt                           # find card names + coords
arena click <cx>,<cy> --double            # double-click any card
sleep 1
arena click 480,50                       # dismiss tooltip overlay (blocks Pack/Pick header)
sleep 1
# Repeat 41 times (14 + 14 + 13 picks). Last card auto-granted.

# 4. Dismiss vault progress overlay (if it appears)
arena click "Okay" --retry 3
sleep 2

# 5. Build deck
arena click "Done" --retry 3              # submit deck → event blade
sleep 2

# 6. Play / concede
arena click "Play" --retry 3
arena wait text="Keep" --timeout 10
arena click "Keep" --retry 3
# ... play or concede as needed
```

### Quick Draft gotchas

- **Double-click to pick.** `arena click <cx>,<cy> --double` (note: `--double` flag, not positional). Bypasses "Confirm Pick" button entirely. On 1x displays, "Confirm Pick" may be clipped off-screen.
- **Dismiss tooltip after each pick.** Double-clicking shows a card tooltip that blocks the "Pack/Pick" header. Click neutral area (`arena click 480,50`) before reading pack progress via OCR.
- **41 server-side picks, not 42.** Pack 0: 14, Pack 1: 14, Pack 2: 13. The 42nd card is auto-granted.
- **Last card in pack has no OCR text.** Tiny thumbnail at (~68,140). Click by position, then double-click.
- **Card grid shifts** as cards are picked. OCR each pack to find current positions.
- **"Unable to finish draft" after last pick is expected.** CmdTypes 621/1908 are stubbed as no-ops (#85). Dismiss error, then: `just stop` + restart server + `arena launch`. Event blade shows "Build Your Deck" on resume.
- **"Build Your Deck" on resume.** After server restart, completed drafts go straight to deckbuilder — no re-draft needed.

## Concede-Fast Recipe

When the goal is just to lose quickly (recording FD traffic, smoke testing):

```bash
arena click 866,533                          # Play (from deck select)
sleep 5                                      # match load
arena click "Keep" --retry 2 2>/dev/null; true
sleep 1
arena click 940,42                           # cog icon
sleep 0.5
arena click "Concede" --retry 3
arena wait text="DEFEAT" --timeout 10
arena click 210,482 && sleep 2 && arena click 210,482 && sleep 2 && arena click 210,482
arena wait text="Play" --timeout 10
```

## Login / Logout

Prefer the explicit field recipe over opaque typing macros.

### Login

```bash
arena activate
arena click 500,355                         # email row center
arena clear-field
arena paste forge@local
arena click 500,385                         # password row center
arena clear-field
arena paste forge
arena click 480,470                         # Log In
```

Notes:
- `arena paste` is more reliable than `arena type` on the Unity login fields.
- `arena clear-field` is best-effort. If stale chars survive, repeat once.
- `arena login --email forge@local --password forge` exists, but the primitive recipe above is easier to debug.

### Logout

```bash
arena click 900,42
sleep 1
arena click "Log Out" --retry 3
```

## Server Setup

### Pre-flight (all must pass before automating)

```bash
curl -s http://localhost:8090/api/state       # returns JSON = server up
lsof -i :30010 | grep LISTEN                  # our Java process listening
lsof -i :30010 | grep ESTABLISHED             # MTGA connected
```

### Starting the server

```bash
tmux new-session -d -s leyline 'cd /Users/denislebedev/src/leyline && just serve'
# Check: tmux capture-pane -t leyline -p | tail -5
# Kill: just stop
```

### Which mode?

- **`just serve`** — local Forge engine. Gameplay bugs, mechanic testing.
- **`just serve-proxy`** — passthrough + recording. Protocol conformance.

### Sealed/Draft/Events in local mode

`synthetic_opponent = true` must be set in `leyline.toml` (checked-in default). Without it, Event_EnterPairing queues the player but never matches — matchmaking hangs forever. **Do not comment out this setting.**

### After mode switch

Kill everything: `just stop` → start new server → `arena launch`. Ghost matches from previous mode break event browser.

### After code changes

**Restart server.** JVM holds old bytecode. `just stop` + rebuild + `just serve`.

### Puzzle reinject

`POST /api/inject-puzzle` hot-swaps the puzzle mid-game without server restart:
```bash
curl -s -X POST http://localhost:8090/api/inject-puzzle \
  --data-binary @puzzles/my-puzzle.pzl -H "Content-Type: text/plain"
```
- Works when `gameOver=false` — resets board, keeps client connected
- Click Pass (888,504) after reinject to sync the phase HUD (#108)
- **Does NOT work after `gameOver=true`** — must restart server + start new match from lobby

## Deck Selection

- "My Decks" may be collapsed → `arena click "My Decks"` to expand
- Click deck **card art** (~80px above the name label), not the label itself
- Verify selection: `arena ocr --find "Edit Deck"` appears when deck is highlighted
- Invalid decks show under "Invalid Decks:" with warning triangles

### Deck Manager Toolbar (bottom bar)

Reference image: `decks-toolbar.png` in this skill directory.

Between "Collection" (left) and "Edit Deck" (right), 6 icon buttons in 960-space (y=518 for all):

| Button | x (960) | Notes |
|--------|---------|-------|
| Deck Details | 206 | Opens deck stats/details panel |
| Favorite | 246 | Toggle favorite star |
| Import | 282 | Import deck from clipboard |
| Export | 322 | Export deck to clipboard |
| Clone | 360 | Duplicate the selected deck |
| | | *visual gap* |
| Delete | 424 | Delete selected deck (trash icon) |

First 5 icons are tightly spaced (~40px apart). Delete is separated by a gap (~64px after Clone).

**Import flow:** copy deck text to clipboard → navigate to Decks tab → click Import icon (282,518). Client parses clipboard and sends `Deck_UpsertDeckV2` with `ActionType: Imported`.

**Delete flow:** select a deck (click thumbnail) → click Delete (424,518) → confirm dialog.

None of these icons have OCR-readable text — use coord clicks only.

## Wait Timeouts

- **OCR waits:** `arena wait text="X" --timeout 10` max. If longer needed, poll manually:
  ```bash
  for i in 1 2 3 4 5; do arena ocr --find "X" 2>/dev/null && break; sleep 3; done
  ```
- **Scene waits:** `arena wait scene=InGame --timeout 15` — reads Player.log, faster than OCR
- **Match load:** `sleep 5` (local Forge) or `sleep 10` (proxy mode)

## Recovery

| Symptom | Action |
|---------|--------|
| Unknown screen | `arena where` → if recognized, continue. If not, `arena navigate Home` |
| Modal/popup blocking | `arena click 480,300` (center dismiss) or `arena click "Okay" --retry 3` |
| Stuck in-game (888,504 doesn't advance) | `arena board` → check actions. May need targeting or modal response |
| "Waiting for Server..." | Server down — restart server, relaunch MTGA |
| Login screen after server restart | Use the explicit login recipe: click email row, `clear-field`, `paste forge@local`, click password row, `clear-field`, `paste forge`, click `480,470` |
| Black screen | Check `logs/leyline.log` — don't click blindly |
| Ghost match ("Resume" but no match) | `just stop` + restart + `arena launch` |
| Wrong coords (clicks miss) | Check display: `system_profiler SPDisplaysDataType`. Use text clicks only as workaround |

## Screenshots

**Always use `arena capture`** — crops to MTGA window, logical resolution.

```bash
arena capture --out /tmp/screenshot.png                     # default 1280px
arena capture --out /tmp/screenshot.png --resolution 1920   # full-res
```

## Client Error Checking

```bash
arena errors                                 # client errors from Player.log
just scry state --no-cards                   # full client state + errors
curl -s http://localhost:8090/api/state       # server-side state
```

## Key Rules

- **One action at a time.** Run ONE bash command, check output, run next. Never batch arena commands or use loops.
- **OCR first, then click.** When unsure of coords, run `arena ocr --fmt` to discover text positions. Click OCR-discovered coords.
- **Text clicks > coord clicks.** `arena click "Play" --retry 3` adapts to any window size. Coord clicks assume 960-wide.
- **888,504 is universal.** All in-game buttons, same coord. Don't OCR for "Pass" vs "Next" — just click the coord.
- **`--retry 3` during transitions.** Animations delay text rendering.
- **`--exact` for common words.** `arena click "Done" --exact` avoids card text matches.
- **5s sleep for Sparky's turn.** Then resume clicking 888,504.
- **After every automation loop:** `arena issues` to review failures.
