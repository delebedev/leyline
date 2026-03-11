# Annotation Variance Report — 2026-03-08

59 sessions, 5520 S-C payloads, 8132 GSMs, 12420 annotation instances, 67 distinct types.
**Status: 2 MISMATCH, 21 NOT IMPLEMENTED, 44 OK.**

Previous report (2026-03-01): 17 sessions, 50 types, 4 MISMATCH, 25 NOT IMPL, 21 OK.
Delta: +42 sessions, +17 types, fixed 2 mismatches (ModifiedLife, SyntheticEvent), registered 18 builders that existed but weren't in the variance tool's `OUR_BUILDERS` map.

## Key Structural Findings

### 1. Multi-type annotations are pervasive

Many annotations carry multiple types in a single proto message. The variance tool reports them as separate entries, hiding the co-typing relationship. The client dispatches to ALL parsers in the type list.

| Observed co-type bundles | Types in single annotation |
|---|---|
| P/T buff from ability | `[ModifiedType, LayeredEffect]` |
| Ability + type change | `[RemoveAbility, ModifiedType, LayeredEffect]` |
| Room door-unlock effect | `[ModifiedCost, TextChange, ModifiedName, RemoveAbility, LayeredEffect]` |
| Clone/copy | `[CopiedObject, LayeredEffect]` |
| Max hand size | `[MiscContinuousEffect, LayeredEffect]` |

**Implication:** ModifiedCost, TextChange, and ModifiedName are NOT three separate annotation types — they're one annotation with 5 co-types. The variance tool's per-type filter obscures this.

### 2. Layered effect infrastructure is the gatekeeper

7+ NOT_IMPL types depend on synthetic effect IDs (7000+ range allocated per-game). Building this infrastructure unlocks:

- **LayeredEffectCreated** (131 instances) — buff/debuff/ability-gain animations
- **LayeredEffectDestroyed** (62 instances, builder exists) — VFX teardown
- **ModifiedType** (20) — card type change rendering
- **CopiedObject** (1) — clone identity
- **AddAbility** (15, builder exists) — ability-gain rendering needs effect IDs
- **ModifiedCost / TextChange / ModifiedName** (3+3+3) — Room door-unlock bundle

Without synthetic effect IDs, none of these can be wired correctly. The client tracks effects by their 7000+ ID and expects Created→Persistent→Destroyed lifecycle.

### 3. `9000+` synthetic IDs are a separate mystery

ReplacementEffect uses `affectorId` values in the 9000+ range. These appear to be zone-change IDs, not effect IDs. Unrelated to the 7000+ layered effect scheme. Only 4 instances observed — needs more recordings.

### 4. Three-annotation triplet pattern

TemporaryPermanent + DelayedTriggerAffectees + DisplayCardUnderCard always appear together on the same card in the same GSM. They represent a temporary exile-and-return effect (e.g. Warp, Getaway Glamer):

- **TemporaryPermanent** — marks the card as temporary (IsTemporary flag)
- **DelayedTriggerAffectees** — tracks the pending return trigger
- **DisplayCardUnderCard** — visual: show exiled card under the source

All three share the same TriggerHolder object (grp:5) as affectorId.

---

## Mismatches (wrong data — fix first)

### Scry — wrong detail keys

| | Server | Ours |
|---|---|---|
| Keys | `bottomIds`, `topIds` | `bottomCount`, `topCount` |
| Values | instanceId lists | integer counts |

**Client impact:** `ScryEventAnnotationParser` reads `topIds`/`bottomIds` to identify which cards went where. Without instanceIds, the scry result animation can't highlight specific cards.

**Field note:** Not yet investigated (existed before this sprint).

### Shuffle — missing detail keys

| | Server | Ours |
|---|---|---|
| Keys | `NewIds`, `OldIds` | *(none)* |
| Values | full instanceId reallocation lists | — |

**Client impact:** `ShuffleAnnotationParser` (dual state+event) uses `OldIds`/`NewIds` for the shuffle animation and instanceId remapping. Without them, shuffle animation doesn't play.

**Field note:** Not yet investigated.

---

## NOT IMPLEMENTED — by implementation tier

### Easy (wire existing data, <20 lines each)

| Type | Count | Sessions | Client effect | Field note |
|---|---|---|---|---|
| MultistepEffectStarted | 4 | 1 | Brackets scry/surveil animation start | [field note](field-notes/MultistepEffectStarted.md) |
| MultistepEffectComplete | 4 | 1 | Brackets scry/surveil animation end | [field note](field-notes/MultistepEffectComplete.md) |
| LoyaltyActivationsRemaining | 7 | 2 | PW ability button enable/disable | [field note](field-notes/LoyaltyActivationsRemaining.md) |
| ShouldntPlay | 15 | 1 | "Enters tapped" warning badge in hand | [field note](field-notes/ShouldntPlay.md) |

### Medium (state tracking, 20-50 lines each)

