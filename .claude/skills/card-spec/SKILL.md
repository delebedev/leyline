---
name: card-spec
description: Build a card spec — Forge script decomposition, mechanic catalog mapping, trace from Player.log games, gap analysis for leyline implementation
---

## What I do

Build a complete card spec for a single MTG card, documenting everything needed to make it work in leyline. The spec is the unit of work for implementing card support.

## When to use me

- "write a card spec for Drake Hatcher"
- "spec out Kiora"
- "what do we need to make Think Twice work"
- Before implementing a new card or mechanic

## Input

- Card name (required)
- Game reference (optional — which saved game to trace, e.g. `2026-03-30_20-33`)

## Before you start

1. Check available tooling:
```bash
just scry-ts --help
```

2. See what saved games exist:
```bash
just scry-ts game list --saved
```

3. Find which game has the card:
```bash
just scry-ts game search "<name>"
```

## Process

### 1. Identity

Look up the card:

```bash
just card-grp "<name>"           # → grpId
just card-script "<name>"        # → Forge script path
just scry-ts ability <grpId>     # → ability text from Arena DB (if ability ID known)
```

Read the Forge script. Extract: name, grpId, set, type, costs (including alternate costs like flashback/kicker).

### 2. Mechanics decomposition

From the Forge script, list every mechanic the card exercises. Map each to catalog status:

```bash
cat docs/catalog.yaml
```

Use the Forge DSL keywords:
- `K:` = keyword (Flashback, Prowess, Vigilance, etc.)
- `T:` = triggered ability (ETB, attack trigger, damage trigger)
- `A:` = activated ability (tap, sacrifice, remove counters)
- `SVar:` = sub-abilities chained from triggers/activated
- `DB$` = effect type (Draw, Discard, Token, PutCounter, Destroy, etc.)

For each mechanic, check if the Forge engine fires a corresponding game event:

```bash
grep -rn "class GameEvent" forge/forge-game/src/main/java/forge/game/event/ --include="*.java"
```

The mechanics table has **4 required columns**:

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|

### 3. What it does

Plain English summary of the card's behavior from the game rules perspective. One numbered line per mode/ability.

**IMPORTANT: This section must be game-rules generic.** No session-specific details. Write as if explaining the card to someone who hasn't seen a game.

### 4. Trace

Find the card in saved games and trace its lifecycle:

```bash
# Trace card through a game — shows zone transfers, annotations, ID changes
just scry-ts trace "<name>" --game <ref>

# Filter to a specific moment
just scry-ts trace "<name>" --game <ref> --gsid <N>

# Raw annotation JSON for deep inspection
just scry-ts trace "<name>" --game <ref> --gsid <N> --json

# See the board at any point
just scry-ts board --game <ref> --gsid <N>

# Look at specific GSMs
just scry-ts gsm show <gsId> --game <ref>
just scry-ts gsm show <gsId> --game <ref> --json

# Find GSMs with specific annotation types
just scry-ts gsm list --has <AnnotationType> --game <ref> --view annotations

# Resolve ability IDs encountered in trace
just scry-ts ability <abilityGrpId>

# Card manifest for game context
just scry-ts game cards <ref>

# Check game notes (may have human observations)
just scry-ts game notes <ref>
```

Build a zone transition table from the trace output: gsId, instanceId changes, zones, what happened.

**If a mechanic was NOT exercised** (e.g. kicker not paid, threshold not reached, transform didn't happen), say so explicitly. Don't invent data — flag it as "unobserved, needs dedicated game/puzzle."

### 5. Annotations

Use `trace --json` and `gsm show --json` to decode raw annotations for **novel or gap-filling** moments only.

**Budget: inspect at most 3 GSMs in full JSON.** Pick the most interesting moments.

**Only document annotations that are:**
- **New** — annotation type not previously documented
- **Different** — known annotation with unexpected field values
- **Gap-filling** — confirms or corrects assumptions about missing mechanics

**Skip entirely** if a mechanic's catalog status is "wired" or "works."

Focus on: ZoneTransfer categories, counter types, persistent annotations, transform mechanics, any SelectNReq/GroupReq shapes, ShouldntPlay reasons, OptionalActionMessage patterns.

### 6. Key findings

Bullet list of what the trace revealed. Corrections to prior assumptions go here. Keep it to genuinely surprising or new discoveries.

### 7. Gaps for leyline

Numbered list of what's missing or broken. Each gap should be actionable — what code needs to change.

### 8. Tooling feedback

**This section is required.** After completing the spec, document your experience with the tooling:

1. **Commands used** — which scry-ts commands you ran, and whether they worked
2. **What was missing or awkward** — friction points, workarounds needed
3. **Wish list** — features that would have made the investigation faster
4. **Upvote existing wishes** — review these known requests from other agents and note which would have helped you:
   - Card name resolution in trace output (implemented — did it help?)
   - `scry ability <id>` for abilityGrpId lookup (implemented — did it help?)
   - Opponent zone labeling (ours/theirs vs seat 1/seat 2)
   - GRE non-GSM message capture (OptionalActionMessage, ChoiceReq)
   - `gsm diff` (compare two GSMs)
   - `trace --filter` by annotation type

This feedback drives tool development. Be specific about what cost you time.

### 9. Supporting evidence needed

**Cross-references only.** Other cards exercising the same mechanics, puzzles to write.

## Output

Write the spec to `docs/card-specs/<card-name-slug>.md`.

## Template

```markdown
# <Card Name> — Card Spec

## Identity

- **Name:** <name>
- **grpId:** <id>
- **Set:** <set code>
- **Type:** <card type>
- **Cost:** <mana cost> (alternate costs if any)
- **Forge script:** `forge/forge-gui/res/cardsfolder/<path>.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| ... | ... | `GameEvent...` or **none** | ... |

## What it does

1. ...

## Trace (game <ref>, seat <N>)

<context — how many times cast, by whom>

### <Event 1>

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| ... | ... | ... | ... |

### Key findings

- ...

## Gaps for leyline

1. ...

## Tooling feedback

### Commands used
| Command | Worked? | Notes |
|---------|---------|-------|

### Missing/awkward
- ...

### Wish list
- ...

## Supporting evidence needed

- [ ] ...
```

## Rules

- **Specs must be self-contained.** All key findings inline.
- **Only reference stable, committed docs.** Valid: `docs/catalog.yaml`, `docs/rosetta.md`, other `docs/card-specs/`.
- **"What it does" is game rules only.** No wire details.
- **Unobserved mechanics get a banner.** Clear callout after the Mechanics table.
- **Track ID lifecycle.** `scry trace` follows ObjectIdChanged chains automatically.
- **Skip mana payment brackets.** Don't trace ManaPaid sequences unless unusual.
- **Tooling feedback is not optional.** Every spec improves the next agent's workflow.

## Reference

- `just scry-ts --help` — available commands (always check first)
- `docs/card-specs/think-twice.md` — canonical example (lean, focused)
- `docs/rosetta.md` — annotation types, zone IDs, transfer categories
- `docs/catalog.yaml` — mechanic support status
