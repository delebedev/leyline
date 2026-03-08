# Layered Effect System — Design

**Date:** 2026-03-08
**Scope:** MVP — P/T buff lifecycle (Prowess + pump spell)
**Prereq reading:** `docs/plans/2026-03-08-layered-effect-design-brief.md`

## What we're building

Synthetic effect ID infrastructure + P/T buff annotations so the client plays buff/debuff animations.

The Arena client expects this lifecycle for continuous effects:

```
LayeredEffectCreated (transient)     → start animation (glow, badge)
LayeredEffect (persistent)           → state: which card, what kind, source ability
LayeredEffectDestroyed (transient)   → stop animation
```

Each effect gets a synthetic ID in the 7000+ range, monotonically increasing per match. The client tracks effects by this ID across GSMs.

### MVP test cases

1. **Prowess** — cast a noncreature spell → creature with Prowess gets +1/+1 until EOT. Triggered ability path. Effect destroyed at turn boundary.
2. **Pump spell** (Giant Growth or similar) — cast targeting creature → +3/+3 until EOT. Direct spell resolution path. Same annotation shape, different trigger mechanism.

Both produce `Effect_ModifiedPowerAndToughness` LayeredEffectType.

## Architecture

### Component: EffectTracker

New component in `GameBridge`, alongside `limbo`, `ids`, `diff`. Owns:

- **Synthetic ID allocator** — `AtomicInteger(7002)`, monotonically increasing per match.
- **Active effect set** — `Map<EffectFingerprint, TrackedEffect>` keyed by fingerprint.
- **Pending created/destroyed queues** — drained at annotation-build time.

```
EffectFingerprint = (cardInstanceId: Int, timestamp: Long, staticAbilityId: Long)
TrackedEffect     = (syntheticId: Int, fingerprint: EffectFingerprint, effectType: LayeredEffectType, metadata: EffectMetadata)
EffectMetadata    = (powerDelta: Int?, toughnessDelta: Int?, sourceAbilityGrpId: Int?)
```

Lifecycle: init in `GameBridge.wrapGame()` / `start()`, reset on puzzle hot-swap (like DiffSnapshotter).

### Diffing strategy: snapshot Forge P/T tables

**No Forge event additions.** We read Forge's public API at annotation-build time:

1. For each card on battlefield, snapshot `Card.getPTBoostTable()` — returns `Table<Long, Long, Value>` with `(timestamp, staticAbilityId)` cell keys.
2. Compare against previous snapshot (stored in EffectTracker).
3. New cells → allocate synthetic ID, queue `LayeredEffectCreated`.
4. Missing cells → queue `LayeredEffectDestroyed`, remove persistent annotation.
5. Unchanged cells → no-op (persistent `LayeredEffect` annotation carries forward automatically).

This runs as a new step in the annotation pipeline, before `mechanicAnnotations` (so P/T transient annotations can reference the effect).

### Why snapshot, not Forge events

- **No coupling direction violation** — leyline depends on forge, never the reverse. Adding events to forge for our benefit inverts this.
- **No maintenance burden** — forge submodule bumps can't break us if we only read stable public API.
- **Correct granularity** — one table cell = one layered effect. Forge events fire at a different granularity (card-level, not effect-level).

Reserve Forge event additions for cases where snapshot-diffing genuinely can't work (e.g., needing intra-resolution ordering). We'll know when we hit that wall.

### Effect identity

The `(cardInstanceId, timestamp, staticAbilityId)` compound key from Forge's boost tables is the fingerprint:

- **`staticAbilityId != 0`** — continuous effects (lords, auras, enchantments). Stable. Traceable to source card via Forge's `StaticEffects` registry → gives us `sourceAbilityGRPID` and `affectorId`.
- **`staticAbilityId == 0`** — resolved spells (Giant Growth, Prowess trigger). Only `timestamp` differentiates. No built-in backlink to source spell.

**Known limitation:** for `staticId=0` effects, we can't populate `affectorId` on `LayeredEffectCreated`. This matches the real server's behavior — ~35% of instances omit `affectorId` (static/system effects). We emit it when traceable, omit when not.

### Pipeline integration

```
StateMapper.buildDiffFromGame():
  1. detectZoneTransfers()          — existing
  2. annotationsForTransfer()       — existing
  3. effectTracker.diff(cards)      — NEW: snapshot, diff, queue created/destroyed
  4. combatAnnotations()            — existing
  5. mechanicAnnotations()          — existing (P/T events reference effect IDs)
  6. effectAnnotations()            — NEW: drain tracker queues → transient + persistent
```

Step 3 mutates the tracker's internal state (snapshots, queues).
Step 6 is a pure drain — converts queued events to proto annotations.

