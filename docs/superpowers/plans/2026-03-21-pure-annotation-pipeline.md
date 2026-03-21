# Pure Annotation Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `StateMapper.buildFromGame()` into gather/compute/apply phases so the annotation pipeline is a pure function testable without a Forge engine.

**Architecture:** Move all destructive bridge reads (drains) to the top, extract the annotation pipeline stages into a pure function that takes data + function params instead of `GameBridge`, collect all mutations as a `PipelineEffects` return value applied at the bottom. Existing callers (`BundleBuilder`) see no interface change.

**Tech Stack:** Kotlin, protobuf (Messages.proto), Kotest FunSpec

**Context:** See `docs/notes/2026-03-21-rich-hickey-review.md` items #1 and #3 for motivation. The engine is frozen (blocked on CompletableFuture) during `buildFromGame`, so reads are free and safe.

---

## File Structure

```
Modify: matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
        - Split buildFromGame into gather → compute → apply
        - Extract gatherPipelineInput() and applyPipelineEffects()

Modify: matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt
        - detectZoneTransfers: replace GameBridge param with function params
        - New: computeAllAnnotations() — pure orchestrator for stages 1-5

Create: matchdoor/src/main/kotlin/leyline/game/PipelineTypes.kt
        - PipelineInput data class (gathered state)
        - PipelineEffects data class (deferred mutations)

Create: matchdoor/src/test/kotlin/leyline/game/PurePipelineTest.kt
        - Pure pipeline tests with constructed data, no engine

Unchanged: BundleBuilder.kt — same interface (calls buildFromGame/buildDiffFromGame)
Unchanged: AnnotationBuilder.kt — already pure
Unchanged: GameEvent.kt, TransferCategory.kt — already pure data
```

---

### Task 1: Define PipelineTypes (data classes)

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/PipelineTypes.kt`
- Test: compile-only (data classes)

- [ ] **Step 1: Create PipelineInput and PipelineEffects data classes**

```kotlin
package leyline.game

import leyline.bridge.InstanceId

/**
 * Everything the annotation pipeline needs, gathered before compute.
 * Pure data — no engine or bridge references.
 */
data class PipelineInput(
    /** Drained game events (zone changes, combat, mechanics). */
    val events: List<GameEvent>,
    /** Per-instanceId previous zone (for zone transfer detection). */
    val previousZones: Map<Int, Int>,
    /** Accumulated limbo instanceIds (retired from previous GSMs). */
    val limboIds: List<Int>,
    /** P/T boost snapshot for layered effect diffing. */
    val boostSnapshot: Map<Int, List<EffectTracker.BoostEntry>>,
    /** One-shot init effect diff (3 effects created+destroyed on gsId=1). */
    val initEffectDiff: EffectTracker.DiffResult,
    /** Boost diff from EffectTracker. */
    val effectDiff: EffectTracker.DiffResult,
    /** Current persistent annotations (immutable snapshot). */
    val currentPersistentAnnotations: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
    /** Acting seat (1 or 2) derived from priorityPlayer. */
    val actingSeat: Int,
)

/**
 * Deferred mutations produced by the pure pipeline.
 * Applied to bridge after GSM assembly.
 */
