## Qualification — field note

**Status:** NOT IMPLEMENTED
**Instances:** 7 across 3 sessions
**Proto type:** AnnotationType.Qualification (type 42)
**Field:** persistentAnnotations

### What it means in gameplay

Tracks which static abilities are currently in effect and who they apply to. The client parses `QualificationType` to determine the category of the qualification (cost reduction, evasion, keyword grant, etc.) and renders appropriate UI indicators — discount icons, evasion badges, etc.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| QualificationType | Always | 1, 32, 40 | Category of qualification (see type table below) |
| QualificationSubtype | Always | 0 | Always 0 in all observed instances; may be unused or reserved |
| SourceParent | Always | instanceIds | The permanent that grants the qualification (= affectorId) |
| grpid | Always | 20230, 62969, 142 | The specific ability grpId granting the qualification |

**QualificationType values observed:**

| Type | Mechanic | Ability | Card | affectedIds |
|------|----------|---------|------|-------------|
| 1 | Cost reduction | 20230 (Warden of Evos Isle cost-reduce ability) | Warden of Evos Isle (grp:75479) | Player seat (2) |
| 32 | Combat evasion — can't be blocked | 62969 (Silent Hallcreeper unblockable) | Silent Hallcreeper (grp:92142) | Card itself (self-referencing) |
| 40 | Keyword ability grant — Menace | 142 (Menace) | Super Shredder (grp:100540) | Card itself (self-referencing) |

### Cards observed

| Card name | grpId | QualificationType | grpid | Scenario | Session |
|-----------|-------|-------------------|-------|----------|---------|
| Warden of Evos Isle | 75479 | 1 | 20230 | Enters BF T5; second copy T10 (iid=331); affectedIds=player seat 2 | 11-50-40 |
| Silent Hallcreeper | 92142 | 32 | 62969 | Enters BF T5; self-referencing (affectedIds = affectorId) | 14-15-29 |
| Super Shredder | 100540 | 40 | 142 | Enters BF T4; Menace keyword grant; self-referencing | 2026-03-07_11-49-05 |

### Lifecycle

Persistent annotation. Created when the source permanent enters the battlefield. Persists as long as the permanent exists. When a second copy of the same card enters (Warden iid=331 at T10), a new annotation with a new ID is created pointing to the new instance — the two annotations coexist, each referencing a different `SourceParent`.

Deleted (via `diffDeletedPersistentAnnotationIds`) when the source permanent leaves the battlefield.

Both seats receive the annotation in their respective GSM copies (the variant report shows two identical records per gsId for the Warden instances — one per systemSeatIds).

### Related annotations

- `LayeredEffect` — carries continuous effect details; Qualification appears to be the "who benefits" tracking layer while LayeredEffect carries the effect parameters
- `LayeredEffectCreated` / `LayeredEffectDestroyed` — may co-occur for temporary qualifications (not yet observed — all observed Qualifications are permanent static abilities)

### Our code status

- Builder: missing
- GameEvent: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

Requires scanning all permanents with static abilities each GSM and determining:
1. Which static abilities produce Qualifications
2. What `QualificationType` each maps to
3. Who `affectedIds` should be — player seat (for cost reduction), the card itself (for evasion/keyword grants), or potentially other cards

The QualificationType enum is Arena-internal and undocumented. Only 3 values have been observed (1, 32, 40). A complete mapping requires more recordings covering a wider variety of static abilities.

Forge computes static effects dynamically; there is no GameEvent when a static ability starts applying. Wiring would require either:
- A per-GSM diff of which static-ability-bearing permanents are on the battlefield
- New instrumentation in Forge's static ability evaluation layer

Low priority — purely visual (cost discount badges, evasion indicators). The mechanics themselves work correctly.

### Open questions

- What is the full range of QualificationType values? Three observed (1, 32, 40) — likely many more exist.
- Are there QualificationType values where `affectedIds` references other cards (not self and not player seat)? E.g., "creatures you control gain flying" — does each creature get a Qualification, or does the controller's seat?
- Does `QualificationSubtype` ever take a non-zero value? Not seen in 7 instances.
- Are Qualifications ever transient (lasting less than one turn), or always persistent as long as the source is on the BF?
