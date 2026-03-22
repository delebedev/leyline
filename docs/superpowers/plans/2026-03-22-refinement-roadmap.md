# Refinement Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 8 interleaved safety and ergonomic improvements to matchdoor — prevent silent bugs and reduce future change cost.

**Architecture:** No new abstractions. Phase 1-3 are surgical (rename, retype, move). Phase 4 is structural (split test file, extract context type). Each task is an independent PR except Task 4 which depends on Task 3.

**Tech Stack:** Kotlin, protobuf, Kotest, Gradle

**Spec:** `docs/superpowers/specs/2026-03-22-refinement-roadmap-design.md`

---

## Task 1: Detail Key Constants

Extract string literal detail keys to a shared `object DetailKeys`. Prevents typo→NPE bugs.

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/conformance/StructuralFingerprint.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/TestExtensions.kt`

- [ ] **Step 1: Grep for all detail key strings in production code**

```bash
grep -rn 'it\.key ==' matchdoor/src/main/kotlin/
grep -rn 'int32Detail\|typedStringDetail\|uint32Detail' matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt | head -30
```

Collect the full set of unique key names: `zone_src`, `zone_dest`, `category`, `new_id`, `actionType`, `counter_type`, `effect_id`, `damage`, `life_total`, `grpId`, `affector_id`, etc.

- [ ] **Step 2: Create DetailKeys.kt**

```kotlin
package leyline.game

/** Annotation detail key constants — single source of truth for proto detail key strings. */
object DetailKeys {
    const val ZONE_SRC = "zone_src"
    const val ZONE_DEST = "zone_dest"
    const val CATEGORY = "category"
    const val NEW_ID = "new_id"
    const val ACTION_TYPE = "actionType"
    const val COUNTER_TYPE = "counter_type"
    const val EFFECT_ID = "effect_id"
    // ... add all keys found in step 1
}
```

- [ ] **Step 3: Replace all string literals in AnnotationBuilder.kt**

Replace `int32Detail("zone_src", ...)` with `int32Detail(DetailKeys.ZONE_SRC, ...)` etc. ~15 replacements across the builder methods.

- [ ] **Step 4: Replace all string literals in PersistentAnnotationStore.kt**

Lines 84, 128, 137: replace `it.key == "counter_type"` with `it.key == DetailKeys.COUNTER_TYPE` etc.

- [ ] **Step 5: Replace all string literals in BundleBuilder.kt**

Lines 963, 976, 978: replace `it.key == "category"` with `it.key == DetailKeys.CATEGORY` etc.

- [ ] **Step 6: Replace string literal in StructuralFingerprint.kt**

Line 77: replace `detail.key == "category"` with `detail.key == DetailKeys.CATEGORY`.

- [ ] **Step 7: Replace detail key strings in TestExtensions.kt**

Update `detailInt`, `detailString` helpers if they use raw key strings internally. Also update any test assertions that use raw key strings.

- [ ] **Step 8: Verify**

```bash
./gradlew :matchdoor:testGate
grep -rn '"zone_src"\|"zone_dest"\|"category"\|"counter_type"\|"effect_id"\|"new_id"\|"actionType"' matchdoor/src/main/kotlin/
```

Tests pass. Grep returns zero hits in production code (test code may still use raw strings via `detailInt("zone_src")` — that's fine, those are API calls not key definitions).

- [ ] **Step 9: Format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt matchdoor/src/main/kotlin/leyline/conformance/StructuralFingerprint.kt matchdoor/src/test/kotlin/leyline/conformance/TestExtensions.kt
git commit -m "refactor: extract detail key constants to DetailKeys object"
```

---

## Task 2: Type-Safe ID Lambdas

