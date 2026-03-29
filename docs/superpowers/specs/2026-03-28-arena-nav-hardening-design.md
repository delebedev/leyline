# Arena Navigation Hardening

**Goal:** Make agent-driven UI navigation fast and reliable. Two layers: a hardened `goto` primitive, and compound commands that collapse multi-step flows into single calls.

**Non-goals:** In-game card playing, combat, turn management. Match UI is out of scope.

**Constraint:** No debug API (`:8090`) in generic commands. Debug API is forge-only and unavailable in proxy mode. Only `start-puzzle` (explicitly forge-only) may use it.

## Problem

The primitives exist (click, ocr, navigate, bot-match) but composing them is slow and fragile:

1. **OCR ambiguity.** `click "Play"` hits the wrong "Play" when multiple instances exist on screen. Text-based clicks are inherently fuzzy.
2. **Speed.** Agent improvises 6-8 individual arena calls per navigation, each with its own OCR/retry ceremony. Round-trips dominate.
3. **Detection overhead.** `detect_screen()` always runs OCR even when Player.log scene is unambiguous.
4. **Popup stacking.** Popup dismissal runs once, but popups can stack (Daily Deals -> Level Up -> Patch Notes).
5. **No compound commands.** "Get me from anywhere to in-game with this deck" requires agent orchestration every time.

## Design

### Layer 1: Harden `arena navigate`

#### 1.1 Prefer coords over text clicks in transitions

Fixed UI elements (Play button, tab headers, cog icon, dismiss targets) have known coords that don't change. Text clicks should be reserved for dynamic content (deck names, event tile labels).

**Change:** Audit every transition in `screens_data.py`. Replace text-based `click "X"` steps with coord-based `click X,Y` where the target is a fixed UI element. Keep text clicks only for:
- Deck names in grid (dynamic)
- Event tile labels (dynamic)
- Popup button text like "Okay" / "Claim" (position varies by popup)

The coord scaling infrastructure (`window_width / 960`) already handles Retina vs non-Retina. No new scaling work needed.

**Specific replacements:**

| Current | Replacement | Rationale |
|---------|-------------|-----------|
| `click "Play" --retry 3` (Home -> RecentlyPlayed) | `click 866,533` | Fixed lobby Play button, "Play" appears in card text too |
| `click "Find Match" --retry 3` | `click 688,93` | Tab header at fixed position |
| `click "Events" --retry 3` | `click 790,93` | Tab header at fixed position |
| `click "Recently Played" --retry 3` | `click 570,93` | Tab header at fixed position |
| `click "Bot Match" --retry 3` | `click 842,396` | Sidebar queue item, fixed position |
| `click "Concede" --retry 3` (ConcedMenu) | `click 480,390` | Concede button in settings overlay, fixed |
| `click "Home" --retry 3` | `click 53,57` | Home nav icon, fixed |
| `click "Keep" --retry 3` (Mulligan) | `click 580,500` | Keep button, fixed position |

Text clicks retained:
- `click "Build Your Deck"` — position varies by event layout
- `click "Done"` — deck builder, position can shift
- `click "Okay"` — popup dismiss, varies
- `click "Claim"` — reward popup, varies
- `click "Sealed"` — event tile, dynamic grid

#### 1.2 Scene-first detection

`detect_screen()` currently always captures + OCRs. Many screens are unambiguously identified by Player.log scene alone.

**Change:** Add a fast path to `detect_screen()`:

```
scene = get_current_scene()
if scene unambiguously maps to one screen (no other screen shares that scene):
    return that screen immediately (no OCR)
```

Unambiguous scenes (only one screen uses them): `EventLanding`, `DeckBuilder`, `SealedBoosterOpen`, `Profile`, `DeckListViewer`, `BoosterChamber`, `RewardTrack`, `Achievements`, `InGame`.

Ambiguous scenes requiring OCR: `Home` (shared by Home, RecentlyPlayed, FindMatch, DeckSelected, Events, BannedCards). `None` (Mulligan, ConcedMenu, Result, DraftPick, Reconnecting).

This eliminates OCR for ~50% of detection calls.

#### 1.3 Prefer scene-based waits in transitions

Some transitions wait on OCR text (`text="Find Match"`) when a scene change would be faster and more reliable. Player.log scene changes arrive ~instantly after the client transitions.

**Change:** Where a transition crosses a scene boundary, use `scene=` wait instead of `text=` wait. Where it stays within the same scene (e.g., Home -> FindMatch, both `scene: Home`), keep `text=` or use `no-text=` wait as fallback.

Cross-scene transitions to update:
- EventLanding -> DeckBuilder: `wait: scene=DeckBuilder` (instead of `text="Cards"`)
- DeckBuilder -> EventLobby: already uses `scene=EventLanding`
- Any -> Home: already uses `scene=Home` mostly

#### 1.4 Exhaustive popup dismissal

**Change:** Loop `try_dismiss_popups()` until no popup detected (max 5 iterations). Currently runs once.

```python
def dismiss_all_popups(commands, max_rounds=5):
    for _ in range(max_rounds):
        popup = try_dismiss_popups(commands)
        if popup is None:
            break
```

Call at: start of `navigate`, after each transition step, and at arrival.

#### 1.5 Structured JSON output

`arena navigate` currently prints human text. Change to structured JSON:

```json
{"ok": true, "screen": "DeckSelected", "path": ["Home", "RecentlyPlayed", "FindMatch", "DeckSelected"], "popups_dismissed": ["DailyDeals"], "reroutes": 0}
```

On failure:
```json
{"ok": false, "error": "timeout", "screen": "FindMatch", "target": "DeckSelected", "step": "FindMatch -> DeckSelected"}
```

### Layer 2: Compound commands

All compound commands: detect current screen -> shortest path to goal -> execute -> confirm -> return JSON.

#### 2.1 `arena ensure-home`

From any screen -> Home. Recovery primitive.

Logic:
1. `detect_screen()`
2. If Home: return immediately
3. If InGame: concede flow first (cog -> Concede -> dismiss result)
4. Otherwise: `navigate Home`
5. Dismiss all popups
6. Confirm scene=Home

```json
{"ok": true, "screen": "Home", "from": "EventLanding"}
```

#### 2.2 `arena start-bot-match [--deck NAME|N]`

From anywhere -> in-game with priority. Single call replaces the multi-step bot-match flow.

Logic:
1. `ensure-home` if not already in lobby
2. `navigate DeckSelected` (Home -> RecentlyPlayed -> FindMatch -> DeckSelected)
3. Select deck by name (OCR fuzzy match) or index (grid coords)
4. Click Play (866,533)
5. Wait `scene=InGame` (timeout 30s)
6. Handle mulligan if Keep text appears (OCR poll): click Keep (580,500)
7. Wait for scene=InGame to stabilize (timeout 15s) — no debug API
8. Return screen confirmation

```json
{"ok": true, "screen": "InGame", "deck": "Mono Red"}
```

#### 2.3 `arena start-puzzle FILE [--hot-swap]`

Puzzle hot path. Two modes:

**Hot-swap (in-game already):**
1. POST `/api/inject-puzzle` with puzzle file
2. Click Pass (888,504) to resync
3. Return board state from debug API

**Cold start (not in-game):**
1. Set puzzle in config (or verify already set)
2. `start-bot-match` flow (puzzle skips mulligan automatically)
3. Return board state

```json
{"ok": true, "puzzle": "menace-block.pzl", "hot_swap": true, "turn": 1, "hand": [...], "battlefield": [...]}
```

#### 2.4 `arena concede`

From in-game -> Home. One call.

Idempotent — "make sure we're not in a match anymore." Safe to call from any screen.

Logic:
1. `detect_screen()` (scene-based, no debug API)
2. If screen not in (InGame, ConcedMenu, Result, EventResult): no-op, return current screen
3. If InGame: click cog (940,42), wait for ConcedMenu
4. If ConcedMenu: click Concede (480,390), wait for result
5. If Result: dismiss (click 210,482 x3 with 2s gaps), wait scene=Home
6. If EventResult: click through (478,551), land at EventLobby
7. Dismiss all popups at final screen

Stops at natural resting place — Home (bot match) or EventLobby (event). Agent calls `ensure-home` after if it wants Home.

```json
{"ok": true, "result": "DEFEAT", "screen": "Home"}
```

No-op case:
```json
{"ok": true, "screen": "FindMatch", "noop": true}
```

#### 2.5 Pre-flight

`arena health` already exists and covers server, port, window, OCR, and display scale checks. No new command needed. Compound commands should call `health` internally if they detect the environment is broken (e.g., no MTGA window), rather than failing with a cryptic error.

No auto-start. Just validation + clear error message.

## Files to change

| File | Change |
|------|--------|
| `tools/py/src/leyline_tools/arena/screens_data.py` | Replace text clicks with coords in transitions |
| `tools/py/src/leyline_tools/arena/nav.py` | Scene-first detection, exhaustive popup loop, JSON output |
| `tools/py/src/leyline_tools/arena/cli.py` | Register new compound commands |
| `tools/py/src/leyline_tools/arena/flows.py` | **New.** Compound command implementations (ensure-home, start-bot-match, start-puzzle, concede, ensure-server) |
| `tools/py/src/leyline_tools/arena/bot_match.py` | Deprecate in favor of `start-bot-match`. Keep temporarily for backwards compat. |
| `docs/arena-screens.yaml` | Update transitions to match screens_data.py changes |
| `.claude/skills/arena-nav/SKILL.md` | **Slim down.** Remove lobby recipes, step-by-step flows, and fixed-button coord tables (now baked into tool). Keep: command reference, in-game patterns (card playing, combat), recovery escalation. |

## Agent usage pattern (after)

Before:
```
arena where                    # what screen?
arena click "Play" --retry 3   # hope it hits right Play
arena ocr --find "Find Match"  # did it work?
arena click "Find Match"       # next step
# ... 6 more calls ...
```

After:
```
arena start-bot-match --deck "Mono Red"   # one call, returns game state JSON
# ... play the game ...
arena concede                          # one call, back at Home
```

For puzzles:
```
arena start-puzzle menace-block.pzl    # one call, returns board state
# ... play puzzle ...
arena concede
arena start-puzzle next-puzzle.pzl     # can hot-swap if still in-game
```

## Coord verification plan

Before shipping, verify all hardcoded coords against current MTGA build:
1. `arena capture --res 1920` at each screen
2. `arena ocr` to confirm button positions
3. Update any coords that have shifted

This is a one-time check, not ongoing maintenance — MTGA button positions are stable across patches.