data class PipelineEffects(
    /** InstanceIds to retire to Limbo zone. */
    val retiredIds: List<Int>,
    /** (instanceId, zoneId) pairs to record in zone tracker. */
    val zoneRecordings: List<Pair<Int, Int>>,
    /** Persistent annotations to apply via PersistentAnnotationStore.applyBatch. */
    val effectPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
    /** Transfer persistent annotations (EnteredZoneThisTurn, ColorProduction). */
    val transferPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
    /** Mechanic annotation result (for applyBatch — counters, attachments, detachments). */
    val mechanicResult: AnnotationPipeline.MechanicAnnotationResult,
    /** Effect diff (for applyBatch — destroyed effect IDs). */
    val effectDiff: EffectTracker.DiffResult,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :matchdoor:compileKotlin --rerun-tasks`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add matchdoor/src/main/kotlin/leyline/game/PipelineTypes.kt
git commit -m "refactor(matchdoor): add PipelineInput/PipelineEffects data classes"
```

---

### Task 2: Move drains to top of buildFromGame

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt:40-227`

Currently, drains and reads are scattered throughout `buildFromGame`:
- Line 133: `bridge.drainEvents()`
- Line 135: `bridge.drainReveals()`
- Line 163: `bridge.effects.emitInitEffectsOnce()`
- Line 171: `bridge.snapshotBoosts()`
- Line 172: `bridge.effects.diffBoosts()`

This task moves them ALL to the top, before zone mapping. No logic change — pure reorder.

- [ ] **Step 1: Run existing tests to establish baseline**

Run: `just test-gate`
Expected: all pass

- [ ] **Step 2: Move all drains to top of buildFromGame, after player lookup**

Move the five drain/snapshot calls to immediately after `val ai = bridge.getPlayer(SeatId(2))` (line 51). Store results in local vals:

```kotlin
// --- Gather: drain all queues and snapshot mutable state ---
val events = bridge.drainEvents().toMutableList()
for (reveal in bridge.drainReveals(viewingSeatId)) {
    events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
}
val initEffectDiff = bridge.effects.emitInitEffectsOnce()
val boostSnapshot = bridge.snapshotBoosts()
val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
```

Remove the same lines from their current positions deeper in the method.
Update references to use the local vals (they already are — just moved earlier).

- [ ] **Step 3: Verify no behavior change**

Run: `just test-gate`
Expected: all pass, same count

- [ ] **Step 4: Commit**

```
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "refactor(matchdoor): move pipeline drains to top of buildFromGame"
```

---

### Task 3: Collect effects at bottom of buildFromGame

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`

Currently, effects are applied inline between pipeline stages:
- Line 140: `bridge.retireToLimbo()`
- Line 141: `bridge.recordZone()`
- Line 180: `bridge.annotations.applyBatch()`

This task collects them into lists and applies them after GSM assembly.

- [ ] **Step 1: Collect retiredIds and zoneRecordings instead of applying inline**

Replace the inline application (lines 140-141):
```kotlin
// Before (inline mutation):
for (id in transferResult.retiredIds) bridge.retireToLimbo(InstanceId(id))
for ((iid, zid) in transferResult.zoneRecordings) bridge.recordZone(InstanceId(iid), zid)

// After (collect, apply later):
val retiredIds = transferResult.retiredIds
val zoneRecordings = transferResult.zoneRecordings
```

- [ ] **Step 2: Move annotation applyBatch and effect tracking to bottom**

After the GSM is assembled (after `builder.build()`), apply all effects:
```kotlin
// --- Apply: all mutations in one batch ---
for (id in retiredIds) bridge.retireToLimbo(InstanceId(id))
for ((iid, zid) in zoneRecordings) bridge.recordZone(InstanceId(iid), zid)
bridge.annotations.applyBatch(
    effectPersistent = effectPersistent,
    effectDiff = effectDiff,
    transferPersistent = transferPersistent,
    mechanicResult = mechanicResult,
) { forgeCardId -> bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value }

return built  // was: return builder.build()
```

- [ ] **Step 3: Verify no behavior change**

Run: `just test-gate`
Expected: all pass, same count

- [ ] **Step 4: Commit**

```
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "refactor(matchdoor): collect pipeline effects and apply at bottom of buildFromGame"
```

---

### Task 4: Extract detectZoneTransfers from GameBridge dependency

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:86-171`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` (call site)
- Test: `matchdoor/src/test/kotlin/leyline/game/PurePipelineTest.kt`

`detectZoneTransfers` currently takes `GameBridge` and calls:
- `bridge.getPreviousZone(instanceId)` — read
- `bridge.getForgeCardId(instanceId)` — read
- `bridge.reallocInstanceId(forgeCardId)` — MUTATION
- `bridge.getOrAllocInstanceId(forgeCardId)` — idempotent read

Replace with function parameters so the function is testable without a bridge.

- [ ] **Step 1: Write a failing pure test for detectZoneTransfers**

```kotlin
// PurePipelineTest.kt
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*

class PurePipelineTest : FunSpec({

    tags(UnitTag)

    test("detectZoneTransfers finds hand-to-battlefield transfer") {
        // Card was in hand (zone 31), now in battlefield (zone 28)
        val obj = GameObjectInfo.newBuilder()
            .setInstanceId(100)
            .setGrpId(12345)
            .setZoneId(ZoneIds.BATTLEFIELD)
            .setOwnerSeatId(1)
            .build()
        val zones = listOf(
            ZoneInfo.newBuilder()
                .setZoneId(ZoneIds.BATTLEFIELD)
                .addObjectInstanceIds(100)
                .build(),
            ZoneInfo.newBuilder()
                .setZoneId(ZoneIds.LIMBO)
                .setType(ZoneType.Limbo)
                .build(),
        )
        val events = listOf(GameEvent.LandPlayed(forgeCardId = 42, seatId = 1))

        val result = AnnotationPipeline.detectZoneTransfers(
            gameObjects = listOf(obj),
            zones = zones,
            events = events,
            previousZones = mapOf(100 to ZoneIds.P1_HAND),
            forgeIdLookup = { iid -> if (iid == 100) 42 else null },
            idAllocator = { forgeId ->
                InstanceIdRegistry.IdReallocation(
                    leyline.bridge.InstanceId(100),
                    leyline.bridge.InstanceId(200),
                )
            },
            idLookup = { forgeId -> leyline.bridge.InstanceId(forgeId + 1000) },
        )

        result.transfers.size shouldBe 1
        result.transfers[0].category shouldBe TransferCategory.PlayLand
        result.transfers[0].origId shouldBe 100
        result.transfers[0].newId shouldBe 200
        result.retiredIds shouldBe listOf(100)
    }
})
```

- [ ] **Step 2: Run test — it should fail (signature doesn't exist yet)**

Run: `./gradlew :matchdoor:test --tests "leyline.game.PurePipelineTest"`
Expected: FAIL (compilation error — no such overload)

- [ ] **Step 3: Add function-param overload of detectZoneTransfers**

In `AnnotationPipeline.kt`, add a new overload that takes functions instead of bridge:

```kotlin
/**
 * Stage 1: Detect zone transfers — pure overload.
 * Takes function parameters instead of [GameBridge] for independent testability.
 */
internal fun detectZoneTransfers(
    gameObjects: List<GameObjectInfo>,
    zones: List<ZoneInfo>,
    events: List<GameEvent>,
    previousZones: Map<Int, Int>,
    forgeIdLookup: (Int) -> Int?,
    idAllocator: (Int) -> InstanceIdRegistry.IdReallocation,
    idLookup: (Int) -> InstanceId,
): TransferResult {
    // Same logic as existing, but using function params
    // instead of bridge.getPreviousZone / bridge.getForgeCardId / etc.
}
```

Move the core logic from the existing `detectZoneTransfers(... bridge ...)` into the new overload. Make the old overload delegate to the new one:

```kotlin
internal fun detectZoneTransfers(
    gameObjects: List<GameObjectInfo>,
    zones: List<ZoneInfo>,
    bridge: GameBridge,
    events: List<GameEvent>,
): TransferResult = detectZoneTransfers(
    gameObjects = gameObjects,
    zones = zones,
    events = events,
    previousZones = bridge.diff.allZones(),
    forgeIdLookup = { iid -> bridge.getForgeCardId(InstanceId(iid))?.value },
    idAllocator = { forgeCardId ->
        bridge.reallocInstanceId(ForgeCardId(forgeCardId))
    },
    idLookup = { forgeCardId ->
        bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId))
    },
)
```

- [ ] **Step 4: Run test — should pass**

Run: `./gradlew :matchdoor:test --tests "leyline.game.PurePipelineTest"`
Expected: PASS

- [ ] **Step 5: Run full test gate — no regressions**

Run: `just test-gate`
Expected: all pass

- [ ] **Step 6: Commit**

```
git add matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt \
       matchdoor/src/test/kotlin/leyline/game/PurePipelineTest.kt
git commit -m "refactor(matchdoor): extract detectZoneTransfers from GameBridge dependency"
```

---

### Task 5: Add pure pipeline tests for key annotation scenarios

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/game/PurePipelineTest.kt`

Add tests that previously required a full engine startup. Each constructs
`GameObjectInfo` + `GameEvent` data directly and calls the pure pipeline.

- [ ] **Step 1: Add CastSpell transfer detection test**

```kotlin
test("detectZoneTransfers finds hand-to-stack cast") {
    val obj = GameObjectInfo.newBuilder()
        .setInstanceId(100).setGrpId(55555)
        .setZoneId(ZoneIds.STACK).setOwnerSeatId(1).build()
    val zones = listOf(
        ZoneInfo.newBuilder().setZoneId(ZoneIds.STACK)
            .addObjectInstanceIds(100).build(),
        ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo).build(),
    )
    val events = listOf(GameEvent.SpellCast(forgeCardId = 42, seatId = 1))

    val result = AnnotationPipeline.detectZoneTransfers(
        gameObjects = listOf(obj), zones = zones, events = events,
        previousZones = mapOf(100 to ZoneIds.P1_HAND),
        forgeIdLookup = { if (it == 100) 42 else null },
        idAllocator = { InstanceIdRegistry.IdReallocation(
            leyline.bridge.InstanceId(100), leyline.bridge.InstanceId(200)) },
        idLookup = { leyline.bridge.InstanceId(it + 1000) },
    )
    result.transfers[0].category shouldBe TransferCategory.CastSpell
}
```

- [ ] **Step 2: Add Resolve transfer test (keeps same instanceId)**

```kotlin
test("detectZoneTransfers Resolve keeps same instanceId") {
    val obj = GameObjectInfo.newBuilder()
        .setInstanceId(100).setGrpId(55555)
        .setZoneId(ZoneIds.BATTLEFIELD).setOwnerSeatId(1).build()
    val zones = listOf(
        ZoneInfo.newBuilder().setZoneId(ZoneIds.BATTLEFIELD)
            .addObjectInstanceIds(100).build(),
        ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo).build(),
    )
    val events = listOf(GameEvent.SpellResolved(forgeCardId = 42, hasFizzled = false))

    val result = AnnotationPipeline.detectZoneTransfers(
        gameObjects = listOf(obj), zones = zones, events = events,
        previousZones = mapOf(100 to ZoneIds.STACK),
        forgeIdLookup = { if (it == 100) 42 else null },
        idAllocator = { error("should not realloc for Resolve") },
        idLookup = { leyline.bridge.InstanceId(it + 1000) },
    )
    result.transfers[0].category shouldBe TransferCategory.Resolve
    result.transfers[0].origId shouldBe 100
    result.transfers[0].newId shouldBe 100  // same ID
    result.retiredIds shouldBe emptyList()
}
```

- [ ] **Step 3: Add Destroy transfer test**

```kotlin
test("detectZoneTransfers battlefield-to-graveyard with CardDestroyed") {
    val obj = GameObjectInfo.newBuilder()
        .setInstanceId(100).setGrpId(55555)
        .setZoneId(ZoneIds.P1_GRAVEYARD).setOwnerSeatId(1).build()
    val zones = listOf(
        ZoneInfo.newBuilder().setZoneId(ZoneIds.P1_GRAVEYARD)
            .addObjectInstanceIds(100).build(),
        ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo).build(),
    )
    val events = listOf(GameEvent.CardDestroyed(forgeCardId = 42, seatId = 1))

    val result = AnnotationPipeline.detectZoneTransfers(
        gameObjects = listOf(obj), zones = zones, events = events,
        previousZones = mapOf(100 to ZoneIds.BATTLEFIELD),
        forgeIdLookup = { if (it == 100) 42 else null },
        idAllocator = { InstanceIdRegistry.IdReallocation(
            leyline.bridge.InstanceId(100), leyline.bridge.InstanceId(200)) },
        idLookup = { leyline.bridge.InstanceId(it + 1000) },
    )
    result.transfers[0].category shouldBe TransferCategory.Destroy
}
```

- [ ] **Step 4: Add no-transfer-when-zone-unchanged test**

```kotlin
test("detectZoneTransfers returns empty when no zone change") {
    val obj = GameObjectInfo.newBuilder()
        .setInstanceId(100).setGrpId(55555)
        .setZoneId(ZoneIds.BATTLEFIELD).setOwnerSeatId(1).build()
    val zones = listOf(
        ZoneInfo.newBuilder().setZoneId(ZoneIds.BATTLEFIELD)
            .addObjectInstanceIds(100).build(),
    )

    val result = AnnotationPipeline.detectZoneTransfers(
        gameObjects = listOf(obj), zones = zones, events = emptyList(),
        previousZones = mapOf(100 to ZoneIds.BATTLEFIELD),
        forgeIdLookup = { null },
        idAllocator = { error("should not realloc") },
        idLookup = { leyline.bridge.InstanceId(it + 1000) },
    )
    result.transfers shouldBe emptyList()
    result.retiredIds shouldBe emptyList()
}
```

- [ ] **Step 5: Run all pure tests**

Run: `./gradlew :matchdoor:test --tests "leyline.game.PurePipelineTest"`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```
git add matchdoor/src/test/kotlin/leyline/game/PurePipelineTest.kt
git commit -m "test(matchdoor): add pure pipeline tests for zone transfer detection"
```

---

### Task 6: Integrate gather/apply into buildFromGame

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`