| Type | Count | Sessions | Client effect | Field note |
|---|---|---|---|---|
| CardRevealed | 1 | 1 | Persistent reveal badge on library card | [field note](field-notes/CardRevealed.md) |
| CastingTimeOption | 5 | 1 | Alternate cost UI (Warp, Offspring, X spells) | [field note](field-notes/CastingTimeOption.md) |
| ChoiceResult | 5 | 2 | "Player chose X" highlight + log entry | [field note](field-notes/ChoiceResult.md) |
| ObjectsSelected | 5 | 3 | "Waiting for opponent" during simultaneous choice | [field note](field-notes/ObjectsSelected.md) |

### Hard — Layered Effect family (needs synthetic ID infrastructure)

All depend on synthetic effect IDs (7000+ range). Must be built as a system, not individual builders.

| Type | Count | Sessions | Client effect | Field note |
|---|---|---|---|---|
| LayeredEffectCreated | 131 | 5 | **Buff/debuff animations** — P/T glow, ability gain, type change, control swap | [field note](field-notes/LayeredEffectCreated.md) |
| ModifiedType | 20 | 1 | Card type change rendering (always co-typed with LayeredEffect) | [field note](field-notes/ModifiedType.md) |
| CopiedObject | 1 | 1 | Clone/copy identity (co-typed with LayeredEffect) | [field note](field-notes/CopiedObject.md) |
| ModifiedCost | 3 | 2 | Cost modification display (5-type co-annotation with LayeredEffect) | [field note](field-notes/ModifiedCost.md) |
| TextChange | 3 | 2 | Card text change (same annotation as ModifiedCost) | [field note](field-notes/TextChange.md) |
| ModifiedName | 3 | 2 | Card name change (same annotation as ModifiedCost) | [field note](field-notes/ModifiedName.md) |
| MiscContinuousEffect | 4 | 2 | MaxHandSize / extra combat phases (dual-typed with LayeredEffect) | [field note](field-notes/MiscContinuousEffect.md) |

### Hard — Other complex types

| Type | Count | Sessions | Client effect | Blocker | Field note |
|---|---|---|---|---|---|
| AbilityWordActive | 47 | 2 | Conditional ability glow (Raid, Impending, Delirium) | Most detail-rich parser; SVar→threshold mapping | [field note](field-notes/AbilityWordActive.md) |
| Qualification | 7 | 3 | Cost reduction / evasion badges | Arena-specific QualificationType enum unmapped | [field note](field-notes/Qualification.md) |
| ReplacementEffect | 4 | 1 | ETB replacement tracking (shocklands, fetches) | Mysterious 9000+ synthetic affectorId scheme | [field note](field-notes/ReplacementEffect.md) |
| LinkInfo | 4 | 2 | As-enters choice recording (land type, sac link) | ~15 sub-handlers; affectedIds encoding unclear | [field note](field-notes/LinkInfo.md) |
| TemporaryPermanent | 12 | 1 | Temporary token/exile badge | Part of triplet; needs TriggerHolder synthetic object | [field note](field-notes/TemporaryPermanent.md) |
| DelayedTriggerAffectees | 4 | 1 | Delayed trigger pending badge | Same triplet; TriggerHolder gap | [field note](field-notes/DelayedTriggerAffectees.md) |

---

## OK types (44) — summary

Builders exist and detail keys match the real server.

| Type | Count | Sessions | Notes |
|---|---|---|---|
| PhaseOrStepModified | 3004 | 8 | Highest frequency |
| TappedUntappedPermanent | 1156 | 8 | |
| EnteredZoneThisTurn | 1013 | 8 | |
| ZoneTransfer | 993 | 8 | |
| UserActionTaken | 847 | 8 | |
| ObjectIdChanged | 836 | 8 | |
| AbilityInstanceCreated | 592 | 8 | |
| AbilityInstanceDeleted | 587 | 8 | |
| ManaPaid | 453 | 8 | |
| ColorProduction | 337 | 8 | Builder registered this session (was NOT_IMPL) |
| ResolutionComplete | 320 | 8 | |
| ResolutionStart | 319 | 8 | |
| NewTurnStarted | 274 | 8 | |
| DamageDealt | 247 | 7 | |
| ModifiedLife | 143 | 6 | Fixed from MISMATCH (delta→life) |
| TriggeringObject | 92 | 7 | Builder registered this session |
| SyntheticEvent | 91 | 6 | Fixed from MISMATCH (added {type}) |
| Counter | 80 | 5 | Builder registered this session |
| ModifiedPower | 78 | 4 | Fixed from MISMATCH (dropped {value}) |
| ModifiedToughness | 75 | 4 | Fixed from MISMATCH (dropped {value}) |
| LayeredEffect | 70 | 5 | Builder registered (stub — always key only) |
| LayeredEffectDestroyed | 62 | 4 | Builder registered this session |
| PowerToughnessModCreated | 59 | 4 | Builder registered this session |
| CounterAdded | 53 | 5 | |
| TokenCreated | 48 | 3 | |
| PlayerSelectingTargets | 48 | 7 | Builder registered this session |
| PlayerSubmittedTargets | 48 | 7 | Builder registered this session |
| TargetSpec | 48 | 7 | Builder registered this session |
| DamagedThisTurn | 22 | 3 | Builder registered this session |
| AddAbility | 15 | 3 | Builder registered this session |
| LossOfGame | 14 | 3 | |
| Designation | 13 | 2 | Builder registered this session |
| TokenDeleted | 12 | 3 | |
| RevealedCardCreated | 11 | 2 | |
| RevealedCardDeleted | 11 | 2 | |
| DisplayCardUnderCard | 10 | 3 | Builder registered this session |
| AbilityExhausted | 7 | 3 | Builder registered this session |
| RemoveAbility | 6 | 2 | Builder registered this session |
| AttachmentCreated | 5 | 2 | |
| Attachment | 5 | 2 | |
| PredictedDirectDamage | 5 | 2 | Builder registered this session |
| InstanceRevealedToOpponent | 5 | 1 | Builder registered this session |
| GainDesignation | 5 | 2 | Builder registered this session |
| CounterRemoved | 5 | 2 | |