Change `(Int) -> Int` lambdas in AnnotationPipeline to `(ForgeCardId) -> InstanceId`. Zero runtime cost, compile-time safety for ID direction.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt`
- Modify: test files that construct lambda resolvers

- [ ] **Step 1: Read current lambda signatures in AnnotationPipeline.kt**

Lines 110-133 (bridge-delegating overload of `detectZoneTransfers`). Identify each lambda parameter, its current type, and what it semantically does (forge→instance, instance→forge, forge→realloc).

- [ ] **Step 2: Change pure overload signatures**

In the pure `detectZoneTransfers` overload, change:
- `forgeIdLookup: (Int) -> Int?` → `forgeIdLookup: (InstanceId) -> ForgeCardId?`
- `idAllocator: (Int) -> IdReallocation` → `idAllocator: (ForgeCardId) -> IdReallocation`
- `idLookup: (Int) -> InstanceId` stays (already typed)
- `manaAbilityGrpIdResolver: (Int) -> Int` → `manaAbilityGrpIdResolver: (ForgeCardId) -> Int`

Add imports for `ForgeCardId`, `InstanceId` from `leyline.bridge`.

- [ ] **Step 3: Update bridge-delegating overload**

Lines 120-131: update lambda construction to wrap/unwrap value classes:
```kotlin
forgeIdLookup = { iid -> bridge.getForgeCardId(iid) }  // InstanceId already
idAllocator = { forgeCardId -> bridge.reallocInstanceId(forgeCardId) }  // ForgeCardId already
```

- [ ] **Step 4: Update StateMapper delegating calls**

StateMapper.buildFromGame passes lambdas to AnnotationPipeline. Update to match new signatures. The bridge methods already use the value classes — this should simplify the call sites.

- [ ] **Step 5: Update PersistentAnnotationStore if it has resolver params**

Check if `computeBatch` or helper methods take `(Int) -> Int` resolvers. Update to typed equivalents.

- [ ] **Step 6: Update test lambda construction**

Tests that construct mock resolvers like `{ forgeCardId -> forgeCardId + 1000 }` need to wrap: `{ fid: ForgeCardId -> InstanceId(fid.value + 1000) }`.

- [ ] **Step 7: Compile and verify**

```bash
./gradlew :matchdoor:compileKotlin
./gradlew :matchdoor:testGate
```

Compile errors = caught a wrong-direction ID bug. Fix and re-verify.

- [ ] **Step 8: Format and commit**

```bash
just fmt
git add -u matchdoor/
git commit -m "refactor: type-safe ID lambdas in AnnotationPipeline"
```

---

## Task 3: BundleBuilder Object → Class

Convert BundleBuilder from singleton `object` to `class` with constructor-injected stable params.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GamePlayback.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/protocol/HandshakeMessages.kt`
- Modify: test files that call BundleBuilder methods

- [ ] **Step 1: Read BundleBuilder.kt fully, list all methods and their params**

Confirm the 4 methods taking `(game, bridge, matchId, seatId, counter)` and others with partial params.

- [ ] **Step 2: Convert object to class**

```kotlin
class BundleBuilder(
    private val bridge: GameBridge,
    private val matchId: String,
    val seatId: Int,  // public — callers need it
) {
```

- [ ] **Step 3: Remove bridge, matchId, seatId from method signatures**

For the 4 main methods (`postAction`, `stateOnlyDiff`, `remoteActionDiff`, `phaseTransitionDiff`): remove `bridge`, `matchId`, `seatId` params. Keep `game` and `counter` as params (they change per call).

- [ ] **Step 4: Update MatchSession.kt**

Construct BundleBuilder once when bridge connects (in `connectBridge` or lazily). Replace all `BundleBuilder.postAction(game, bridge, matchId, seatId, counter)` calls with `bundleBuilder.postAction(game, counter)`.

- [ ] **Step 5: Update GamePlayback.kt**

Construct BundleBuilder from bridge params. Update `remoteActionDiff` calls.

- [ ] **Step 6: Update HandshakeMessages.kt**

Adapt handshake bundle calls — these may need a BundleBuilder instance passed in or constructed locally.

- [ ] **Step 7: Update test files**

Tests that call `BundleBuilder.postAction(...)` directly need to construct a BundleBuilder instance. Check `BundleBuilderTest.kt` and any conformance tests.

- [ ] **Step 8: Verify**

```bash
./gradlew :matchdoor:testGate
```

- [ ] **Step 9: Format and commit**

```bash
just fmt
git add -u matchdoor/
git commit -m "refactor: BundleBuilder object → class with constructor injection"
```

---

## Task 4: TargetingHandler GRE Message Extraction

Move GRE message building from TargetingHandler to BundleBuilder. Depends on Task 3.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`

- [ ] **Step 1: Identify extractable methods in TargetingHandler**

Read lines 451-847. The methods that build GRE protos inline:
- `sendCastingTimeOptionsReq` (line 451-515) — builds ModalReq + CastingTimeOptionsReq
- `sendGroupReqForSurveilScry` (line 777-847) — builds GREToClientMessage + GameStateMessage

Methods that already delegate to BundleBuilder:
- `sendSearchReq` — calls `BundleBuilder.buildSearchReq()`
- `sendSelectTargetsReq` — calls `BundleBuilder.selectTargetsBundle()`
- `sendSelectNReq` — calls `BundleBuilder.buildSelectNReq()`

Focus on extracting the 2-3 methods that build proto directly.

- [ ] **Step 2: Extract CastingTimeOptionsReq builder to BundleBuilder**

Move the proto construction logic from TargetingHandler.sendCastingTimeOptionsReq into a new BundleBuilder method `buildCastingTimeOptionsReq(...)`. TargetingHandler calls it and sends the result.

- [ ] **Step 3: Extract GroupReq builder to BundleBuilder**

Move proto construction from sendGroupReqForSurveilScry. BundleBuilder already has `GsmBuilder.buildSurveilScryGroupReq` — consolidate.

- [ ] **Step 4: Verify TargetingHandler LOC reduction**

```bash
wc -l matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt
```

Target: below 700 LOC (from 854).

- [ ] **Step 5: Verify tests**

```bash
./gradlew :matchdoor:testGate
```

Targeting tests must still pass — the behavior is identical, only the location changed.

- [ ] **Step 6: Format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt
git commit -m "refactor: extract GRE message building from TargetingHandler to BundleBuilder"
```