Wire Tasks 2-3 together: `buildFromGame` now follows gather → compute → apply.
The method body reads top-to-bottom as three phases with clear comments.

- [ ] **Step 1: Add a `gatherPipelineInput` private method**

Extract the drain + snapshot block from the top of `buildFromGame` into a named method:

```kotlin
private fun gatherPipelineInput(
    bridge: GameBridge,
    game: Game,
    viewingSeatId: Int,
): PipelineInput {
    val events = bridge.drainEvents().toMutableList()
    for (reveal in bridge.drainReveals(viewingSeatId)) {
        events.add(GameEvent.CardsRevealed(reveal.forgeCardIds, reveal.ownerSeatId))
    }
    val initEffectDiff = bridge.effects.emitInitEffectsOnce()
    val boostSnapshot = bridge.snapshotBoosts()
    val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
    val human = bridge.getPlayer(SeatId(1))
    val actingSeat = if (game.phaseHandler.priorityPlayer == human) 1 else 2

    return PipelineInput(
        events = events,
        previousZones = bridge.diff.allZones(),
        limboIds = bridge.getLimboInstanceIds().map { it.value },
        boostSnapshot = boostSnapshot,
        initEffectDiff = initEffectDiff,
        effectDiff = effectDiff,
        currentPersistentAnnotations = bridge.annotations.getAll(),
        actingSeat = actingSeat,
    )
}
```

