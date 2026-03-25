---
name: build-deck
description: Build mechanic-dense decks from your Arena collection for recording sessions — maximizes mechanic variety per game
---

## What I do

Build 60-card decks optimized for maximum mechanic coverage during recording sessions. Scores cards by mechanic density (flashback, transform, saga, tiered, job_select, landfall, threshold, etc.), filters by your real Arena collection, and outputs an Arena-importable deck list.

## When to use me

- "build a recording deck" / "build a deck for recording"
- "what mechanics can I test with my collection"
- "make a deck from FDN and FIN"
- "deck with max mechanic variety"
- Before proxy recording sessions when you want diverse data

## How it works

1. **Collection**: auto-loads from latest proxy recording (`fd-frames.jsonl` CmdType 551 response). Falls back to Player.log or `--no-collection` for all cards.
2. **Scoring**: each card scored by mechanics it carries. Rare/missing mechanics in leyline (flashback, transform, saga, tiered) weight higher.
3. **Deck building**: greedy — picks highest-scored cards first, reduces copies once a mechanic is covered, fills to 60.

## Commands

```bash
# Build deck from specific sets, filtered by collection
just deck coverage FDN FIN

# Skip collection filtering (use all cards)
just deck coverage FDN FIN --no-collection

# Custom deck size / land count
just deck coverage FDN FIN --size 40 --lands 17

# Explicit collection source
just deck coverage FDN --fd-frames recordings/2026-03-25/capture/fd-frames.jsonl

# Show collection stats
just deck collection
```

## Mechanic detection

Three methods against Arena's SQLite card DB:

| Method | Example | How |
|--------|---------|-----|
| Direct ability ID | flying=8, prowess=137 | Card's AbilityIds contains the ID |
| BaseId chain | flashback: "Flashback {2R}" → BaseId=35 | Ability's BaseId matches keyword |
| AbilityWord enum | landfall=21, threshold=33 | Ability's AbilityWord field |
| Structural | transform, token_maker, modal | LinkedFaceGrpIds, token links, ModalChildIds |

## Weight table (higher = rarer in leyline, more valuable to record)

| Mechanic | Weight | Why |
|----------|--------|-----|
| flashback | 5.0 | Missing — graveyard casting not wired |
| transform | 5.0 | Missing — no TDFC support |
| threshold | 4.0 | Missing — AbilityWordActive annotation gap |
| tiered | 4.0 | Untested modal cost model |
| job_select | 4.0 | Untested equipment ETB + token combo |
| saga | 4.0 | Untested card type |
| kicker | 3.0 | Wired but needs more coverage |
| landfall | 2.0 | Wired, good validation data |
| raid | 2.0 | Wired, good validation data |
| convoke | 2.0 | Partial |
| token_maker | 2.0 | Wired, diverse annotation shapes |
| modal | 2.0 | Wired, CastingTimeOptionsReq |
| prowess | 1.5 | Works, low priority |

## Output

Prints:
1. Per-set top cards with scores and owned copies
2. Deck mechanic summary (what's covered, how many cards)
3. Arena-importable deck list (copy-paste into Arena deck builder)
