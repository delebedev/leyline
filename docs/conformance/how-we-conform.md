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
- Auto-derived affectorId rules (93 invariants computed from recording data)

### 4. Grow the catalog

When you find a new bug, ask: "what structural rule would have caught this?" Add it to `RelationshipCatalog.kt`. Run `just segment-relationships` to confirm it holds against recordings. The next engineer won't hit the same bug.

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