### Annotation output

**LayeredEffectCreated** (transient, one per new effect):
```
types: [LayeredEffectCreated]
affectedIds: [7005]           // synthetic effect ID
affectorId: <ability iid>     // when traceable, omit otherwise
```

**LayeredEffect** (persistent, every GSM while active):
```
types: [LayeredEffect]        // MVP: single type. Future: co-types added here
affectedIds: [<card iid>]     // the buffed card
details:
  effect_id: 7005
  LayeredEffectType: Effect_ModifiedPowerAndToughness   // MVP scope
  sourceAbilityGRPID: <grpId>                           // when traceable
```

**LayeredEffectDestroyed** (transient, one per removed effect):
```
types: [LayeredEffectDestroyed]
affectedIds: [7005]           // same synthetic ID
```

Persistent annotation deletion: synthetic ID added to `diffDeletedPersistentAnnotationIds` in the same GSM as the Destroyed transient.

### gsId=1 initialization effects

Every real server session emits 3 effects (7002-7004) created AND destroyed at gsId=1. Purpose unclear — possibly game-rule initialization bookkeeping.

**MVP approach:** replicate minimally. EffectTracker starts at ID 7002, emits 3 placeholder Created+Destroyed pairs in the first GSM. Keeps our ID counter aligned with real server baseline (client may assume starting value).

Document this as "game-init noise" — revisit if we discover the client actually uses these.

## What grows later (out of MVP scope)

### Co-typed annotations
- `[ModifiedType, LayeredEffect]` — card type changes
- `[AddAbility, LayeredEffect]` — ability grants (builder exists, needs effect_id wiring)
- `[RemoveAbility, ModifiedType, LayeredEffect]` — ability removal + type change
- `[CopiedObject, LayeredEffect]` — clone identity
- `[MiscContinuousEffect, LayeredEffect]` — MaxHandSize, extra combat
- 5-type bundle: `[ModifiedCost, TextChange, ModifiedName, RemoveAbility, LayeredEffect]`

All follow the same pattern: add types to the `LayeredEffect` persistent annotation, populate type-specific detail keys. The EffectTracker fingerprint and ID allocation don't change — only the annotation builder grows.

### Additional LayeredEffectType values
- `Effect_ModifiedPower` / `Effect_ModifiedToughness` (separate P/T)
- `Effect_AddedAbility`
- `Effect_ModifiedType`
- `Effect_ModifiedColor`
- `Effect_ControllerChanged`

Each dispatches to a different client sub-handler for animations. MVP only implements `Effect_ModifiedPowerAndToughness`.

### Keyword/ability diffing
Same snapshot pattern but reading `Card.getChangedCardKeywords()` instead of P/T tables. Fingerprint extends naturally.

### affectorId for resolved spells
If we add Forge events that carry "which spell resolved to create this effect," we can populate `affectorId` for `staticId=0` cases. Deferred — the omission matches real server behavior.

## Testing strategy

### Unit tests
- **EffectTracker** in isolation: fingerprint stability, ID allocation, diff correctness, created/destroyed queue behavior, puzzle hot-swap reset.
- **Annotation output**: given a diff result, verify proto annotation shape matches recording data.

### Puzzle tests
- **Prowess puzzle**: creature with Prowess + a noncreature spell to cast. Verify:
  - LayeredEffectCreated emitted on spell cast
  - LayeredEffect persistent annotation present with correct effect_id and LayeredEffectType
  - LayeredEffectDestroyed emitted at turn boundary
  - P/T visually updates in client (manual verification)
- **Pump spell puzzle**: creature + Giant Growth (or equivalent). Same verification.

### Conformance
- Compare annotation output against proxy recording data for Prowess (session `09-33-05`, gsId=163/180).

## Files to create/modify

| File | Action | What |
|------|--------|------|
| `matchdoor/.../game/EffectTracker.kt` | Create | Core component: fingerprint, ID allocator, diff, queues |
| `matchdoor/.../game/GameBridge.kt` | Modify | Add `effectTracker` field, init in wrapGame/start, reset on hot-swap |
| `matchdoor/.../game/StateMapper.kt` | Modify | Add steps 3+6 to pipeline |
| `matchdoor/.../game/AnnotationBuilder.kt` | Modify | Add `layeredEffectCreated()` builder |
| `matchdoor/.../game/AnnotationPipeline.kt` | Modify | Add `effectAnnotations()` pure function |
| `matchdoor/...test.../game/EffectTrackerTest.kt` | Create | Unit tests |
| `puzzles/prowess.pzl` | Create | Prowess test puzzle |
| `puzzles/pump-spell.pzl` | Create | Giant Growth test puzzle |
