# How We Conform

**The single reference for conformance workflow.** Supersedes command references in `pipeline.md` and `workflow.md` for the match-door protocol layer. Those docs retain architectural context and protocol findings — this doc tells you what to run.

## Principles

1. **Recording is the spec.** Real server recordings are the source of truth.
2. **Raw proto, not decoded JSON.** `md-frames.jsonl` is lossy — use `RecordingFrameLoader` for conformance.
3. **Observe variance, don't guess from one sample.** Run `just segment-variance` to see what's constant vs variable.
4. **Structural invariants, not field-by-field equality.** The relationship catalog encodes the protocol rules. The differ uses variance profiles to compare intelligently.

## Quick Reference

| I want to... | Run |
|---|---|
| See what the real server sends for a message type | `just conform-proto SearchReq <session> --seat 0` |
| See field variance across all recordings for a mechanic | `just segment-variance CastSpell` |
| List all segment categories with instance counts | `just segment-variance` |
| Validate structural invariants against recordings | `just segment-relationships` |
| Validate structural invariants against engine output | `just segment-relationships --engine <dir>` |
| Diff engine output against a recording (exact) | `just conform-proto <Type> <session> --engine <dir>` |
| Diff engine output against a recording (profile-aware) | `just conform-proto <Type> <session> --engine <dir> --profile` |
| Check annotation detail key shapes | `just proto-annotation-variance` |
| Interactive segment exploration (legacy, JSON-based) | `just tape segment list <session>` |

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
Recording .bin files (local)
        │
        ▼
RecordingFrameLoader          (raw proto, lossless)
        │
        ├─→ just conform-proto     (decode + diff single messages)
        │
        ├─→ SegmentDetector        (categorize frames by mechanic)
        │         │
        │         ├─→ FieldVarianceProfiler   (per-field variance)
        │         │         │
        │         │         └─→ just segment-variance  (CLI report)
        │         │
        │         └─→ RelationshipValidator   (invariant checking)
        │                   │
        │                   ├─→ RelationshipCatalog    (hand-written rules)
        │                   ├─→ Auto-derived rules     (affectorId from variance)
        │                   └─→ just segment-relationships (CLI report)
        │
        └─→ ProtoDiffer            (field-by-field diff)
                  │
                  └─→ Profile-aware mode    (uses variance + relationships)
```

## What's still valid from the old pipeline

| Tool | Status | Use for |
|------|--------|---------|
| `segments.py` / `just tape segment` | Still works | Interactive exploration, segment listing, ID tracing |
| `md-frames.jsonl` | Still generated | Quick browsing of session frames |
| `AnnotationVariance.kt` / `just proto-annotation-variance` | Still works | Annotation detail-key variance (separate from the observatory) |
| `AnnotationShapeConformanceTest` | Still in CI | Catches annotation builder regressions |
| `StructuralFingerprint` / `StructuralDiff` | Still works | Shape-level timeline comparison |

**Deprecated (being replaced):**
| Tool | Replaced by |
|------|-------------|
| `AnnotationSerializer.kt` JSON output | `ProtoDiffer` direct proto comparison |
| `just conform` (Python template → diff chain) | `just conform-proto --profile` |
| `ConformancePipelineTest` JSON intermediary | Direct proto diff in tests |
| JSON templates from `just tape segment template` | `just conform-proto` raw proto decode |

## Key files

| File | What |
|------|------|
| `tooling/.../conformance/RecordingFrameLoader.kt` | Loads raw .bin → `List<GREToClientMessage>` |
| `tooling/.../conformance/ProtoDiffer.kt` | Field-by-field diff with ID normalization + profile-aware mode |
| `tooling/.../conformance/SegmentDetector.kt` | Categorize recording frames into segments |
| `tooling/.../conformance/FieldVarianceProfiler.kt` | Per-field variance + auto-derived affectorId rules |
| `tooling/.../conformance/RelationshipValidator.kt` | Validate patterns against segment instances |
| `tooling/.../conformance/RelationshipCatalog.kt` | Hand-written structural invariants (40 patterns) |
| `tooling/.../conformance/Relationship.kt` | Relationship types: Equals, NonEmpty, ValueIn, AlwaysPresent, AffectorIdRule |
| `just/proto.just` | CLI recipes: `conform-proto`, `segment-variance`, `segment-relationships` |

## Related docs

- `conformance/debugging.md` — annotation ordering, instanceId lifecycle, detail key types (still current)
- `conformance/protocol-findings.md` — durable protocol facts (multi-type annotations, ID ranges, patterns)
- `conformance/levers.md` — architectural analysis of conformance gaps (levers #2 and #6 now in progress)
- `annotation-variance.md` — annotation detail-key variance tool (parallel system, not replaced)
- `rosetta.md` — annotation types, zone IDs, transfer categories, protocol reference
