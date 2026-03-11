# Spec: Annotation ID Analysis Tool

## Why

The `affectorId` and `affectedIds` fields on annotations carry semantic meaning that varies per annotation type. `ZoneTransfer.affectorId` is a player seat. `TriggeringObject.affectorId` is an ability instance. `EnteredZoneThisTurn.affectorId` is a zone ID. This vocabulary is undocumented — scattered across 5+ files, investigated ad-hoc, and completely absent from `AnnotationVariance` (which ignores `affectorId` entirely).

We need this vocabulary to:
- Populate rosetta.md with recording-backed affectorId/affectedIds semantics per annotation type
- Validate our `AnnotationBuilder` output against real server patterns (today's conformance gaps include wrong affectorId on `AbilityInstanceCreated`, `TappedUntappedPermanent`, etc.)
- Make the conformance pipeline's binder smarter — knowing that `affectorId` is an ability instance vs a card instance changes how you bind template variables

The tool mines this vocabulary from recordings automatically. No manual investigation per type.

## What

A Python CLI tool that analyzes `md-frames.jsonl` recordings and classifies every `affectorId` and `affectedIds` value by semantic role, using three layers of increasingly rich context.

### Layer 1: Range classification (stateless)

Classify every ID value by numeric range. No accumulated state needed — pure arithmetic.

| Range | Role | Example |
|---|---|---|
| 0 | Absent | Not set in proto (default value) |
| 1–2 | Player seat | `UserActionTaken.affectorId = 1` |
| 18–38 | Zone ID | `EnteredZoneThisTurn.affectorId = 28` (Battlefield) |
| 100+ | Instance ID | Card or ability — needs Layer 2 to distinguish |
| 7000+ | Synthetic effect ID | `LayeredEffectDestroyed.affectedIds` |
| 9000+ | Synthetic zone-change event ID | `ReplacementEffect` field notes |
| -3 | Sentinel | Game-rule effect, no source object (`CopiedObject`) |

**Output per annotation type:** Distribution of roles. `ZoneTransfer.affectorId: seat=85%, instance=15%, absent=0%`.

### Layer 2: Instance ID provenance (needs accumulated state)

For every ID in the 100+ range, determine what it is by tracking object lifecycles across the recording.

Build a running object map from frame 0 to frame N:
- On Full GSM: snapshot all `gameObjects` → `{instanceId: {grpId, zoneId, cardTypes, born_gs}}`
- On Diff GSM: merge objects (diff replaces by instanceId), apply `diffDeletedInstanceIds`, track zone changes from zone `objectIds` lists
- On `ObjectIdChanged` annotation: record rename `orig_id → new_id`, inherit grpId/card info

For every 100+ ID in an annotation, look it up in the accumulated map:
- **Card instance**: has a grpId, lives/lived in a zone (Hand, Battlefield, Stack, Library, etc.)
- **Ability instance**: appeared in `AbilityInstanceCreated` annotation's `affectedIds`, no grpId in objects (or grpId is an ability grpId, not a card grpId). Transient — created and deleted within a segment.
- **Unknown**: ID not found in accumulated state (data gap or proto field we're not tracking)

**Output per annotation type:** Refined distribution. `TappedUntappedPermanent.affectorId: ability_instance=80%, card_instance=20%`.

### Layer 3: Relationship patterns (cross-field analysis)

For each annotation instance, classify the relationship between `affectorId` and `affectedIds`:

| Pattern | Description | Example |
|---|---|---|
| self | affectorId == affectedIds[0] | `TokenDeleted`, `AbilityExhausted` |
| source→target | affector is source, affected is target(s) | `TriggeringObject`: ability→card |
| player→target | affector is seat, affected is card(s) | `UserActionTaken`: seat→card |
| zone→cards | affector is zone ID, affected is card list | `EnteredZoneThisTurn`: zone→[cards] |
| absent→target | no affector, affected carries meaning | `AttachmentCreated`: →[aura, enchanted] |
| source→player | affector is card/ability, affected is seat | `Qualification`: permanent→[seat] |

**Output per annotation type:** Dominant pattern + frequency. `TriggeringObject: source→target(100%), affector=ability, affected=card`.

## How

### Implementation

Single Python script: `tooling/scripts/annotation-ids.py`. Stdlib only (json, collections, sys, os, glob). No project classpath.

**Subcommands:**

```bash
# Layer 1 only (fast, no accumulated state)
python3 tooling/scripts/annotation-ids.py ranges [session]

# All three layers (builds object accumulator)
python3 tooling/scripts/annotation-ids.py analyze [session]

# Per-type deep dive (shows concrete examples with card names)
python3 tooling/scripts/annotation-ids.py detail <AnnotationType> [session]
```

Session defaults to most recent recording with `md-frames.jsonl`. Substring matching (same as `md-segments.py`).

### Object accumulator

Maintains `{instanceId: ObjectInfo}` across frames. `ObjectInfo` is a dict:

```python
{
    "grpId": int,
    "zoneId": int,
    "cardTypes": list[str],
    "owner": int,
    "born_gs": int,          # gsId where first seen
    "prev_ids": list[int],   # ObjectIdChanged rename chain
    "is_ability": bool,      # appeared in AbilityInstanceCreated
}
```

Merge rules (same as scry accumulator, but with rename tracking):
- Full GSM: replace entire map
- Diff GSM: merge by instanceId (diff replaces object), apply deletions
- `ObjectIdChanged` annotation: copy object info from `orig_id` to `new_id`, add `orig_id` to `prev_ids`, mark `orig_id` as retired
- `AbilityInstanceCreated` annotation: create entry with `is_ability=True`, inherit `grpId` from the annotation's details if present

Retired objects stay in the map (needed for provenance lookups on later annotations that reference old IDs).

### Output format

**`ranges` subcommand** — compact table:

```
Annotation Type           affectorId                    affectedIds
─────────────────────────────────────────────────────────────────────
ZoneTransfer              seat:85% instance:15%         instance:100%
EnteredZoneThisTurn       zone:100%                     instance:100%
UserActionTaken           seat:100%                     instance:95% absent:5%
TriggeringObject          instance:100%                 instance:100%
ResolutionStart           instance:100%                 instance:100%
ObjectIdChanged           absent:60% instance:40%       instance:100%
...
```

**`analyze` subcommand** — enriched with provenance and patterns:

```
Annotation Type           affectorId              affectedIds             Pattern
──────────────────────────────────────────────────────────────────────────────────
ZoneTransfer              seat:85% ability:15%    card:100%               player→target / source→target
EnteredZoneThisTurn       zone:100%               card:100%               zone→cards
TriggeringObject          ability:100%            card:100%               source→target
TappedUntappedPermanent   ability:80% card:20%    card:100%               source→target
TokenDeleted              card:100%               card:100%               self
ResolutionStart           card:60% ability:40%    card:60% ability:40%    self
...
```

**`detail` subcommand** — concrete examples per type:

```
TriggeringObject (n=12 across 3 sessions)

  affectorId: ability_instance (100%)
  affectedIds: card_instance (100%)
  Pattern: source→target

  Examples:
    gs=10  affectorId=285 (ability, grpId:176406, ETB trigger for Wall of Runes)
           affectedIds=[283] (card, grpId:75478, Wall of Runes, Battlefield)
           details: {source_zone: 28}
           session: 2026-03-08_19-44-CHALLENGE-STARTER-SEAT1

    gs=45  affectorId=310 (ability, grpId:176830, trigger for Sworn Guardian)
           affectedIds=[308] (card, grpId:75480, Sworn Guardian, Battlefield)
           details: {source_zone: 28}
           session: 2026-03-08_19-44-CHALLENGE-STARTER-SEAT1
```

### Multi-session support

When no session is specified, scan all sessions with `md-frames.jsonl` under `recordings/`. Aggregate statistics across sessions. The `detail` subcommand shows examples from multiple sessions to demonstrate consistency.

### Integration points

- **Rosetta update**: `analyze` output can be pasted directly into rosetta.md Table 1 as new columns
- **Conformance pipeline**: `detail` output for a specific type feeds into the binder — tells you whether to expect a seat, card, ability, or zone as affectorId when binding template variables
- **Annotation variance**: the `ranges` output complements `AnnotationVariance` — variance checks detail key shapes, this checks ID value shapes. Together they cover the full annotation schema.

### Detail keys that carry instance IDs

Six detail keys also carry instance IDs (not just affectorId/affectedIds):

| Detail key | Annotation type | What it is | Frequency |
|---|---|---|---|
| `orig_id` / `new_id` | ObjectIdChanged | Card before/after zone transfer | Every zone move |
| `topIds` / `bottomIds` | Scry | Cards being scryed | Every scry |
| `id` | ManaPaid | Mana source instance | Every spell cast |
| `SourceParent` | Qualification | Source permanent | Rare |
| `promptParameters` | TargetSpec | Target card instances | Every targeted spell |
| `originalAbilityObjectZcid` | AddAbility | Source ability instance | When gaining abilities |

These are annotation-type-specific, not universal. The accumulator built for Layer 2 can classify them with no extra work — same provenance lookup, different input field.

**Implementation:** `--include-details` flag on `analyze` and `detail` subcommands. When set, also classifies detail values in the 100+ range using the same accumulator. Off by default to keep output focused.

**Not instance IDs** (look like them but aren't): `grpid`/`abilityGrpId` (card catalog constants), `effect_id` (synthetic 7000+ range), `counter_type` (enum), `UniqueAbilityId` (ordinal), `promptId` (UI template). These stay in the skip list.

### What this does NOT do

- Does not compare our engine output against recordings (that's the conformance pipeline)
- Does not modify any files (read-only analysis)
- Does not resolve card names from grpIds (would need Arena card DB access — just shows `grpId:NNNNN`). Card name resolution can be added later via `just card` integration.

## Reference

- **Existing ID tracing**: `Trace.kt` / `just proto-trace` — single-ID chase through payloads with ObjectIdChanged following. Interactive, not batch.
- **Existing variance**: `AnnotationVariance.kt` / `just proto-annotation-variance` — detail key shape comparison. Ignores affectorId completely.
- **Scry accumulator**: `bin/scry_lib/accumulator.py` — live game state snapshot. Handles Diff/Full merge. Does NOT track ObjectIdChanged renames or annotation history. Reference for merge semantics, not for reuse.
- **Conformance levers**: `docs/conformance-levers.md` #4 (structural binding) and #5 (lossless object tracking) both depend on the object accumulator this tool builds. The accumulator is shared infrastructure.
- **Known affectorId gaps**: `docs/conformance-pipeline.md` CastSpell gaps table — `AbilityInstanceCreated.affectorId` should be mana source (recording) but is 0 (engine). `TappedUntappedPermanent.affectorId` should be ability instance (recording) but is the card itself (engine).
