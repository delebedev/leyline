# Annotation Fidelity — Batch 1

Fix 4 mismatches + implement 18 new builder methods (Tier 1 + easy Tier 2).
Goal: move from 21/50 types OK to 39/50+ types OK.

## Scope

### Phase A — Fix 4 MISMATCHes

| Type | Fix |
|---|---|
| ModifiedLife | Rename `delta` → `life` |
| ModifiedPower | Drop `{value}` detail (server sends no required keys) |
| ModifiedToughness | Drop `{value}` detail |
| SyntheticEvent | Add `uint32Detail("type", 1)` |

Remove each from `expectedMismatch` in `AnnotationShapeConformanceTest.kt`.

### Phase B — 7 Tier 1 Types (Game State Correctness)

| Type | Always-Present Keys | Notes | Real Card |
|---|---|---|---|
| Counter | `count`, `counter_type` | Complements CounterAdded/Removed (events). This is the state annotation (type 14). | +1/+1 counter on grp:93848 (session 09-33-05) |
| AddAbility | `grpid`, `effect_id`, `UniqueAbilityId`, `originalAbilityObjectZcid` | Granted ability state. | grp:92081 via effect 7005 (session 14-15-29) |
| RemoveAbility | `effect_id` | Also has `310` (object ref) in recordings. | Effect cleanup (session 2026-03-01) |
| AbilityExhausted | `AbilityGrpId`, `UsesRemaining`, `UniqueAbilityId` | Per-ability use tracking. | grp:95039 activated ability (session 09-33-05) |
| GainDesignation | `DesignationType` | Event parser — emits DesignationCreatedEvent. | grp:92196, type=19 (session 2026-03-01) |
| Designation | `DesignationType` | State parser — stub. Full version needs `PromptMessage`, `CostIncrease`, `grpid`, etc. (context needed). | Same card, same session |
| LayeredEffect | `effect_id` | State parser — stub. Sometimes: `grpid`, `UniqueAbilityId`, `sourceAbilityGRPID`, `originalAbilityObjectZcid`. Full ProcessMutate/ProcessNormal split needs more context. | grp:93848, effect_id=7004 (session 09-33-05) |

### Phase C — 11 Easy Tier 2 Types (Visual/UX Fidelity)

| Type | Always-Present Keys | Notes | Real Card |
|---|---|---|---|
| ColorProduction | `colors` | Land frame colors. High frequency (159 instances). | grp:96188, colors=4 (session 09-33-05) |
| TriggeringObject | `source_zone` | Optional: `NEW_OBJECT_ID` (4%). | grp:95039, zone=27 (session 09-33-05) |
| LayeredEffectDestroyed | *(none)* | End-of-effect cleanup. | effect 7007 destroyed (session 09-33-05) |
| PlayerSelectingTargets | *(none)* | Target arrow UX. | grp:176387 (session 11-50-40) |
| PlayerSubmittedTargets | *(none)* | Target confirmation UX. | grp:176387 (session 11-50-40) |
| TargetSpec | `abilityGrpId`, `index`, `promptId`, `promptParameters` | Damage distribution, multi-target. | grp:75479, promptId=1330 (session 11-50-40) |
| PowerToughnessModCreated | `power`, `toughness` | P/T buff animation event. | grp:91865, +1/+1 (session 09-33-05) |
| DamagedThisTurn | *(none)* | Damage indicator badge. | grp:75450 (session 14-15-29) |
| InstanceRevealedToOpponent | *(none)* | "Revealed" badge. | grp:75522 (session 2026-03-01) |
| DisplayCardUnderCard | `Disable`, `TemporaryZoneTransfer` | Imprint/Adventure visual stacking. | grp:75479 (session 11-50-40) |
| PredictedDirectDamage | `value` | "This will deal N damage" preview. | grp:58445, value=2 (session 2026-03-01) |

### Deferred

| Type | Reason |
|---|---|
| LayeredEffectCreated | Complex mega-dispatcher with 7 sub-event generators. Needs more context. |
| Tier 3 (6 types) | Niche — AbilityWordActive, Qualification, MiscContinuousEffect, ModifiedCost, TextChange, ModifiedName |

## Architecture

### Builder Methods

All 18 new types get a method in `AnnotationBuilder.kt`. Same pattern as existing: `AnnotationInfo` with correct `AnnotationType` enum + detail keys via `int32Detail`/`uint32Detail`/`typedStringDetail`.

Two categories:
- **Detail-less** (5): LayeredEffectDestroyed, PlayerSelectingTargets, PlayerSubmittedTargets, DamagedThisTurn, InstanceRevealedToOpponent
- **Detail-carrying** (13): Counter, AddAbility, RemoveAbility, AbilityExhausted, GainDesignation, Designation, LayeredEffect, ColorProduction, TriggeringObject, TargetSpec, PowerToughnessModCreated, DisplayCardUnderCard, PredictedDirectDamage

### Pipeline Wiring

| Level | Types | Status |
|---|---|---|
| **Builder only** | Counter, LayeredEffect, AddAbility, RemoveAbility, AbilityExhausted, Designation, ColorProduction, TriggeringObject, TargetSpec, DisplayCardUnderCard, PredictedDirectDamage, InstanceRevealedToOpponent, DamagedThisTurn | State annotations — builder method exists, no pipeline hook yet. Server attaches these as persistent state on game objects. Pipeline wiring is a separate effort when those subsystems are built. |
| **Pipeline-ready** | GainDesignation, PowerToughnessModCreated, PlayerSelectingTargets, PlayerSubmittedTargets, LayeredEffectDestroyed | Event annotations — fire on game moments. May need new GameEvent variants. Wire case-by-case. |

### Stubbing Strategy

For complex types (LayeredEffect, Designation, AbilityExhausted): builder accepts always-present keys and emits those. Optional/conditional keys documented with `// TODO: context needed` comments referencing real cards/sessions where those keys appeared.

## Testing

### Layer 1 — Builder Shape (all 18 types)

- `AnnotationBuilderTest.kt`: call builder, assert detail key names, types, values
- `AnnotationShapeConformanceTest.kt`: add golden entries to `goldenAlwaysKeys` from variance report data

### Layer 2 — Pipeline (pipeline-ready types only)

- `startWithBoard{}` tier (~10ms) for event-driven annotations
- Verify GameEvent → annotation output chain

### Layer 3 — Variance Regression

- `just proto-annotation-variance --summary` confirms types move from NOT IMPLEMENTED → OK
- 4 MISMATCHes move to OK

## Success Criteria

- `just test-gate` green
- Variance report: ≤1 MISMATCH (LayeredEffectCreated deferred), 39+ OK types
- No new `expectedMismatch` entries — all builders match golden keys
