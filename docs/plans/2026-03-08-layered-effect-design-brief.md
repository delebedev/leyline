# Layered Effect System — Design Brief

Context dump for the agent designing the synthetic effect ID infrastructure. Read this first, then the linked docs.

## What you're building

The Arena client expects a lifecycle for continuous effects:

```
LayeredEffectCreated (transient, type 18)  →  animation start (glow, badge)
    ↓
LayeredEffect (persistent, type 51)        →  state: which card, what kind of effect
    ↓
LayeredEffectDestroyed (transient, type 19) →  animation teardown
```

Each effect gets a synthetic ID in the 7000+ range, allocated per-game, monotonically increasing. The client tracks effects by this ID across GSMs.

We have builders for LayeredEffect (stub) and LayeredEffectDestroyed. We're missing LayeredEffectCreated, the allocation scheme, and the diff-across-GSMs lifecycle tracking.

## Why it matters

LayeredEffectCreated is the **highest-frequency NOT_IMPL type** (131 instances across 5 sessions). It's also the gatekeeper — 7+ other types co-type with LayeredEffect and can't be wired without the ID infrastructure:

- ModifiedType (20 instances) — always `[ModifiedType, LayeredEffect]`
- CopiedObject (1) — always `[CopiedObject, LayeredEffect]`
- MiscContinuousEffect (4) — MaxHandSize variant dual-typed
- ModifiedCost/TextChange/ModifiedName (3+3+3) — 5-type co-annotation bundle
- AddAbility (15, builder exists) — needs effect_id for the granting effect
- RemoveAbility (6, builder exists) — needs effect_id to remove by

## The client side (from decompiled code)

**`mtga-internals/docs/annotation-registry.md`** has the full parser tables. Key facts:

### LayeredEffectCreated event parser (type 18) — mega-parser

Dispatches on `LayeredEffectType` detail key into sub-handlers:

| Detail value | Sub-generator | Visual |
|---|---|---|
| `Effect_ModifiedPower` | GenerateModifiedPowerEvents | power buff glow |
| `Effect_ModifiedToughness` | GenerateModifiedToughnessEvents | toughness buff glow |
| `Effect_ModifiedPowerAndToughness` | GeneratePowerToughnessModifiedEvents | P/T combined |
| `Effect_AddedAbility` | GenerateAddedAbilityEvents | ability gain animation |
| `Effect_ModifiedType` | GenerateTypeModificationEvents | type change |
| `Effect_ModifiedColor` | GenerateColorModifiedEvents | color change |
| `Effect_ControllerChanged` | GenerateControllerChangedEvents | control swap |

### LayeredEffect state parser (type 51) — complex

Two code paths: `ProcessMutate` vs `ProcessNormal`. Detail keys:
- Always: `effect_id`
- Sometimes: `abilityGrpId`, `isTop`, `Duration`, `LayeredEffectType`, `sourceAbilityGRPID`, `CopyObject`, `grpid`, `UniqueAbilityId`, `originalAbilityObjectZcid`, `MaxHandSize`

### LayeredEffectDestroyed (type 19) — simple event

No detail keys. Just `affectedIds=[7xxx]`. Client removes VFX for that effect ID.

## Observed patterns from recordings

### Pattern 1: Prowess — turn-scoped P/T buff (easiest test case)

> **Current understanding from 1 recording (Otter token, grp:91865). Not verified with an actual Prowess keyword card — none in current recordings. The otter's triggered ability is mechanically identical to Prowess but may differ in grpId mapping.**

Session `09-33-05`, Otter token (grp:91865, iid=335), ability grpId=137.

**Setup — gsId=160 (spell cast):**
- `AbilityInstanceCreated`: ability instance 340 (grpId=137) on the stack
- `TriggeringObject` persistent annotation: affectorId=340, affectedIds=[336] (the triggering spell)

**gsId=163 — trigger resolves:**

Transient annotations (all in this GSM):
```
LayeredEffectCreated     affectedIds=[7007]  affectorId=340  (ability instance on stack)
PowerToughnessModCreated affectorId=340      affectedIds=[335]  details={power=1, toughness=1}
ResolutionComplete       affectorId=340      affectedIds=[340]  details={grpid=137}
AbilityInstanceDeleted   affectorId=335      affectedIds=[340]
```

