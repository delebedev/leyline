# How We Conform

> **Migration in progress.** Conformance is moving from proto-based analysis to Player.log-based comparison via `scry-ts`. The card-centric workflow and principles below remain valid. See `docs/conformance/strategy.md` for the current approach.

## Principles

1. **Player.log is the spec.** Arena logs from real server games are the source of truth for building specs.
2. **Player.log is the comparison format.** Both real server and leyline produce Player.logs — compare per-card annotation traces.
3. **Observe variance, don't guess from one sample.** Profile annotation shapes across multiple games.
4. **Structural invariants, not field-by-field equality.** The relationship catalog (`docs/conformance/relationship-catalog.yaml`) encodes protocol rules.

## Quick Reference

| I want to... | Run |
|---|---|
| Trace a card through a game | `just scry-ts trace "Card Name" --game <id>` |
| See annotation types in a game | `just scry-ts gsm list --view annotations --game <id>` |
| See board state at a point in time | `just scry-ts board --game <id> --gsid <N>` |
| Save a game for later analysis | `just scry-ts save` |
| Search saved games for a card | `just scry-ts game search "Card Name"` |
| List saved games | `just scry-ts game list` |
| Resolve an abilityGrpId | `just scry-ts ability <id>` |

## Workflow: Implementing a New Mechanic

### 1. Observe

Before writing any code, understand what the real server does:

```bash
# What categories exist in your recordings?
just segment-variance

# Detailed profile for the mechanic you're implementing
just segment-variance CastSpell

# Full proto decode of a specific message
just conform-proto SearchReq 2026-03-21_22-05-00 --seat 0
```

The variance report tells you:
- Which annotations are always/sometimes present
- Which fields are constant vs variable
- Co-occurrence patterns between annotation types
- affectorId rules (auto-derived from data)

### 2. Implement

Write the code based on what the variance report says, not on one recording sample.

Key inputs from the observatory:
- **CONSTANT fields** → hardcode the value
- **ENUM fields** → use the observed value set
- **ID fields** → wire instance ID resolution
- **Always-present annotations** → must produce them
- **Sometimes annotations** → produce them when the condition applies

### 3. Verify

After implementation, capture engine output and validate:

```bash
# Run a puzzle that exercises the mechanic
./gradlew :matchdoor:testIntegration -Pkotest.filter.specs=".*EngineRelationshipTest"

# Validate relationships against engine output
just segment-relationships --engine matchdoor/build/conformance/engine-multi/

# Profile-aware diff against a specific recording
just conform-proto <Type> <session> --engine <dir> --profile
```

The relationship validator checks:
- Hand-written catalog (structural rules like "CastSpell always has ObjectIdChanged")
- Auto-derived affectorId rules (computed fresh from available recordings — count scales with data)

### 4. Grow the catalog

When you find a new bug, ask: "what structural rule would have caught this?" Add it to `RelationshipCatalog.kt`. Run `just segment-relationships` to confirm it holds against recordings. The next engineer won't hit the same bug.

## Card-Centric Workflow

The mechanic-centric workflow above works bottom-up: pick a protocol segment, observe variance, implement. The card-centric workflow works top-down: pick a card, decompose it into mechanics, trace it on the wire, find gaps, then group gaps into horizontal work.

### 1. Record — play diverse games

Build mechanic-dense decks (`just deck coverage <SETS>`) and play proxy sessions. The goal is mechanic variety per game, not wins. Every card resolved = data point.

### 2. Spec — one card at a time

Use the `card-spec` skill. For each card: read the Forge script, decompose into mechanics, trace zone transitions and annotations from recordings, identify what's missing in leyline.

Specs live in `docs/card-specs/<card>.md`. Each is self-contained: identity, mechanics table (with Forge event + catalog status), plain-English behavior, trace, annotations, gaps, supporting evidence.

### 3. Synthesize — slice horizontally

After a batch of card specs, read all gaps across specs and group by shared infrastructure:

| Horizontal layer | Example | Cards unblocked |
|-----------------|---------|-----------------|
| Counter type mapper | incubation=200, landmark=127, bore=182 | Drake Hatcher, Treasure Map, Brass's Tunnel-Grinder |
| Token grpId registry | Clue=89236, Hero=96212, Drake=94163 | Novice Inspector, Black Mage's Rod, Drake Hatcher |
| Transform (grpId mutation) | silent in-place swap, no annotation | Treasure Map, Brass's Tunnel-Grinder, all DFCs |
| Cast-from-non-hand | GY→Stack, Exile→Stack | Think Twice, Ratcatcher Trainee, Cauldron Familiar |
| AbilityWordActive | threshold, descended | Kiora, Brass's Tunnel-Grinder |

Each horizontal layer becomes a focused PR with unit tests. Cross-link back to the card specs that need it.

### 4. Implement — horizontal layers first, puzzles as mechanic gates

```
horizontal PR (unit-tested)     puzzle (integration gate)
─────────────────────────────   ──────────────────────────────
counter type mapper             Drake Hatcher puzzle
token grpId registry            Novice Inspector puzzle
transform handler               Treasure Map puzzle
cast-from-GY action             Think Twice puzzle
```

Each horizontal PR is small, testable, independently mergeable.

**Puzzles test mechanic combos, not individual cards.** A card is the vehicle — the simplest card that exercises the mechanic is the best puzzle candidate. 20K cards exist but only ~50-100 distinct mechanic combos matter. Card specs identify which combos need testing; puzzles prove the combo works.

Most cards share mechanics with others and don't need their own puzzle. Example: one flashback puzzle (Think Twice) covers all flashback cards. One ETB-loot puzzle (Kiora) covers loot + threshold together.

**Exception: complex lifecycles.** Mechanics with multi-step state machines may need multiple puzzles for each phase. Adventure needs 2-3: cast adventure side only, cast creature side only, full loop (adventure → exile → creature from exile). Each phase can break independently.

A puzzle failing tells you which horizontal layer has a gap.

### 5. Close the loop

After a card works in a puzzle, playtest it via `just serve` + Arena. Record the session. Compare the leyline output against the real-server trace in the card spec. Differences = conformance bugs → new gaps → next iteration.

Update the card spec with "verified" status and link to the PR that made it work.

## Tool Architecture

```
Player.log (arena or leyline)
        │
        ▼
scry-ts parser                (JSON, lossless for S→C)
        │
        ├─→ scry-ts trace          (per-card annotation journey)
        │
        ├─→ scry-ts gsm            (GSM queries, diffs, annotation views)
        │
        ├─→ scry-ts board          (accumulated board state at any point)
        │
        ├─→ scry-ts prompts        (server request audit)
        │
        └─→ scry-ts game           (catalog, search, card manifest)
```

Legacy proto-based tooling (`conform-proto`, `segment-variance`, `segment-relationships`) has been retired from this repo.

## Key files

| File | What |
|------|------|
| `tools/scry-ts/` | Player.log analysis CLI (Bun TypeScript) |
| `docs/conformance/relationship-catalog.yaml` | 40 structural invariants (extracted from Kotlin) |
| `docs/rosetta.md` | Annotation types, zone IDs, transfer categories |
| `docs/catalog.yaml` | Mechanic implementation status |

## Related docs

- `conformance/debugging.md` — annotation ordering, instanceId lifecycle, detail key types
- `conformance/protocol-findings.md` — durable protocol facts (multi-type annotations, ID ranges, patterns)
- `conformance/levers.md` — architectural analysis of conformance gaps
- `rosetta.md` — annotation types, zone IDs, transfer categories, protocol reference
