# Conformance Observatory

**Date:** 2026-03-22
**Branch:** `feat/conformance-observatory`

## Goal

Build a systematic, observation-derived model of protocol correctness for our reimplemented local server — one that grows with every recording session and catches conformance bugs before playtesting.

## Problem

Three recurring failure modes:
1. **Invisible fields** — tooling hides fields that matter (SearchReq inner fields)
2. **Single-sample reasoning** — treating one recording as the spec, guessing which fields are required vs incidental
3. **Missing relationships** — fields are present but the wiring between them is wrong

Root cause: no systematic model of what "correct" looks like. Each bug teaches one fact, but those facts aren't accumulated into a reusable model.

The annotation variance system is the exception — it systematically answers "what keys does each annotation type have?" and that answer is durable and catches regressions. Everything else is ad-hoc.

## Architecture

Three pieces, layered. Each independently useful.

### Piece 1: SegmentVarianceProfiler

The core tool. Scans all available recordings, groups frames into segments, computes variance profiles.

**Segment detection** (built into the profiler, not a separate component):

A segment is a single GSM Diff frame containing a ZoneTransfer annotation with a recognized category. For standalone messages (SearchReq, DeclareAttackersReq, CastingTimeOptionsReq), the entire GRE message is the segment.

```kotlin
/** A single observed protocol interaction. */
data class Segment(
    /** Mechanic category: "CastSpell", "PlayLand", or message type: "SearchReq" */
    val category: String,
    /** The GRE message (GSM Diff for mechanics, full message for standalone) */
    val message: GREToClientMessage,
    /** Provenance */
    val session: String,
    val frameIndex: Int,
    val gsId: Int,
)
```

Segment detection reuses `RecordingFrameLoader` for raw proto access. Categorization logic:
- For GSM Diffs: scan annotations for ZoneTransfer, extract `category` detail key
- For non-GSM types (SearchReq, DeclareAttackersReq, CastingTimeOptionsReq, IntermissionReq): use the GRE message type as category

**Variance profiling** — given N segments of the same category:

```kotlin
data class SegmentProfile(
    val category: String,
    val instanceCount: Int,
    val sessionCount: Int,
    val fieldProfiles: Map<String, FieldProfile>,
    val annotationPresence: Map<String, AnnotationPresence>,
)

data class FieldProfile(
    /** Fraction of instances where this field is present (1.0 = always) */
    val frequency: Double,
    /** How the value varies across instances */
    val variance: ValueVariance,
    /** Up to 5 unique values observed */
    val samples: List<Any>,
)

enum class ValueVariance {
    CONSTANT,  // same value in all instances
    ENUM,      // small set of distinct values (≤10)
    ID,        // always different, instance-ID-like
    RANGED,    // numeric, varies within a range
    SIZED,     // list/repeated field with varying length
}

data class AnnotationPresence(
    /** Fraction of segment instances containing this annotation type */
    val frequency: Double,
    val instanceCount: Int,
    /** Annotation types that always co-occur with this one */
    val coOccursWith: Set<String>,
)
```

For each field path in the segment (walked via proto reflection), computes frequency and variance classification. For GSM segments, also computes which annotation types appear always/sometimes and their co-occurrence.

Does NOT re-derive annotation detail keys — that's the existing `AnnotationVariance.kt` system. References it conceptually but doesn't call it.

**Confidence thresholds:**
- N < 3 instances → `TENTATIVE` (shown in output, not used for assertions)
- N ≥ 3 across ≥ 2 sessions → `OBSERVED`
- N ≥ 10 across ≥ 3 sessions → `CONFIDENT`

**Invocation:** `just segment-variance [category] [--session X]`

~300 LOC (segment detection ~80, profiling ~150, CLI + report ~70).

### Piece 2: RelationshipValidator

Validates a hand-written pattern catalog against observed segment instances.

**Relationship types** (V1 — minimal set, extend as needed):

```kotlin
sealed class Relationship(val category: String) {
    /** Field A == Field B within the same segment */
    data class Equals(val cat: String, val a: String, val b: String) : Relationship(cat)
    /** Field/list is present and non-empty */
    data class NonEmpty(val cat: String, val path: String) : Relationship(cat)
    /** Field value is one of expected set */
    data class ValueIn(val cat: String, val path: String, val values: Set<Any>) : Relationship(cat)
    /** Annotation type appears in all instances of this category */
    data class AlwaysPresent(val cat: String, val annotationType: String) : Relationship(cat)
}
```

Four types in V1. `Subset`, `MemberOf`, `CoOccurs`, `SizeRange` deferred — add when patterns demand them. The catalog is Kotlin data, so adding new types is trivial.

**Pattern catalog** — Kotlin object, lives in `tooling/src/main/kotlin/leyline/conformance/RelationshipCatalog.kt`:

```kotlin
object RelationshipCatalog {
    val patterns: List<Relationship> = listOf(
        // CastSpell
        Equals("CastSpell",
            "annotations[ZoneTransfer].affectedIds[0]",
            "annotations[ObjectIdChanged].details.new_id"),
        AlwaysPresent("CastSpell", "ObjectIdChanged"),
        AlwaysPresent("CastSpell", "ZoneTransfer"),
        ValueIn("CastSpell", "annotations[ZoneTransfer].details.zone_dest", setOf(27)),
        NonEmpty("CastSpell", "gameObjects"),

        // SearchReq
        NonEmpty("SearchReq", "searchReq.itemsToSearch"),
        NonEmpty("SearchReq", "searchReq.itemsSought"),
        NonEmpty("SearchReq", "searchReq.zonesToSearch"),

        // PlayLand
        AlwaysPresent("PlayLand", "ObjectIdChanged"),
        AlwaysPresent("PlayLand", "ZoneTransfer"),
        ValueIn("PlayLand", "annotations[ZoneTransfer].details.zone_dest", setOf(28)),

        // ... ~15-20 initial patterns
    )
}
```

**Validator** — checks each pattern against all instances of that category from recordings:

```kotlin
data class ValidationResult(
    val pattern: Relationship,
    val holds: Int,      // instances where pattern held
    val total: Int,      // total instances checked
    val exceptions: List<Exception>,  // instances where it didn't hold
) {
    val confidence get() = holds.toDouble() / total
    val status get() = when {
        holds == total -> Status.HOLDS
        confidence >= 0.95 -> Status.MOSTLY_HOLDS
        else -> Status.VIOLATED
    }
}

data class Exception(
    val session: String,
    val gsId: Int,
    val frameIndex: Int,
    val detail: String,  // what went wrong
)
```

When a pattern's field path doesn't resolve (typo, renamed field): reported as `UNRESOLVABLE` with the bad path. Doesn't silently pass.

**Invocation:** `just segment-relationships [category]`

~200 LOC (types ~40, catalog ~60, validator ~60, CLI + report ~40).

### Piece 3: Profile-Aware Verification

Extends `ProtoDiffer.diff()` with an optional profile parameter:

```kotlin
fun diff(
    recording: GREToClientMessage,
    engine: GREToClientMessage,
    profile: SegmentProfile? = null,
    seatMap: Map<Int, Int> = emptyMap(),
): DiffResult
```

When `profile` is null: current behavior (exact field diff with ID normalization).

When `profile` is provided, comparison rules change per field:
- `CONSTANT` fields (frequency=1.0, variance=CONSTANT) → exact match required
- `ENUM` fields → value must be in observed sample set
- `ID` fields → normalize to ordinals, check structure only (non-zero, present)
- `SIZED` fields → check non-empty, don't assert exact count
- `frequency < 1.0` → field is optional, don't report as missing from engine
- `frequency = 1.0` → field is required, report if absent from engine

After field comparison, validates all confirmed relationships from `RelationshipCatalog` for this category against the engine message. Relationship violations reported as a separate section in `DiffResult`.

The profile is computed on-the-fly by `SegmentVarianceProfiler` from available recordings. No stored profile files.

**Invocation:** `just conform-proto <type> <session> --engine <dir> --profile`

~150 LOC (profile integration into diff ~100, relationship checking ~50).

## Composition with existing tooling

| Existing | How it relates |
|----------|---------------|
| `RecordingFrameLoader` | Foundation — Piece 1 uses it to load raw protos |
| `ProtoDiffer` | Extended by Piece 3 with optional profile parameter |
| `AnnotationVariance.kt` | Parallel system for annotation detail-key variance. Uses `RecordingDecoder` (lossy path). Not modified — conceptually referenced for the annotation detail layer. Future: could migrate to raw proto path for consistency. |
| `AnnotationShapeConformanceTest` | Unmodified — CI tests for annotation builder shapes stay |
| `segments.py` | Unmodified — interactive exploration. Piece 1's segment detection is the Kotlin equivalent on raw proto. |

## Design constraints

- **Works with whatever recordings are locally present** — no fixed corpus. Variance computed fresh each run.
- **Results carry confidence** — instance count + session count + confidence level (TENTATIVE/OBSERVED/CONFIDENT).
- **Relationship catalog is committed Kotlin data** — not local-only. The patterns themselves are protocol knowledge, not captured data. Safe to commit.
- **No CI integration for variance** — recordings aren't in CI. Variance profiling is a local tool. Relationship catalog assertions could eventually become CI tests (same pattern as `AnnotationShapeConformanceTest`) but that's out of scope.
- **Follows annotation variance patterns** — same data model concepts (always/sometimes, frequency, samples) extended to full messages.

## What this does NOT build

- Automatic relationship discovery (V2 — add when catalog is mature)
- CI integration for variance profiles
- Migration of `AnnotationVariance.kt` to raw proto path
- Stored/cached variance profiles
- Sequence-level comparison (multi-frame interaction flows)

## Success criteria

1. Before implementing a mechanic, `just segment-variance <Category>` shows the full structural spec derived from recordings
2. After implementing, `just conform-proto --profile` verifies structural conformance without playtesting
3. When something breaks, the tools point directly at the divergence — not "something is wrong" but "this field is missing" or "this relationship is violated"
4. The relationship catalog grows with each bug and prevents recurrence

## Milestones

1. **SegmentVarianceProfiler** — "I know which fields matter" (~300 LOC)
2. **RelationshipValidator** — "I know the structural rules" (~200 LOC)
3. **Profile-aware verification** — "My engine output is structurally correct" (~150 LOC)

Total: ~650 LOC new code across 3 milestones.