- [ ] **Step 2: Add an `applyPipelineEffects` private method**

```kotlin
private fun applyPipelineEffects(bridge: GameBridge, effects: PipelineEffects) {
    for (id in effects.retiredIds) bridge.retireToLimbo(InstanceId(id))
    for ((iid, zid) in effects.zoneRecordings) bridge.recordZone(InstanceId(iid), zid)
    bridge.annotations.applyBatch(
        effectPersistent = effects.effectPersistent,
        effectDiff = effects.effectDiff,
        transferPersistent = effects.transferPersistent,
        mechanicResult = effects.mechanicResult,
    ) { forgeCardId -> bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value }
}
```

- [ ] **Step 3: Wire into buildFromGame**

The annotation pipeline section of `buildFromGame` becomes:

```kotlin
// --- Gather ---
val input = gatherPipelineInput(bridge, game, viewingSeatId)

// --- Compute (annotation pipeline) ---
val transferResult = AnnotationPipeline.detectZoneTransfers(gameObjects, zones, bridge, input.events)
// ... stages 2-5 using input.* fields instead of inline drains ...

// --- Apply ---
applyPipelineEffects(bridge, PipelineEffects(
    retiredIds = transferResult.retiredIds,
    zoneRecordings = transferResult.zoneRecordings,
    effectPersistent = effectPersistent,
    transferPersistent = transferPersistent,
    mechanicResult = mechanicResult,
    effectDiff = input.effectDiff,
))
```