---

## Task 5: drainEvents Return-Once Safety

Make event drain explicit in the type system so double-drain is impossible.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`
- Create: test for drain behavior

- [ ] **Step 1: Read GameEventCollector.drainEvents() (line 41)**

Understand current drain mechanics. Also check `peekEvents()` (line 48) and `hasEvents()` (line 51).

- [ ] **Step 2: Choose approach**

Option A: `drainEvents()` returns a `DrainedEvents` wrapper (value class over `List<GameEvent>`). Drain clears the queue. Calling drain again returns an empty `DrainedEvents`. The wrapper type makes intent clear.

Option B: Make drain idempotent — return same events until `acknowledge()` is called. More protection but more state.

**Recommend Option A** — simpler, makes the single-use nature visible in the type system without adding state.

- [ ] **Step 3: Implement DrainedEvents wrapper**

```kotlin
@JvmInline
value class DrainedEvents(val events: List<GameEvent>)
```

Change `drainEvents(): List<GameEvent>` to `drainEvents(): DrainedEvents`.

- [ ] **Step 4: Update StateMapper.buildFromGame (line 63)**

Change `val events = bridge.drainEvents()` to `val drained = bridge.drainEvents()` and use `drained.events` where the list is needed.

- [ ] **Step 5: Write test for drain behavior**

```kotlin
test("drainEvents empties queue") {
    collector.visit(someEvent)
    val first = collector.drainEvents()
    first.events.shouldNotBeEmpty()
    val second = collector.drainEvents()
    second.events.shouldBeEmpty()
}
```

- [ ] **Step 6: Verify**

```bash
./gradlew :matchdoor:testGate
```

- [ ] **Step 7: Format and commit**

```bash
just fmt
git add -u matchdoor/
git commit -m "refactor: DrainedEvents wrapper for type-safe event consumption"
```

---

## Task 6: revealLibraryForSeat Mutation → Parameter

Remove mutable flag from GameBridge, pass as parameter instead.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameBridge.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt`
- Modify: any other callers of buildFromGame/buildDiffFromGame

- [ ] **Step 1: Find all usages of revealLibraryForSeat**

```bash
grep -rn 'revealLibraryForSeat' matchdoor/src/main/
```

GameBridge.kt line 72 (declaration), TargetingHandler.kt lines 689+691 (set/clear), StateMapper or ZoneMapper (read).

- [ ] **Step 2: Add parameter to StateMapper.buildFromGame and buildDiffFromGame**

Add `revealForSeat: Int? = null` parameter to both methods (lines 48, 180). Default null means no library reveal.

- [ ] **Step 3: Thread parameter to ZoneMapper**

Wherever `revealLibraryForSeat` was read from bridge, use the new parameter instead.

- [ ] **Step 4: Update TargetingHandler**

Replace:
```kotlin
bridge.revealLibraryForSeat = ops.seatId
// ... buildFromGame call ...
bridge.revealLibraryForSeat = null
```
With:
```kotlin
// ... buildFromGame(... revealForSeat = ops.seatId) ...
```

- [ ] **Step 5: Remove mutable field from GameBridge**

Delete `@Volatile var revealLibraryForSeat: Int? = null` from GameBridge.kt.

- [ ] **Step 6: Verify**

```bash
./gradlew :matchdoor:testGate
grep -rn 'revealLibraryForSeat' matchdoor/src/main/  # should return zero hits
```

- [ ] **Step 7: Format and commit**

```bash
just fmt
git add -u matchdoor/
git commit -m "refactor: revealLibraryForSeat mutation → parameter"
```

---

## Task 7: AnnotationPipelineTest Split

