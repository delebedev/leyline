---
name: arena-nav
description: Load before any MTGA UI automation — bot matches, brawl, in-game card playing. Commands, coords, recovery, and dev pitfalls.
---

## When to use me

- Before running ANY `arena-ts` commands
- When an automation flow gets stuck
- When building or debugging arena automation

## First: load current commands

Run `just arena-ts --help` to see all available commands. The help output is the source of truth — this skill documents patterns and pitfalls, not an exhaustive command list.

## Starting Matches

| Command | Purpose |
|---------|---------|
| `arena bot-match [--deck N\|NAME]` | Home → Find Match → Play tab → Bot Match → deck → Play |
| `arena brawl [--deck N\|NAME]` | Home → Find Match → Brawl tab → Standard Brawl → deck → Play |
| `arena concede` | Idempotent — Escape → Concede → dismiss results → Home |
| `arena keep` | Keep hand during mulligan, handle card returns |

### Deck selection

- `--deck 2` — 2nd deck in grid (1-based)
- `--deck 'Mono B'` — find by OCR name match
- No flag — uses first deck in grid

## Signal Priority

| Priority | Source | Use for |
|----------|--------|---------|
| 1 | `arena scene` | Scene from Player.log — fastest. Detects InGame via GRE messages. |
| 2 | `arena turn` | Game state: hand, actions, phase, life. From scry-ts accumulator. |
| 3 | `arena hand` | Hand cards with OCR-detected visual positions. |
| 4 | OCR (internal) | Used by commands internally. Not a standalone command yet. |

## Coordinate System

**All coords are in 960-wide logical space.** Auto-scaled to actual window.

Use named landmarks: `arena click home-cta` not `arena click 866,533`. Run `arena click --help` to see all landmarks.

### Key In-Game Landmarks

| Landmark | Coords | Notes |
|----------|--------|-------|
| `action-btn` | 888,504 | Universal — Pass/Next/End Turn/Resolve/All Attack |
| `game-concede` | 480,344 | Concede button in Options overlay |
| `opponent-face` | 480,85 | Click during targeting |
| `home-cta` | 866,533 | Play button on Home screen |

### Format Tab Landmarks

| Landmark | Coords | Notes |
|----------|--------|-------|
| `fmt-play` | 866,192 | Play format tab |
| `fmt-ranked` | 805,192 | Ranked format tab |
| `fmt-brawl` | 928,192 | Brawl format tab |

## How to Play Cards

1. **`arena land [name]`** — Play a land. OCR-locates in hand, drags to battlefield, verifies.
2. **`arena cast "Card Name"`** — Cast a spell. Same OCR pipeline, drag, verify.
3. **`arena drag <from> <to>`** — Direct drag between coords. Fallback for anything else.
4. **`arena pass [--n N]`** — Click action button. Pass priority, resolve, end turn.

### Gameplay Loop

```
arena turn               # check phase, hand, available actions
arena land               # play a land if available
arena cast "Card Name"   # cast a spell
arena pass               # pass priority
arena pass --n 5         # pass through multiple phases
arena turn               # check what happened
```

### Card Positions

**Card display order ≠ zone order.** MTGA sorts hand by mana cost visually. `arena hand` shows cards in visual left-to-right order with OCR-detected positions.

The OCR pipeline: capture MTGA by window ID → crop bottom 20% + trim sides → upscale 2x → Vision OCR → fuzzy match → arc-adjusted position.

Leftmost cards may be too overlapped for OCR — falls back to gap inference.

### Combat

- **All Attack:** `arena pass` when in DeclareAttack phase (action button says "All Attack")
- **Blocker assignment is click-click, NOT drag.** Click blocker, click attacker.
- After casting a spell, click `arena pass` to Resolve, then again to Pass.

## Turn Cycle

Real server gives priority at relevant phases. Typical turn:

1. `arena pass` through upkeep/draw → MAIN1
2. `arena land` + `arena cast` — play cards
3. `arena pass` → combat → declare attackers → confirm
4. `arena pass` → MAIN2 → more cards if needed
5. `arena pass` → end turn

**`arena turn`** tells you the current phase and available actions. Always check before acting.

## Known Sharp Edges

- **Adventure cards** show adventure name instead of card name in `arena turn`/`arena hand` (scry-ts bug)
- **HTML tags** in card names (`<nobr>`) — leak from Arena DB
- **Cast actions are unfiltered by mana** — `arena turn` lists all legal casts, not just affordable ones
- **Seat number varies** — we're not always seat 1. `gamestate.ts` uses `game.ourSeat` from ConnectResp.
- **Player.log verification lag** — after playing a card, Player.log may take 2-3s to update. `arena land` may falsely report failure.
- **Format list position** — "Bot Match" position in the format sidebar shifts. OCR finds it dynamically.

## Recovery

| Symptom | Action |
|---------|--------|
| Unknown screen | `arena scene` to check, `arena click home-cta` to get home |
| Modal/popup | `arena click center` or `arena click dismiss` |
| Stuck in-game | `arena turn` → check actions → `arena pass` |
| Wrong card played | Drag hit wrong position. Use `arena hand` to verify positions first. |
| Not in game | `arena scene --json` → check `inGame` field |

## Subagent Journaling

When dispatching arena automation to a subagent:

```bash
echo "=== <Task> $(date) ===" > /tmp/<task>-playtest.log
echo "$(date +%H:%M:%S) [step N] what happened" >> /tmp/<task>-playtest.log
```

## For Developers

Architecture, native layer, OCR pipeline details: read `tools/arena-ts/CLAUDE.md` and `tools/arena-ts/README.md`.

Adding new match types: use `startMatch()` from `src/match-flow.ts` — specify format tab + format entry name. See `bot-match.ts` and `brawl.ts` for examples.
