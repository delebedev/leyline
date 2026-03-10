# Autoplay Harness — Status

Last updated: 2026-03-10 09:18

## What

Automated harness that spawns Claude agents to play MTGA bot matches against Sparky via `bin/arena` + `bin/scry`. Orchestrator (`bin/record-game`) manages lifecycle, watchdog, metrics, and post-batch analysis.

## Current state

**Working. 14/16 games reached turn 5+ across 4 batches. Card play improving.**

### Latest batch metrics (batch 10, prompt v6 — OCR-guided play)

| Game | Turns | Cards Played | Tool Calls | Cost | Wall Time | Result |
|------|-------|-------------|------------|------|-----------|--------|
| 1 | 1 | 0 | 11 | n/a | 132s | killed: stuck at gsId=23 |
| 2 | 11 | 2 | 36 | $0.74 | 438s | DEFEAT (Sparky lethal) |
| 3 | 9 | 3 | 79 | $1.72 | 610s | concede (timeout) |

### Progression across prompt versions

| Version | Batches | Games | Turn 5 | Cards/game | Cost/game | Approach |
|---------|---------|-------|--------|------------|-----------|----------|
| v1-v3 (pre-fix) | 1-6 | ~15 | 0% | 0 | n/a | Blind drags at y=530 (missed) |
| v4 (coord fix) | 7-8 | 8 | 100% | ~2 | $0.34 | Blind drags at y=500, fixed x |
| v5 (OCR-guided) | 9 | 3 | 66% | ~1 | n/a | OCR + scry but too complex |
| v6 (OCR tightened) | 10 | 3 | 100% | ~2.5 | $0.82 | OCR once/turn, cancel targets |

Key insight: OCR-guided play produces **better card choices** (lands first, real positions) but costs 2-3x more and agents get stuck on targeting prompts from auras/enchantments.

## Architecture

```
bin/record-game              Orchestrator (Python, ~540 LOC)
docs/autoplay/play-game.md   Player agent system prompt (v6)
docs/autoplay/analyze-gameplay.md   Analyst agent prompt
bin/scry state               Game state from Player.log
bin/arena click/drag/ocr/wait   UI automation
```

## Batch output

All in `docs/retro/autoplay/batch-<timestamp>/`:
- `summary.json` — per-game metrics
- `report.md` — analyst report
- `refinements.md` — actionable improvements
- `run-N/transcript.jsonl` — full agent conversation
- `run-N/retro.md` — agent self-assessment
- `run-N/gameplay.mp4` — screen recording (when `--record-video`)

## Bugs fixed (total: 8)

1. **y=530 → y=500** — hand card Y coord off by 30px (100% miss → 0%)
2. **Scry parser regex** — uppercase match IDs
3. **Scry game_over** — missing property
4. **Scry action unwrapping** — nested dict format
5. **Scry actions in to_dict** — not exposed, added with card name resolution
6. **Orchestrator stale gsId** — Player.log accumulation
7. **Orchestrator NoneType** — turn can be None
8. **Agent skills loading** — suppress via CLI flags

## What works

- Lobby navigation (5 calls, reliable)
- Card play via `arena drag x,500 480,300`
- OCR-based card position detection
- Scry actions list (know what's castable + mana cost)
- Phase advancement via 888,504 clicks
- Discard detection + handling
- Targeting prompt cancellation (auras/enchantments)
- Concede + dismiss flow
- Video recording per game
- Post-batch analyst reports

## What doesn't work yet

1. **Targeting spells** — auras need target selection after drag. Agent cancels them (correct fallback, but wastes the play)
2. **Spell cost awareness** — agent sometimes drags expensive spells it can't pay for
3. **Combat attacks** — creatures enter BF but rarely attack
4. **OCR call volume** — agent uses 8-21 OCR calls/game when 3-5 would suffice
5. **Priority awareness** — blind-clicks during opponent's priority sometimes waste calls
6. **Mastery Pass modal** — undocumented popup blocks lobby

## Next steps (prioritized)

1. **Reduce OCR frequency** — once per own turn start, not after every action
2. **Mana-aware play** — read scry actions manaCost, skip spells agent can't afford
3. **Skip targeted spells** — if scry action is an aura/enchantment, don't attempt
4. **Add combat attack step** — explicit "All Attack" after main phase plays
5. **priority_player gating** — only click buttons when it's our priority
6. **Handle Mastery Pass modal** — detect + dismiss in lobby flow
7. **Randomize deck selection** — OCR deck list, vary per game
