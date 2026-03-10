# Autoplay Harness — Status

Last updated: 2026-03-10 14:10

## What

Automated harness that spawns Claude agents to play MTGA bot matches against Sparky via `bin/arena` + `bin/scry`. Orchestrator (`bin/record-game`) manages lifecycle, watchdog, metrics, and post-batch analysis.

## Current state

**Working. Agents play 8-11 cards/game across any starter deck. 2 clean completions with concede + retro.**

### Latest batch (v8.2 — scry-first + 2-click Sparky pass + OCR spell targeting)

| Game | Turns | Cards Played | Tool Calls | Cost | Wall Time | Result |
|------|-------|-------------|------------|------|-----------|--------|
| 1 | 11 | 7 (lands + spells) | 27 | n/a | 266s | killed: gsId=97 stuck |
| 2 | 7 | 9 (lands + spells) | 56 | n/a | 606s | killed: timeout |
| 3 | 9 | 8 (3 lands, 5 spells) | 46 | $1.09 | 422s | **clean exit, retro written** |

Game 3 details: Mono-black deck — played 3 Swamps, 2× Hopeless Nightmare, Grim Bauble, Tithing Blade, Mephitic Draught. Dealt 4 damage to Sparky, killed a creature. Clean concede after 5 own turns.

### Progression across prompt versions

| Version | Batches | Games | Turn 5 | Cards/game | Cost/game | Approach |
|---------|---------|-------|--------|------------|-----------|----------|
| v1-v3 (pre-fix) | 1-6 | ~15 | 0% | 0 | n/a | Blind drags at y=530 (missed) |
| v4 (coord fix) | 7-8 | 8 | 100% | ~2 | $0.34 | Blind drags at y=500, fixed x |
| v5-v6 (OCR-guided) | 9-10 | 6 | 83% | ~2.5 | $0.82 | OCR + scry but too complex |
| v7 (actions-first) | 11 | 5 | 100% | 0-6 | n/a | Scry actions, OCR for coords |
| v8 (wrong x est.) | 12 | 3 | 33% | 0-1 | n/a | **Regression**: bad position formula |
| v8.1 (OCR+land heur.) | 13 | 3 | 100% | 1-2 | $0.76 | First clean game! 1 land, no spells |
| v8.2 (2-click fix) | 14 | 3 | 100% | 7-11 | $1.09 | Breakthrough: land x=300, OCR spells, 2-click pass |
| **v9 (arena play)** | **—** | **—** | **—** | **—** | **—** | **`arena play` replaces scan-and-cancel; scry fallback for proxy mode** |

Key breakthrough (v8.2): removing the third 888,504 click from the Sparky pass pattern stopped the agent from accidentally passing its own Main1 phase.

Key change (v9): `arena play "<name>"` replaces the entire scan-and-cancel land loop and OCR-guided spell drag. Uses scry as fallback when debug API unavailable (proxy mode). Eliminates ~15 tool calls/turn for land play. Adds `--to` for targeted spells/auras.

## Architecture

```
bin/record-game              Orchestrator (Python, ~560 LOC)
docs/autoplay/play-game.md   Player agent system prompt (v8.2)
docs/autoplay/analyze-gameplay.md   Analyst agent prompt
bin/scry state               Game state from Player.log
bin/arena click/drag/ocr/wait   UI automation
```

## Scry enhancements (this session)

- `hand` field: array of `{id, name}` for each card in our hand (zone order, not visual order)
- NOTE: zone order ≠ visual order. Arena sorts hands visually by mana cost.
- Removed `x` position estimates — they were wrong and caused v8 regression.

## Bugs fixed (total: 8 prior + 3 new)

Prior: y=530→500, scry parser regex, game_over, action unwrapping, actions in to_dict, stale gsId, NoneType turn, agent skills loading.

New this session:
9. **Triple-click overshoot** — Sparky pass pattern `887,491 && 890,510 && 888,504` passed through own Main1. Fixed: use only 2 clicks, then scry.
10. **Position estimation regression** — Added hand x estimates based on zone order, but zone order ≠ visual order. Removed estimates, use OCR+heuristic instead.
11. **Mastery Pass modal** — Added Home nav click to orchestrator cleanup.

## What works

- Lobby navigation (5-6 calls, reliable)
- Land play via blind drag x=300,y=500 (90%+ success)
- Spell play via OCR name→coord drag (works for named spells)
- Scry-first decision loop (actions array → land first, cheapest spell)
- 2-click Sparky pass without overshooting own turn
- Discard detection + handling (Phase_Ending, hand > 7)
- Combat pass (889,504 double-click)
- Concede + dismiss + retro flow
- Deck-agnostic play (mono-white, mono-black, Boros, BW all tested)
- Video recording per game
- Post-batch analyst reports

## What doesn't work yet

1. **Land drag x=200 always misses** — x=300 works. Updated in v8.3.
2. **Some games stuck at gsId** — unclear why; possibly server-side blocking prompt
3. **Cost/tokens null for killed games** — stream-json doesn't emit result on SIGTERM
4. **No combat attacks** — creatures enter BF but agent never declares attackers
5. **No targeting spells** — auras/enchantments skipped (correct fallback)
6. **OCR misses some card names** — small text, overlapping cards
7. **Mastery Pass modal** still appears between games (mitigated in cleanup)

## Next steps (prioritized)

1. **Validate v8.3** (land x=300 first try) with 3-5 games
2. **Add combat attack step** — when creatures on BF, click "All Attack" at 889,504
3. **Reduce stuck timeout** from 90s → 30s for drag failures
4. **Randomize deck selection** — OCR deck list, pick different decks
5. **Handle targeting** — for simple targeted spells (e.g., burn spells), click opponent face