- [ ] **Step 4: Verify no behavior change**

Run: `just test-gate`
Expected: all pass, same count

- [ ] **Step 5: Commit**

```
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "refactor(matchdoor): wire gather/compute/apply phases in buildFromGame"
```

---

### Task 7: Update review notes

**Files:**
- Modify: `docs/notes/2026-03-21-rich-hickey-review.md`

- [ ] **Step 1: Update action items table**

Mark items #1 and #3 as done. Update status column.

- [ ] **Step 2: Commit**

```
git add docs/notes/2026-03-21-rich-hickey-review.md
git commit -m "docs: update rich hickey review — pipeline refactoring complete"
```

---

## Verification

After all tasks, the codebase should have:

1. **`buildFromGame` reads top-to-bottom as three phases** — gather, compute, apply
2. **`detectZoneTransfers` is callable without a GameBridge** — function params overload
3. **Pure tests exist** that test zone transfer detection with constructed data (~0.01s each)
4. **All existing tests pass unchanged** — same count, no regressions
5. **`BundleBuilder` callers see no change** — `buildFromGame`/`buildDiffFromGame` signatures unchanged

## What This Does NOT Do (future work)

- Does not extract `combatAnnotations` from `Game` dependency (needs combat data snapshot)
- Does not make the full zone mapping (ZoneMapper) pure (still reads from frozen `Game`)
- Does not change `buildDiffFromGame` internals (it delegates to `buildFromGame`)
- Does not remove the bridge-param overload of `detectZoneTransfers` (backward compat)
