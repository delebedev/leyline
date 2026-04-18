# StateMapper Snap-vs-Snap Diff — Design Spec

## Goal

Rewrite `StateMapper.buildDiffFromGame` as `StateMapper.buildDiff(prev: GsmSnapshot?, cur: GsmSnapshot, ...)` — a pure-input diff over two immutable `GsmSnapshot` values. Drop the last `forge.game.Game` import inside the GSM pipeline (`StateMapper.kt` baseline entry). Make `bridge.lastSent: GsmSnapshot?` load-bearing — the diff reads it. Retire the `DiffSnapshotter` baseline branch (`diffBaselineState` + helpers); keep `previousZones` (independent zone-tracking concern).

## Context

Follow-up to `arena-lab-b3h` (GsmSnapshot pipeline migration, leyline PR #19, merged 2026-04-18). That epic landed mapper-level purity, the compile-enforced `NoGameInMappers` detekt rule, and `GsmSnapshot.forTest(...)` fixtures for unit tests. Critical-review verdict on b3h: 3–5x payoff, not the 10x originally pitched, because the diff stage held back the headline wins:

- **"Ordering invariant dies"** — only partially. `StateMapper.buildDiff` still mutates `bridge.ids` via zone-transfer realloc; the 4-stage coupling collapsed to a 2-stage handshake (diff → actions). Live `Game` reads still happen inside diff.
- **"Replay trivial"** — not delivered. Snap capture works; you can store `GsmSnapshot` sequences. But replay-from-snap doesn't round-trip because diff still needs `GameStateMessage`.
- **"`bridge.lastSent` replaces `DiffSnapshotter.diffBaselineState`"** — `bridge.lastSent` is set at every bundle exit but consumed nowhere. Groundwork, not a working replacement.

Migrating the diff stage closes all three input-side wins. Output-side side effects (`bridge.ids` realloc, `retireToLimbo`, `recordZone`) stay inside diff for now (Q3-ii); they're a separable refactor.

## Non-goals

- **Pure-output diff.** `bridge.ids.realloc()` (and `retireToLimbo`, `recordZone`) continue to fire from inside the diff via `ZoneTransferDetector`. The architectural win this bead delivers is on inputs (snap-vs-snap, immutable). Pulling output side-effects out as data is a separable bead.
- **Typed `Diff(zonesAdded, zonesRemoved, ...)` result.** `BuildResult(gsm, hasCastSpell)` survives. A typed diff would help fuzzer/replay work but isn't required for this bead.
- **`ActionMapper.buildNaiveActions` migration.** AI-only path; deliberately stayed on legacy in b3h. Out of scope here too.
- **Coordinator/handler pair unification (`PromptExchange`)** — separate bead, lower leverage, independent.
- **`PersistentAnnotationStore` O(n²) indexes** — trigger-gated; out of scope.
- **`BundleBuilder` is the legitimate `forge.game.Game` capture site after this bead too.** Not migrating it.

## Architecture

### Diff signature

```kotlin
fun buildDiff(
    prev: GsmSnapshot?,
    cur: GsmSnapshot,
    gameStateId: Int,
    matchId: String,
    bridge: GameBridge,
    actions: ActionsAvailableReq? = null,
    updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
    viewingSeatId: Int = 0,
    revealForSeat: Int? = null,
): BuildResult
```

`game: Game` parameter removed. `prev == null` → Full GSM (first bundle, or after explicit `clearBaseline`-equivalent). Otherwise → Diff GSM by snap-vs-snap field comparison.

### Pipeline shape — after

```
BundleBuilder.postAction(game, counter):
    snap   = GsmSnapshot.capture(game, bridge, matchId)        // unchanged
    diff   = StateMapper.buildDiff(bridge.lastSent, snap, ...) // snap-only inputs
    actions = ActionMapper.buildFromSnapshot(seatId, snap, bridge)
    gs     = GsmBuilder.embedActions(diff.gsm, actions, ...)
    bridge.lastSent = snap                                     // unchanged write site
    return BundleResult(messages)
```

`bridge.lastSent` becomes load-bearing: the diff reads it; nothing else changes about how/when it's written.

### Internal flow of `buildDiff(prev, cur)`

1. **Build current full GSM from `cur`.** Existing `StateMapper.buildFromGame` is renamed `buildFromSnapshot` and drops its `game: Game` parameter. All internal reads already go through the snap captured at line 66 today; this commit just removes the now-unused parameter.
2. **First-bundle path.** If `prev == null`, return `curFull` as `BuildResult` (Full type). No baseline assignment to retire — `bridge.lastSent = snap` is set by the caller (`BundleBuilder`) at bundle exit, replacing `bridge.snapshotDiffBaseline(curFull)`.
3. **Snap-vs-snap delta computation:**
   - **Changed zones.** `cur.zones.filter { (id, z) -> prev.zones[id] != z }` keys → emit corresponding `ZoneInfo`s from `curFull.zonesList`. `ZoneSnapshot` equality covers `contents`, `visibility`, `viewers`, `type`, `owner` — all the fields the existing diff inspects.
   - **Changed objects.** `cur.objects.filter { (fid, c) -> prev.objects[fid] != c }` keys → emit corresponding `GameObjectInfo`s from `curFull.gameObjectsList`. Opponent-hand filter preserved (`ZoneMapper.opponentHandZone(viewingSeatId)`); active-reveal exception preserved (`bridge.allSeatIds().any { ...activeReveal() != null }`, gating proxies + Public hand cards through). Justification this works: `ObjectMapper.buildFromSnapshot` already produces every `GameObjectInfo` field from `CardSnapshot` (b3h migration); if a field weren't snap-derivable, current tests would already fail.
   - **Deleted IDs.** `prev.objects.keys - cur.objects.keys` mapped through `bridge.getOrAllocInstanceId(fid)` → instance IDs, minus any still tracked in `currentZoneTrackedIds` (limbo-retired IDs that still appear in zone lists).
4. **Diff GSM assembly.** Same builder structure as today: `setType(GameStateType.Diff)`, `setTurnInfo(curFull.turnInfo)`, `addAllPlayers(curFull.playersList)`, `addAllZones(changedZones.sortedBy { it.zoneId })`, `addAllGameObjects(changedObjects)`, `addAllAnnotations(curFull.annotationsList)`, `addAllPersistentAnnotations(curFull.persistentAnnotationsList)`, `addAllDiffDeletedPersistentAnnotationIds(bridge.annotations.drainDeletions())`, `addAllTimers`, `setUpdate(updateType)`, `setPrevGameStateId(...)`. Stripped action embedding preserved (`ActionMapper.stripActionForGsm`).
5. **`prevGameStateId`.** Today comes from `prev.gameStateId` (the proto baseline). Snap doesn't carry a `gameStateId` today. Add `GsmSnapshot.gameStateId: Int` and extend `GsmSnapshot.capture(game, bridge, matchId, gameStateId: Int)` to accept it. `BundleBuilder` calls `capture(game, bridge, matchId, nextGs)` after `counter.nextGsId()`. `HandshakeMessages` capture sites (deal/mulligan flows, `gameStateId` not yet meaningful) pass `0`. `buildDiff` reads `prev.gameStateId` for the `setPrevGameStateId(...)` call — no extra parameter on `buildDiff`.
6. **Side effects (unchanged).** `transferResult.retiredIds` → `bridge.retireToLimbo`, `transferResult.zoneRecordings` → `bridge.recordZone`, `bridge.annotations.applyBatchResult`, `bridge.annotations.setAnnotationId`. All inside `buildFromSnapshot`. `bridge.ids.realloc(...)` continues to fire inside `ZoneTransferDetector` as before. Q3-ii.

### Snap growth (Q2-iii)

Three surviving Forge reads in `StateMapper.kt` (the source of the lone `import forge.game.Game`):

**On snap (pure data, captured at `SnapshotCapture.run`):**
- `CardSnapshot.isOnAdventure: Boolean` — used for Qualification pAnn (Exile cards on Adventure).
- `CardSnapshot.endOfTurnLeavePlay: Boolean` — used for TemporaryPermanent pAnn (token + `hasSVar("EndOfTurnLeavePlay")`).
- `GsmSnapshot.abilityWordEntries: List<AbilityWordScanner.Entry>` — precomputed by running `AbilityWordScanner.scan(bfCards, ...)` at capture time. `StateMapper` consumes the precomputed list instead of running the scanner.

**Off snap (closure builder, lives in a Forge-allowed file):**
- Extract `buildSourceAbilityResolver(bridge)` from `StateMapper.kt` into new file `matchdoor/src/main/kotlin/leyline/game/SourceAbilityResolverFactory.kt`. The factory imports `forge.game.Game` legitimately; it sits outside the `NoGameInMappers` denied set. `StateMapper` calls `SourceAbilityResolverFactory.build(bridge)` instead of constructing inline. Closure is bridge-bound (depends on `cardRepository`, `abilityRegistryFor`, etc.) — putting closures on a value snap would be awkward.

After both moves, `StateMapper.kt` no longer needs `import forge.game.Game`. The detekt baseline entry clears.

### `CombatAnnotations` cross-stage dependency

`CombatAnnotations.combatAnnotations(events, bridge, transferredIds)` reads `bridge.getDiffBaselineState()` for `previousLifeTotals`. Replace with snap-threaded prev:

```kotlin
internal fun combatAnnotations(
    events: List<GameEvent>,
    bridge: GameBridge,
    prev: GsmSnapshot?,
    transferredIds: Map<ForgeCardId, Int> = emptyMap(),
): CombatAnnotationResult {
    val previousLifeTotals = prev?.seats?.associate { it.seatId.value to it.life } ?: emptyMap()
    val currentLifeTotals = previousLifeTotals.keys.associateWith { seat ->
        bridge.getPlayer(SeatId(seat))?.life ?: 0
    }
    ...
}
```

`prev` threads through `StateMapper.computeAnnotations(...)` (already an internal fn) → `combatAnnotations`. `currentLifeTotals` could also pull from `cur.seats[seat].life` — preserve the bridge-read for v1 to minimize blast radius.

### `DiffSnapshotter` retire

**Delete:**
- `diffBaselineState` field
- `snapshotDiffBaseline(state)` — written from `StateMapper.buildDiffFromGame`, `BundleBuilder.kt:766`, `MatchSession.kt:162`, `MatchSession.kt:208`, `TestHelpers.snapshotDiffBaseline` (test helper)
- `getDiffBaselineState()` — read from `StateMapper.buildDiffFromGame` (lines 239, 375), `CombatAnnotations.kt:53`
- `clearBaseline()` (called from `GameBridge.clearDiffBaseline()`)
- `GameBridge.clearDiffBaseline()` — confirm no live callers (check before deletion)

**Keep:**
- `previousZones`, `recordZone`, `getPreviousZone`, `allZones` — independent zone-tracking concern, consumed by `ZoneTransferDetector`.

**Trim:**
- `BridgeContracts.kt`: drop `snapshotDiffBaseline` and `getDiffBaselineState` from the interface.
- `DiffSnapshotter.resetAll()`: keep `previousZones.clear()`, drop `diffBaselineState = null`.
- Test helper `TestHelpers.snapshotDiffBaseline(game, gameStateId)` rewritten to `bridge.lastSent = GsmSnapshot.capture(game, bridge, "")`.
- `GameBridge.kt:787` (`lastSent = null` in some reset path) — preserved.

## Migration cadence (Q5)

Single branch `snap-diff` off fresh `main` (`e16db16`, b3h merge commit). Already created at `~/src/leyline--snap-diff`. One PR at the end, commits stack on the branch. Same b3h pattern.

**Dual-check discipline.** Each behavioural commit pair adds the new path, asserts equivalence under `DevCheck.strict`, then cuts over:

```kotlin
val newGsm = StateMapper.buildDiffFromSnapshot(prev, cur, ...).gsm
if (DevCheck.strict) {
    val oldGsm = StateMapper.buildDiffFromGame(game, ...).gsm
    check(newGsm == oldGsm) { "snap-diff drift:\n  new=$newGsm\n  old=$oldGsm" }
}
// real flow uses newGsm
```

`DevCheck.strict` is true in tests, false at runtime. Drift surfaces as a CI failure with both protos.

**Commit stack:**

```
1.  feat(snapshot): grow CardSnapshot (isOnAdventure, endOfTurnLeavePlay)
2.  feat(snapshot): precompute abilityWordEntries on GsmSnapshot at capture
3.  feat(snapshot): GsmSnapshot.gameStateId — capture takes gameStateId param; BundleBuilder threads nextGs
4.  refactor(game): extract SourceAbilityResolverFactory out of StateMapper
5.  refactor(state): rename buildFromGame → buildFromSnapshot; drop unused Game param
6.  refactor(annotations): thread prev: GsmSnapshot? through computeAnnotations + CombatAnnotations
7.  feat(state): add buildDiffFromSnapshot; dual-check under DevCheck.strict at every BundleBuilder bundle
8.  refactor(state): cut over BundleBuilder bundles to buildDiffFromSnapshot; delete buildDiffFromGame
9.  chore(bridge): retire DiffSnapshotter.diffBaselineState branch + interface trim
10. chore(state): drop import forge.game.Game from StateMapper.kt; clear NoGameInMappers baseline
11. chore(snapshot): rename buildDiffFromSnapshot → buildDiff; retire dual-check scaffolding
```

Per-commit gate: `:matchdoor:detektMain :matchdoor:detektTest :matchdoor:spotlessApply :matchdoor:test`. Phase-boundary (after each cut-over commit): `gtimeout 900 ./gradlew :matchdoor:testIntegration`.

Wire canary: `AnnotationShapeConformanceTest` green at every commit. Surfaces any proto-shape drift the dual-check missed.

## Tests

**Snap-fixture diff test** (acceptance criterion):

```kotlin
class StateMapperDiffSnapshotTest : FunSpec({
    tags(UnitTag)

    test("life total change emits PlayerInfo update") {
        val prev = GsmSnapshot.forTest(seats = listOf(seat(1, life = 20), seat(2, life = 20)))
        val cur  = GsmSnapshot.forTest(seats = listOf(seat(1, life = 17), seat(2, life = 20)))
        val diff = StateMapper.buildDiff(prev, cur, gameStateId = 5, matchId = "test", bridge = harness.bridge)
        diff.gsm.type shouldBe GameStateType.Diff
        diff.gsm.playersList.first { it.systemSeatNumber == 1 }.lifeTotal shouldBe 17
    }

    test("no change → empty zones+objects+deletedIds") {
        val snap = GsmSnapshot.forTest(seats = listOf(seat(1, life = 20)))
        val diff = StateMapper.buildDiff(snap, snap, ...)
        diff.gsm.zonesList.shouldBeEmpty()
        diff.gsm.gameObjectsList.shouldBeEmpty()
        diff.gsm.diffDeletedInstanceIdsList.shouldBeEmpty()
    }

    test("null prev → Full GSM") {
        val cur = GsmSnapshot.forTest(...)
        val diff = StateMapper.buildDiff(null, cur, ...)
        diff.gsm.type shouldBe GameStateType.Full
    }
})
```

The `bridge` parameter is still required for ID resolution + helper services even though snap drives the diff inputs — minimal harness, no Forge boot.

**Existing tests (ConformanceTestBase, SubsystemTest, MatchFlowHarness)** continue to cover end-to-end behaviour; snapshot unit tests complement at the diff layer.

## Acceptance criteria (from arena-lab-9d8)

- `StateMapper.buildDiff(prev: GsmSnapshot?, cur: GsmSnapshot, ...)` — no `Game` param.
- `bridge.lastSent` read by diff stage (consumed, not just written).
- `DiffSnapshotter.diffBaselineState` + `snapshotDiffBaseline` + `getDiffBaselineState` + `clearBaseline` deleted; `previousZones` retained.
- `matchdoor/detekt-baseline-main.xml` — zero `NoGameInMappers` entries.
- ≥1 diff-stage unit test uses `GsmSnapshot.forTest(prev=..., cur=...)` literal fixture; no Forge boot for that test.
- `:matchdoor:test` + `:matchdoor:testIntegration` green throughout the branch.
- `AnnotationShapeConformanceTest` green at every commit (wire canary).
- `BundleBuilder.kt` is the only file in `matchdoor/src/main/kotlin/leyline/game/` importing `forge.game.Game` after this bead lands.

## Open questions

*(Answered during brainstorming — captured here for traceability)*

- **Diff baseline shape?** Pure snap-vs-snap (Q1-A). Risk that some `GameObjectInfo` field isn't `CardSnapshot`-derivable is small because `ObjectMapper.buildFromSnapshot` already proves the contract.
- **Surviving Forge reads?** Hybrid (Q2-iii). Trivial flags + `abilityWordEntries` go on snap. `sourceAbilityResolver` stays bridge-side as `SourceAbilityResolverFactory.kt`.
- **`bridge.ids` realloc?** Stays inside diff (Q3-ii). Pure-output diff is a separable follow-up bead.
- **Diff result type?** Keep `BuildResult(gsm, hasCastSpell)` (Q4). Typed `Diff(zonesAdded, ...)` deferred.
- **Migration cadence?** Dual-path under `DevCheck.strict` (Q5). Same as b3h.
- **PR shape?** Single branch `snap-diff`, single PR at the end, commits stack (Q6). Same as b3h.

## Predecessors

- **arena-lab-b3h** — closed 2026-04-18; landed via leyline PR #19 (merge `e16db16`). Spec: `docs/superpowers/specs/2026-04-18-gsm-snapshot-design.md`. Plan: `docs/superpowers/plans/2026-04-18-gsm-snapshot.md`.
- **arena-lab-k8r** — closed; PromptJournal migration (PR #17).
