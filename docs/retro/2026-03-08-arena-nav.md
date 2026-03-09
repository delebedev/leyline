# Session: Arena Navigation Robustness

## What was done

### Arena screen state machine (committed)
- `bin/arena_screens.py` — screen graph with BFS pathfinding
- `bin/arena.py` — `detect_screen()`, `cmd_navigate()`, `cmd_where()`, `cmd_scene()`, `cmd_move()`
- Popup dismissal layer (`_try_dismiss_popups()`) — 6 common popups defined
- Re-detect + reroute after each transition step (up to 3 reroutes)
- Synthetic scene from GRE: ConnectResp → "InGame", ResultType_WinLoss → "PostGame"
- `get_current_scene()` reads last 1MB of Player.log (not full file)

### Screen definitions (current)
- **Lobby:** Home, RecentlyPlayed, FindMatch, DeckSelected, Events, EventLanding, EventLobby
- **Sealed:** SealedBoosterOpen (2 phases: pack prompt + rares reveal)
- **Nav bar:** Profile, Decks, Packs, Mastery, Achievements
- **In-game:** InGame, ConcedMenu, Result, EventResult, Mulligan, Reconnecting
- **Other:** DeckBuilder, BannedCards, DraftPick

### Sealed flow (discovered via subagent)
Home → RecentlyPlayed → Events → EventLanding (pre-deck, "Start")
  → SealedBoosterOpen ("Open" → rares → "Continue")
    → DeckBuilder (click ~23 cards, lands auto-fill to 40/40, "Done")
      → EventLobby (post-deck, "Play"/"Resign"/loss counter)
        → InGame → ConcedMenu → EventResult ("[Click to Continue]" at 478,551)
          → EventLobby (+1 loss) → repeat until 3 losses

### Scry improvements (committed)
- Standalone GRE JSON parsing (no header line)
- SceneChange model from Player.log
- Match ID from gameInfo.matchID (not header session ID)
- Scene tracking in GameTracker

## Known issues (not yet fixed)

### 1. Scene staleness after match
After a match ends, scene can be stuck on "InGame" or "PostGame" because the MTGA client doesn't fire `Client.SceneChange` when returning to lobby. 

**Fix needed:** In `detect_screen()`, when scene is "InGame" or "PostGame", do a quick OCR check for lobby nav bar text ("Home", "Profile", "Decks"). If nav bar is present, override scene to "Home". Guard: ensure the nav bar check doesn't false-positive during actual gameplay (game screen shows "ForgePlayer" not "Home" in nav position — check y-coordinate ~57).

### 2. OCR text click ambiguity
`click "Play"` via OCR matches wrong element (e.g., "ForgePlayer" contains "Play"). Action buttons should use fixed coords, not OCR text matching.

**Partially fixed:** RecentlyPlayed→InGame, DeckSelected→InGame, EventLanding→InGame now use `click 866,533`. Other transitions still use OCR text clicks where appropriate (tab names, menu items).

### 3. Play blade tabs not fully tested
RecentlyPlayed, FindMatch, Events are now separate screen states. Tab switching transitions defined but only RecentlyPlayed→InGame tested live. The X button to close blade is at `746,93`.

### 4. Back-navigation gaps
- DeckSelected → Home (close blade)
- FindMatch → RecentlyPlayed (tab switch) 
- DeckBuilder → Home (cancel)
These are defined in transitions but not tested.

## Architecture

### Signal priority for screen detection
1. **GRE messages** (Player.log) — ConnectResp = InGame, ResultType = PostGame
2. **Scene changes** (Player.log `Client.SceneChange`) — lobby navigation
3. **OCR** — discriminates sub-screens sharing same scene
4. **Debug API** (:8090) — Forge engine state, last resort

### detect_screen() flow
1. Parse scene from last 1MB of Player.log (GRE + SceneChange signals)
2. Capture window + OCR
3. Score each screen: OCR anchors (3pts each), require_any (2pts), scene match (1pt bonus)
4. Reject rules filter out false positives
5. Highest score wins; fallback to scene-only if no OCR candidates

### navigate() flow
1. Dismiss popups → detect current screen
2. BFS find path to target
3. For each transition: execute steps → wait for condition
4. If wait fails: dismiss popups → re-detect → re-route (up to 3 reroutes)
5. After each successful transition: dismiss popups

## Key coords
- **Action button (bottom-right):** 866,533 (Play) / 888,504 (in-game Pass/Next/etc)
- **Cog icon:** 940,42
- **Bot match defeat dismiss:** 210,482 (3x with 2s gaps)
- **Event defeat dismiss:** 478,551 ("[Click to Continue]", single click)
- **Play blade X (close):** 746,93
- **Nav bar:** Home(53,57) Profile(108,57) Decks(157,57) Packs(207,57) Store(257,57) Mastery(307,57) Achievements(366,57)

## Files
- `bin/arena.py` — main CLI, detect_screen, navigate, all commands
- `bin/arena_screens.py` — SCREENS, POPUPS, TRANSITIONS, find_path()
- `bin/scry_lib/` — Player.log parser, tracker, models
- `.claude/rules/arena-automation.md` — agent playbook
- `docs/playbooks/event-discovery-playbook.md` — how to use subagents for new event formats
- `docs/arena-screens.yaml` — YAML reference (out of sync with Python — Python is authoritative)
