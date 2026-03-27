---
name: recording-inspect
description: Interactive recording inspection — analyze a proxy session, trace cards, compare with Forge scripts, write notes. Human-in-the-loop workflow for understanding what happened in a real game.
---

## What I do

Walk through a proxy recording session interactively: show what happened, let you pick cards to trace, check if Forge can handle them, and capture findings in notes.

## When to use me

- After a proxy recording session ("let's look at last game", "let's analyse today's games")
- Reviewing the recording backlog — sessions don't have to be from today
- When investigating how a specific card/mechanic played out on the real server
- Before implementing a mechanic — understand what the client saw first
- `/recording-inspect` or `/recording-inspect 2026-03-17`

## Process

### 1. Resolve session

Parse the argument as a session hint. Default to latest proxy session with MD frames.

```bash
just tape session list
```

### 2. Read existing notes

Check `recordings/<session>/notes.md` — if it exists, read it and show to user. Previous observations inform what to look at next.

### 3. Generate cards.json

If `cards.json` doesn't exist yet, generate it:

```bash
just tape session cards <session>
```

This extracts all cards from md-frames.jsonl, resolves names + types + oracle text from the Arena card DB, and writes `recordings/<session>/cards.json`. Output shows cards grouped by seat with ability summaries — use this as the card manifest for the session.

### 4. Analysis overview

If `analysis.json` exists, read it. If not, run:

```bash
just tape session analyze <session> --force
```

Present the summary to the user:
- Turns, winner, duration
- Mechanics exercised (with counts)
- Annotation coverage (types seen)
- Interesting moments (if any)
- Cards from cards.json — highlight mechanically interesting ones (non-vanilla, activated/triggered abilities)

### 5. Ask what to explore

Ask the user: **"Which cards or mechanics interest you?"**

Don't guess — wait for direction. The user knows what they played and what felt interesting.

### 6. Forge script check (BEFORE tracing)

**Always read the Forge script before tracing recordings.** The script tells you what abilities exist, what keywords matter, and what the engine supports — so you know what to look for in annotations.

```bash
just card-script "<name>"
```
Read the script. Note:
- What abilities the card has (triggered, activated, static, keywords)
- Mana costs for activated abilities
- Token scripts referenced
- Any complex interactions (replacement effects, conditions)

### 7. Card activity analysis

With the Forge script as context, trace what actually happened using annotations from `md-frames.jsonl`.

**Seat detection:** `md-frames.jsonl` contains frames from both seats interleaved (check `systemSeatIds` field). For AI bot games, seat 1 is typically the human player, but this isn't guaranteed. Ask the user if unclear rather than guessing. When analyzing, filter to one seat's frames to avoid double-counting annotations.

**Key annotation signals for "card did something":**

| Annotation type | What it tells you |
|---|---|
| `ZoneTransfer` (details: `zone_src`, `zone_dest`, `category`) | Card moved — cast (Hand→Stack), resolved (Stack→Battlefield), died (Battlefield→Graveyard), etc. Category values: `CastSpell`, `Resolve`, `PlayLand`, `Draw`, `SBA_Damage`, `SBA_ZeroToughness`, `SBA_UnattachedAura`, `Destroy`, `Sacrifice`, `Discard`, `Return` |
| `AbilityInstanceCreated` (affectorId = source card) | Triggered or activated ability fired |
| `DamageDealt` (affectorId = source, affectedIds = targets) | Combat or spell damage. `id:1`/`id:2` = player objects |
| `ObjectIdChanged` (affectedIds = old, details.new_id = new) | instanceId reallocation. When old and new resolve to **different card names** = real transform (DFC flip). Same name = zone-change bookkeeping realloc. Unknown→named = library card revealed (not a game transform) |
| `ControllerChanged` | Threaten/steal effects (e.g. Claim the Firstborn) |
| `LayeredEffectCreated` | Continuous effect applied (e.g. +X/+X, -X/-X) |
| `ModifiedLife` (details: `life`) | Life gain/loss with source card |
| `Scry` | Scry event |

### 8. Deep trace (if user wants)

For protocol-level card lifecycle:

**a. Find instances:**
```bash
just tape proto find-card "<name>" -s <session>
```
Shows instanceIds + zone transitions.

**b. Full trace:**
```bash
just tape proto trace <instanceId> -s <session>
```
Protocol-level lifecycle. Call out: ObjectIdChanged (realloc/transform), DeclareAttackers/Blockers, SelectTargetsReq, etc.

### 9. Compare Forge vs recording

With both the script and trace data in hand, flag:
- Ability present in recording but missing from Forge script — **can't implement yet**
- Ability in Forge but not exercised in recording — **no conformance data**
- Both present — **ready to wire/verify**

### 10. Write/update notes

At the end, offer to write or update `recordings/<session>/notes.md` with:
- Deck description and opponent (if known)
- Result (winner, turn count)
- Notable plays with instanceIds and gsId references
- Conformance-relevant moments (new annotation types, edge cases, Forge gaps)

## Key principles

- **Human drives, agent digs.** Don't auto-trace every card — ask first.
- **Recording is truth.** When recording and Forge disagree, recording wins.
- **Notes are durable.** Write them — next conversation can pick up where this one left off.
- **Keep it conversational.** This is exploration, not a pipeline. Short answers, ask follow-ups.
- **Filter by seat.** Always filter md-frames.jsonl by `systemSeatIds` to avoid double-counting. Ask user which seat if ambiguous.