Persistent annotation created (survives across GSMs):
```
types = ["ModifiedToughness", "ModifiedPower", "LayeredEffect"]   ← THREE types, one proto
affectorId = 335   (the creature itself, NOT the ability instance)
affectedIds = [335]
details:
  sourceAbilityGRPID = 137
  effect_id = 7007
  (NO LayeredEffectType key)
```

**gsId=180 (T8 Beginning) — buff expires:**
```
LayeredEffectDestroyed  affectedIds=[7007]   (transient)
```
Persistent annotation removed via `diffDeletedPersistentAnnotationIds`.

**Key contract points:**
- Persistent annotation has THREE types — not just `LayeredEffect`
- `LayeredEffectType` detail key is **absent** for P/T buffs (only used for `CopyObject` effects)
- `affectorId` on the persistent annotation = the creature (iid=335), not the resolved ability
- `affectorId` on `LayeredEffectCreated` transient = the ability instance (340) while it's on stack
- `PowerToughnessModCreated` is a required companion transient alongside `LayeredEffectCreated`
- `sourceAbilityGRPID` drives the prowess VFX animation on the client

### Pattern 2: Aura/counter — permanent P/T buff

Session `09-33-05`, creature (grp:93848, iid=289) with +1/+1 counter effect.

**gsId=107 — effect applied:**
```
LayeredEffectCreated    affectedIds=[7004]
LayeredEffect           affectedIds=[289]   effect_id=7004
                        (persistent — stays until source leaves)
```

**Lasts across turns.** Not destroyed until the source permanent leaves battlefield.

### Pattern 3: AddAbility via layered effect

Session `00-11-05`, Twinblade Paladin (grp:93652, iid=310).

**gsId=138:**
```
LayeredEffectCreated    affectedIds=[7002]
LayeredEffect + AddAbility  affectedIds=[310]  effect_id=7002
                            grpid=3  UniqueAbilityId=183
```

Grants an ability to the creature. The AddAbility is co-typed with LayeredEffect.

### Pattern 4: Game-start noise

Session `00-18-46`, gsId=1: three effects (7002, 7003, 7004) created AND destroyed in the same GSM. No objects present. Initialization bookkeeping — can probably be ignored.

### Pattern 5: MaxHandSize — dual-type with MiscContinuousEffect

Session `14-15-29`, Proft's Eidetic Memory (grp:88986).

```
LayeredEffect + MiscContinuousEffect  affectedIds=[1]  effect_id=7002
                                      MaxHandSize=2147483647
```

`affectedIds=[1]` = player seat, not a card.

### Pattern 6: CopiedObject — clone

Session `2026-03-06_22-37-41`, Mockingbird (grp:91597) copying Badgermole Cub (grp:97444).

```
CopiedObject + LayeredEffect  affectedIds=[388]  effect_id=7022
                              copyFromGrpid=97444  LayeredEffectType=CopyObject
```

## What Forge gives us

Forge computes layered effects dynamically via its static/continuous ability layer. There's no persistent "effect 7007 was created" tracking. Effects are recomputed from scratch each `buildFromGame`.

**The core design challenge:** we need to diff consecutive game states to detect which effects appeared/disappeared, assign stable synthetic IDs, and emit the Created/Destroyed lifecycle.

### Possible Forge hooks

- `StaticAbilityLayer` — Forge's internal layer system for continuous effects
- `Card.getChangedCardKeywords()` — abilities added by effects
- `Card.getNetPower()` / `Card.getNetToughness()` vs `Card.getBasePower()` — P/T deltas from effects
- `Card.getCounters()` — counter-based P/T changes (distinct from layered effects)
- `GameEventCardStatsChanged` — fires when P/T changes, but doesn't carry the "why"

### What we already emit

- `ModifiedPower` / `ModifiedToughness` (state parsers) — P/T numbers update correctly
- `PowerToughnessModCreated` (event) — animation for P/T change
- `CounterAdded` / `Counter` — counter system fully wired

What's missing: the LayeredEffect lifecycle that tells the client WHY the P/T changed (which effect, which source ability) and when to start/stop the visual.

## Design constraints

1. **IDs must be stable within a game** — same effect = same ID across GSMs until destroyed
2. **IDs must be monotonically increasing** — 7000, 7001, 7002... (client may assume this)
3. **Created/Destroyed must be transient** — appear once, in the GSM where the change happens
4. **LayeredEffect must be persistent** — present in every GSM while the effect is active
5. **Co-typing matters** — AddAbility, ModifiedType, CopiedObject must share the same annotation proto with LayeredEffect
6. **affectorId on Created** — 63/98 instances have it (= ability instance on stack). 35 don't (static/system effects). Optional but valuable.
7. **Must handle multiple effects on same card** — creature can have Prowess buff (7007) + counter effect (7004) simultaneously