---

## Client-side reference

From decompiled client code:

**State parsers** mutate `MtgCardInstance` / `MtgEntity` / `MtgGameState` fields.
**Event parsers** produce `GameRulesEvent` for animation/visual layer.
Some types have BOTH (e.g. ResolutionStart, Shuffle, LinkInfo).

### LayeredEffectCreated event parser — sub-handlers

This is why LayeredEffectCreated matters for animations:

| LayeredEffectType detail | Sub-Generator | Visual effect |
|---|---|---|
| `Effect_ModifiedPower` | GenerateModifiedPowerEvents | Power buff/debuff glow |
| `Effect_ModifiedToughness` | GenerateModifiedToughnessEvents | Toughness buff/debuff glow |
| `Effect_ModifiedPowerAndToughness` | GeneratePowerToughnessModifiedEvents | P/T combined buff animation |
| `Effect_AddedAbility` | GenerateAddedAbilityEvents | Ability gain animation |
| `Effect_ModifiedType` | GenerateTypeModificationEvents | Card type change animation |
| `Effect_ModifiedColor` | GenerateColorModifiedEvents | Color change animation |
| `Effect_ControllerChanged` | GenerateControllerChangedEvents | Control swap animation |

### Counter system — three-parser pattern

Server must send all three for correct display:
1. **Counter** (type 14, state) — sets final counter count in `Counters` dict
2. **CounterAdded** (type 16, event) — yields add animation
3. **CounterRemoved** (type 17, event) — yields remove animation

### Dual-interface types (both state + event parsers)

| Type | State effect | Event effect |
|---|---|---|
| ResolutionStart/Complete | Resolution tracking | Resolution animation |
| Shuffle | ID remapping | Shuffle animation |
| LinkInfo | LinkedInfoText display | CardNamedEvent |
| DieRoll | DieRollResults | DieRollEvent |
| ReplacementEffectApplied | Applied tracking | ReplacementEffectAppliedEvent |

---

## Wiring status (builders exist but not emitted)

All 18 builders registered this session have correct detail key shapes but are NOT yet emitted during gameplay. The pipeline wiring status from the [implementation plan](plans/2026-03-01-annotation-fidelity-impl.md):

### Wired (emitted during gameplay)
- `damagedThisTurn` — transient in `combatAnnotations`
- `powerToughnessModCreated` — transient in `mechanicAnnotations`
- `counter` (state) — persistent in `mechanicAnnotations`

### Not yet wired (builder exists, not emitted)
- `colorProduction` — needs per-GSM land scan
- `triggeringObject` — needs ability-to-source tracking
- `playerSelectingTargets` / `playerSubmittedTargets` — needs targeting flow integration
- `targetSpec` — needs deep Forge targeting system integration
- All Tier 1 builders (addAbility, removeAbility, designation, etc.) — need GameEvent wiring

---

## Related docs

- [Annotation variance tool guide](annotation-variance.md) — CLI usage, workflow, key files
- [Annotation field notes (legacy)](annotation-field-notes.md) — deep investigations from 2026-03-01
- [Field notes directory](field-notes/) — per-type investigation notes from 2026-03-08 sprint
- Client annotation registry (from Arena client decompilation) — state + event parsers
- Client annotation parsers (from Arena client decompilation) — parser coroutines
- [Conformance debugging cookbook](conformance-debugging.md) — annotation ordering, category codes, detail key types
- [Implementation plan (batch 1)](plans/2026-03-01-annotation-fidelity-impl.md) — original plan (stale paths, needs update)
- [Variance tooling improvements](plans/2026-03-08-variance-tooling-improvements.md) — tool gaps found during investigation sprint
