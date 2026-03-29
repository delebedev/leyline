---
name: arena-nav
description: Load before any MTGA UI automation — bot matches, puzzles, sealed events, draft, or in-game card playing. Commands, coords, recovery, and dev pitfalls.
---

## When to use me

- Before running ANY `arena` commands
- When an automation flow gets stuck
- When building or debugging compound arena commands

## First: load current commands

Run `arena --help` to see all available commands with descriptions. The output is the source of truth — this skill documents patterns and pitfalls, not an exhaustive command list.

## Compound Commands

These block internally and return JSON. The agent should never poll or retry externally.

| Command | Purpose | Returns |
|---------|---------|---------|
| `arena start-bot-match [--deck NAME\|N]` | From anywhere → in-game bot match. Random deck by default. | `{"ok": true, "screen": "InGame", "deck": "..."}` |
| `arena start-puzzle <file.pzl>` | From anywhere → in-game with puzzle loaded (Sparky's Challenge). Hot-swaps if already in-game. | `{"ok": true, "screen": "InGame", "puzzle": "..."}` |
| `arena concede` | Idempotent — from any screen, ensure not in a match. Lands at natural resting place (Home or EventLobby). | `{"ok": true, "screen": "Home"}` or `{"ok": true, "screen": "...", "noop": true}` |

### Click flags

- `--retry N` — recapture + re-OCR up to N times (for animations)
- `--exact` — whole-word OCR match (avoids card text collisions)
- `--bottom` — when multiple OCR matches, pick lowest on screen (fixes "Play" in event descriptions vs Play button)
- `--double` — double-click (for draft picks)

## Signal Priority

| Priority | Source | Use for |
|----------|--------|---------|
| 1 | `arena scene` | Lobby screen from Player.log — fastest, no OCR |
| 2 | `arena ocr` | Visual text + coords (~0 tokens) |
| 3 | `arena where` | Combined scene + OCR screen detection |
| 4 | Debug API (`:8090`) | Engine state — forge-only, not available in proxy |

**Never use screenshots for state detection** (~800 tokens). `arena ocr` costs ~0.

## Coordinate System

**All coords are in 960-wide logical space** (MTGA macOS logical on 2x Retina: 1920 render / 2).

- `arena ocr` returns coords in this space
- `arena click <x>,<y>` expects coords in this space
- Auto-scales on 1x displays

### In-Game Coords

| Element | Coords | Notes |
|---------|--------|-------|
| Action button (Pass/Next/End Turn/All Attack) | 888,504 | Universal — all labels, same position |
| Cog/Settings icon (in-game) | 940,42 | Opens Concede menu |
| Opponent portrait (targeting) | 480,85 | Click during targeting |
| Submit (targeting) | 888,489 | Confirm target selection |
| Cancel (targeting/kicker) | 888,456 | Back out / decline cost |
| Kicker choice (right card) | 560,250 | "Choose One" modal |

## How to Play Cards

1. **`arena play "Card Name"`** — Best. Verified drag, retries with jitter, confirms zone change.
2. **`arena drag <from> <to>`** — Fallback. Cards in hand at y~530, x spans 350-620.

### Patterns

- **Lands:** `arena play "Plains"` — plays instantly
- **Spells:** Same, gets "Pay" prompt if mana available
- **Targeting:** Click target, then Submit at 888,489
- **Optional costs:** "Choose One" modal — left=normal, right=with cost (~560,250)
- **Modal choices (scry, surveil):** Click option card, then "Done"

### Combat

- **Blocker assignment is click-click, NOT drag.** Click blocker, click attacker.
- **15s bridge timeout** on DeclareAttackers/Blockers — script fast, don't OCR between clicks.
- **All Attack:** 888,504 when "All Attack" prompt shows.

## Forge Engine Quirks

Read `forge-quirks.md` in this skill folder for Forge-vs-real-Arena differences: extra priority stops, Resolve clicks, phase skipping, cards to avoid, display issues.

Key point: after `arena play`, spells need 888,504 (Resolve) then 888,504 (Pass). Lands just need Pass.

## Sealed & Draft Events

Read `event-gotchas.md` in this skill folder for sealed/draft-specific pitfalls: OCR text collisions ("Open", "Play"), deck building grid shifts, draft pick mechanics, ghost courses.

## Turn Cycle

Forge gives priority at every phase — expect 5-10 clicks per turn.

**888,504 is universal.** Pass, Next, End Turn, Resolve, All Attack — all same coord.

1. Click 888,504 through upkeep/draw → MAIN1
2. Play cards
3. Click 888,504 → combat → All Attack → confirm
4. MAIN2 → more cards
5. Click 888,504 → end turn → 5s sleep for Sparky

**Stuck?** `arena board` — check for pending actions.

## Server

- **`just serve`** — local Forge. `just serve-proxy` — passthrough + recording.
- **Pre-flight:** `arena health` (checks server, port, window, OCR, display)
- **After code changes:** `just stop` + rebuild + `just serve` (JVM holds old bytecode)
- **After mode switch:** `just stop` + restart + `arena launch` (ghost matches)
- **`synthetic_opponent = true`** in `leyline.toml` for events (default, don't remove)

### Puzzle reinject

`arena start-puzzle <file.pzl>` handles this. Manual alternative:
```bash
curl -s -X POST http://localhost:8090/api/inject-puzzle \
  --data-binary @puzzles/my-puzzle.pzl -H "Content-Type: text/plain"
```
Click Pass (888,504) after reinject to sync phase HUD. Does NOT work after `gameOver=true`.

## Login / Logout

```bash
# Login
arena activate
arena click 500,355                         # email field
arena clear-field && arena paste forge@local
arena click 500,385                         # password field
arena clear-field && arena paste forge
arena click 480,470                         # Log In

# Logout
arena click 900,42 && sleep 1 && arena click "Log Out" --retry 3
```

## Recovery

| Symptom | Action |
|---------|--------|
| Unknown screen | `arena where` → `arena navigate Home` |
| Modal/popup | `arena click 480,300` or `arena click "Okay" --retry 3` |
| Stuck in-game | `arena board` → check actions |
| "Waiting for Server..." | Restart server, relaunch MTGA |
| Ghost match | `just stop` + restart + `arena launch` |
| Wrong coords | `system_profiler SPDisplaysDataType` — check display scale |

## Subagent Journaling

When dispatching arena automation to a subagent, have it journal:

```bash
echo "=== <Task> $(date) ===" > /tmp/<task>-playtest.log
echo "$(date +%H:%M:%S) [step N] what happened" >> /tmp/<task>-playtest.log
```

After every automation loop: `arena issues` to review failures.

## Building Compound Commands

Pitfalls learned from building `concede`, `start-bot-match`, `start-puzzle`:

### OCR Disambiguation

- **"Play" appears everywhere** — event descriptions, card text, the actual button. Use `--bottom` to pick the lowest match (the button is always bottom-right).
- **"Start" matches "starter decks"** — don't text-match Start. Check enrollment via "Resign" presence, click Start button by coord.
- **"Bot Match" appears as header AND sidebar** — `--bottom` picks the sidebar item.
- **"Keep" in "Keep playing until..."** — event descriptions contain mulligan keywords. Scene-first detection prevents false Mulligan detection.
- **"Play" in "ForgePlayer"** — substring match `"play" in "forgeplayer"` is true. Word-boundary matching in `detect_screen` fixes this.

### Screen Detection

- **Scene-first:** Unambiguous scenes (EventLanding, DeckBuilder, Profile, InGame, etc.) skip OCR entirely. Built into `detect_screen()`.
- **Shared scenes:** EventLanding and EventLobby both use `scene: "EventLanding"`. Disambiguate with OCR (Resign presence = EventLobby).
- **`detect_screen` vs `scene`:** If `detect_screen` returns wrong screen but `scene` is right, the OCR is matching a false positive. Check word boundaries and reject rules in `screens_data.py`.

### Timing

- **Defeat screen animation:** 3s wait before clicks register. Don't fire dismiss clicks immediately.
- **Poll loop pattern:** Click → sleep 3s → check `scene=Home` → repeat up to 5x. Better than blind `sleep 2` × 3 clicks.
- **VS screen:** ~5-10s load time between Play click and InGame scene.

### Deck Selection

- Click deck **card art** (~80px above the OCR name label). Clicking the label text doesn't select.
- Filter OCR results: exclude "Edit Deck", "My Decks", "Double-click to Edit" from deck name candidates.
- Verify selection: poll for "Edit Deck" text after clicking.

### Tool Internals

For architecture, Swift binaries, adding commands/screens: read `tools/arena/README.md`.

### Testing Workflow

1. `arena capture --out /tmp/test.png --resolution 960` — see what's on screen
2. Read the image to verify clicks landed correctly
3. `arena ocr --fmt` — check all text positions
4. `arena ocr --find "X"` — check what OCR returns for specific text
5. Don't guess coords — verify from OCR output first