## Suggested test cases (increasing complexity)

1. **Prowess** — turn-scoped, single P/T buff, clean create/destroy lifecycle. Use existing Prowess puzzle or bot match.
2. **+1/+1 counter aura** — permanent effect, persists across turns, destroyed when source leaves.
3. **Two simultaneous effects on one creature** — Prowess + counter, verify both get separate IDs.
4. **AddAbility via effect** — creature gains an ability from another permanent.
5. **CopiedObject** — clone effect (hardest — needs copyFromGrpid + LayeredEffectType).

## Files to read

| File | What's in it |
|---|---|
| `docs/field-notes/LayeredEffectCreated.md` | Deep investigation with recording data |
| `docs/field-notes/ModifiedType.md` | Co-typing patterns, never standalone |
| `docs/field-notes/CopiedObject.md` | Clone annotation structure |
| `docs/field-notes/MiscContinuousEffect.md` | Dual-type with LayeredEffect |
| `docs/annotation-field-notes.md` § LayeredEffectDestroyed | Lifecycle patterns, game-start noise |
| `docs/2026-03-08-annotation-variance-report.md` | Full variance data + client parser tables |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Existing builders (~line 559 for layeredEffect, ~637 for layeredEffectDestroyed) |
| `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` | Where annotations are assembled into GSMs |
| `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` | Where Forge events become our GameEvents |
| `matchdoor/CLAUDE.md` | Mental model, pipeline architecture, cookbook |
| `~/src/mtga-internals/docs/annotation-registry.md` | Client annotation type registry — state parsers, event parsers, detail keys, LayeredEffectCreated sub-handlers |
| `~/src/mtga-internals/docs/annotation-parsers.md` | Client parser structure, dispatch loop, annotation ordering contract |

## Retro: contract gaps found during playtest

Discovered after code was written by diffing the live client vs our output during Prowess testing. Five gaps, all from the same root cause.

### Gap 1: Multi-type persistent annotation

We emitted `["LayeredEffect"]`. Real server: `["ModifiedToughness", "ModifiedPower", "LayeredEffect"]` — three types on one proto. The client's state parser dispatches on the full type set, not just the presence of `LayeredEffect`. Sending the wrong types silently breaks P/T display and the buff badge.

### Gap 2: `affectorId` omitted on `LayeredEffectCreated`

We omitted `affectorId`. Real server sets it to the ability instance currently on the stack (iid=340). The client uses this to link the animation to the source spell. Without it, no source-highlight animation fires.

### Gap 3: Missing companion `PowerToughnessModCreated` transient

We emitted `LayeredEffectCreated` alone. Real server always emits `PowerToughnessModCreated` in the same GSM alongside it for P/T buffs. The client needs the companion to know the magnitude (power=1, toughness=1) to drive the floating-numbers animation.

### Gap 4: `sourceAbilityGRPID` not populated

The detail key was present in our builder's field list but we weren't reading it from Forge. Present on ~65% of LayeredEffect instances. Determines which VFX animation plays — prowess-specific glow vs the generic P/T bump animation. Without it the client falls back to the generic one (wrong visual for prowess).

### Gap 5: Spurious `LayeredEffectType` on P/T buffs

We emitted `LayeredEffectType=Effect_ModifiedPowerAndToughness`. Real server: **no `LayeredEffectType` key at all** for P/T buffs. `LayeredEffectType` is only present for `CopyObject` effects. The client's sub-handler dispatch on this key meant we were routing into the wrong code path.

### Root cause

No tooling to extract the full annotation "contract" from recordings before writing code. The `proto-annotation-variance` tool profiles detail keys in isolation — it doesn't surface:
- **Multi-type co-occurrence**: which types appear together on one annotation proto
- **affectorId patterns**: whether/what the affector points to at each lifecycle phase
- **Companion annotations**: what else appears in the same GSM alongside the target annotation

We wrote code against incomplete field notes. Future annotation work: before any implementation, run a companion-extraction query against at least 2 recordings to get the full shape — types[], affectorId, affectedIds, all detail keys, and what else appears in the same GSM.