Split 1134-line god-test into 5 stage-focused files.

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` (delete or reduce to shared fixtures)
- Create: `matchdoor/src/test/kotlin/leyline/game/TransferAnnotationPipelineTest.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/CombatAnnotationPipelineTest.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/MechanicAnnotationPipelineTest.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/EffectAnnotationPipelineTest.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/PersistentAnnotationPipelineTest.kt`

- [ ] **Step 1: Read AnnotationPipelineTest.kt, map tests to categories**

From the exploration data:
- Transfer (12 tests): playLand*, castSpell*, resolve*, genericZone*, castSpellToStack*, resolveToGraveyard*
- Mechanic (16 tests): counter*, shuffle*, scry*, surveil*, token*, powerToughness*, attach*, detach*, cardTapped*, cardUntapped*
- Combat (10 tests): destroy*, sacrifice*, bounce*, exile*, discard*, draw*, mill*
- Effect (8 tests): effectAnnotations*, zoneTransferEvents*
- Persistent/advanced (12 tests): computeBatch*, cardExiledWithSource*, returnFrom*, search*, put*, counteredProduces*

- [ ] **Step 2: Extract shared test fixtures**

Any helper functions, shared `testResolver`, `ConformanceTestBase` setup used across multiple test groups → keep in a shared location (either a companion object or leave in a slim `AnnotationPipelineTestBase.kt`).

- [ ] **Step 3: Create TransferAnnotationPipelineTest.kt**

Move the 12 transfer tests. Add own `tags(UnitTag)`, `ConformanceTestBase` setup, imports.

- [ ] **Step 4: Create MechanicAnnotationPipelineTest.kt**

Move 16 mechanic tests.

- [ ] **Step 5: Create CombatAnnotationPipelineTest.kt**

Move 10 combat/zone-change tests (destroy, sacrifice, bounce, exile, discard, draw, mill).

- [ ] **Step 6: Create EffectAnnotationPipelineTest.kt**

Move 8 effect tests.

- [ ] **Step 7: Create PersistentAnnotationPipelineTest.kt**

Move 12 persistent/advanced tests (computeBatch, displayCardUnderCard, returnFrom, search, put, countered).

- [ ] **Step 8: Delete original AnnotationPipelineTest.kt**

After all tests are moved, delete the original file.

- [ ] **Step 9: Verify same test count**

```bash
./gradlew :matchdoor:testGate
```

Count must match original (50+ tests, 0 failures). Check no tests were lost in the move.

- [ ] **Step 10: Format and commit**

```bash
just fmt
git add matchdoor/src/test/kotlin/leyline/game/
git commit -m "refactor: split AnnotationPipelineTest into 5 stage-focused files"
```

---

## Task 8: ResolvedSessionContext Extraction

Extract nullable-guard pattern from match/ handlers into a typed context resolved once.

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/match/SessionContext.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/CombatHandler.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/AutoPassEngine.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MulliganHandler.kt`

- [ ] **Step 1: Read MatchSession.kt, identify the guard pattern**

15 occurrences of `val bridge = gameBridge ?: return` + 5 of `bridge.getGame() ?: return`. Understand what each handler resolves.

- [ ] **Step 2: Define SessionContext**

```kotlin
package leyline.match

import forge.game.Game
import leyline.bridge.SeatId
import leyline.game.GameBridge

/**
 * Resolved session state — non-null after bridge connection.
 * Constructed once per handler dispatch inside the synchronized block.
 */
data class SessionContext(
    val game: Game,
    val bridge: GameBridge,
    val seatId: Int,
)
```

Keep it minimal. `seatBridge` and `player` can be derived from bridge + seatId — don't store what you can derive.

- [ ] **Step 3: Add resolveContext() to MatchSession**

```kotlin
private fun resolveContext(): SessionContext? {
    val b = gameBridge ?: return null
    val g = b.getGame() ?: return null
    return SessionContext(g, b, ops.seatId)
}
```

- [ ] **Step 4: Update MatchSession handlers to use resolveContext()**

Replace:
```kotlin
fun onPerformAction(...) {
    synchronized(sessionLock) {
        val bridge = gameBridge ?: return
        val game = bridge.getGame() ?: return
        ...
    }
}
```
With:
```kotlin
fun onPerformAction(...) {
    synchronized(sessionLock) {
        val ctx = resolveContext() ?: return
        ...
    }
}
```

Apply to all 15+ handlers. Use `ctx.game`, `ctx.bridge` throughout.

- [ ] **Step 5: Update CombatHandler, TargetingHandler, AutoPassEngine**

If these receive bridge/game as parameters, change to receive `SessionContext`. If they call `bridge.getGame()` internally, use `ctx.game` instead.

- [ ] **Step 6: Verify no remaining bare guards**

```bash
grep -n 'gameBridge ?: return\|getGame() ?: return' matchdoor/src/main/kotlin/leyline/match/MatchSession.kt
```

Should return zero (all replaced by `resolveContext() ?: return`).

- [ ] **Step 7: Verify tests**

```bash
./gradlew :matchdoor:testGate
```

- [ ] **Step 8: Format and commit**

```bash
just fmt
git add matchdoor/src/main/kotlin/leyline/match/
git commit -m "refactor: extract SessionContext to eliminate nullable-guard boilerplate"
```
