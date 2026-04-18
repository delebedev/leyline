# StateMapper Diff — Pure Outputs + Replay Forcing Function — Design Spec

## Goal

Make `StateMapper.buildDiff` a pure function on its ordering-sensitive outputs. Today `buildDiff` returns `(gsm, hasCastSpell)` and side-effects `bridge.ids` (id reallocation for zone transfers), `bridge.limbo` (retireToLimbo), `bridge.diff.previousZones` (recordZone), and `bridge.annotations` (applyBatchResult + setAnnotationId) as it runs. This bead lifts those writes out of the stage and returns them as data (`BridgeMutations`); `BundleBuilder` applies them between diff and action-build. A round-trip replay test — take a deterministic scenario, capture per-step snaps + events, feed them back through `buildDiff`, assert byte-equal Diff GSM — serves as the acceptance forcing function: it proves the purity is real, not performative.

## Context

Follow-up to `arena-lab-9d8` (leyline PR #21, merged 2026-04-18). 9d8 made `buildDiff`'s INPUTS pure (snap-vs-snap, no `game: Game` parameter anywhere in the GSM pipeline). Review verdict on 9d8: the three advertised wins — ordering invariant dies, replay trivial, `bridge.lastSent` load-bearing — landed as 1½ of 3. `bridge.lastSent` is clean. Replay is *enabled* (snap-vs-snap shape permits it) but not *exercised* — no harness, no regression guard. The ordering invariant is half-dead: diff reads are pure, diff writes still mutate bridge in an order-sensitive sequence. The deleted KDoc that warned about ordering was misleading at the time of deletion — the invariant moved inside `buildFromSnapshot`, it didn't die.

Why now: we paid for the snap scaffolding and the compile-enforced `NoGameInMappers` rule, but we haven't cashed in the composable-stages payoff. Every new stateful feature (annotation, prompt shape) that lands between here and "we have a replay test" risks re-introducing impurity we'd then need to excavate later. The replay test is the forcing function that turns "code looks pure" into "pipeline IS pure" — compile time is too weak a signal; a passing round-trip test is the stronger one.

Scope commitment from brainstorm: **a + b**. (a) minimal replay test as acceptance. (b) extract the ordering-sensitive bridge mutations as data. Not (c) full recording-driven fuzzer — that's a follow-up bead that extends the recording format.

## Non-goals

- **Pulling monotonic allocators into data.** `bridge.ids.getOrAlloc` for new cards, `bridge.effects.nextEffectId`, `bridge.annotations.nextAnnotationId`/`nextPersistentId` counter reads — these stay as in-place mutations. Their ordering doesn't affect correctness; making them pure is cost without benefit.
- **`bridge.effects` (EffectTracker) state lifting.** EffectTracker holds layered-effect lifecycle state. Making it pure for replay would require a full second-order refactor of its own. Out of scope; replay is "partial" in the sense that effect-tracker scenarios may not round-trip byte-equal. The bead accepts this boundary.
- **Recording-driven replay.** Extending `recordings/` tooling to capture snaps + events alongside FD frames is a separate bead (follow-up). This bead uses a scripted deterministic scenario instead.
- **Fuzzer.** The replay test is a single scripted scenario, not a fuzzer over the recordings corpus.
- **Prompt-response purity.** Prompt handling (client response → bridge mutation via `TargetingCoordinator` etc.) is inherently transactional and lives outside the pure zone. The `PromptJournal` refactor (k8r) already drew that boundary.
- **Forge engine purity.** Forge stays mutable. Purity stops at the capture boundary (`GsmSnapshot.capture`) + event-collect boundary (`GameEventCollector`). Same stance as 9d8.

## Architecture

### Diff signature — after

```kotlin
fun buildDiff(
    prev: GsmSnapshot?,
    cur: GsmSnapshot,
    events: List<GameEvent>,
    gameStateId: Int,
    matchId: String,
    bridge: GameBridge,
    actions: ActionsAvailableReq? = null,
    updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
    viewingSeatId: Int = 0,
    revealForSeat: Int? = null,
): BuildResult
```

Differences from today:
- `events: List<GameEvent>` is now a parameter. Caller drains `bridge.drainEvents()` + `bridge.drainReveals(viewingSeatId)` before calling.
- `bridge` is still passed — needed for pure-read helpers (`bridge.getOrAllocInstanceId` for new-card allocation, `bridge.cardRepository`, `bridge.promptBridge(seat).journal.activeReveal()` for active-reveal detection). These are either monotonic allocators or effectively-immutable lookups. Document this as the accepted impurity island.
- `BuildResult` grows to `data class BuildResult(val gsm: GameStateMessage, val hasCastSpell: Boolean, val mutations: BridgeMutations)`.

### `BridgeMutations` shape

Typed value (not sealed-class list):

```kotlin
data class BridgeMutations(
    /** Zone-transfer id reallocations (ForgeCardId → new InstanceId). */
    val idReallocations: List<IdReallocation>,
    /** Instance IDs to move to limbo after apply. */
    val retiredIds: List<InstanceId>,
    /** (instanceId, zoneId) pairs to record in `DiffSnapshotter.previousZones`. */
    val zoneRecordings: List<Pair<InstanceId, Int>>,
    /** Persistent annotation store batch to apply. */
    val persistentBatch: PersistentAnnotationStore.BatchResult,
    /** New value for `bridge.annotations.nextAnnotationId`. */
    val nextAnnotationId: Int,
) {
    companion object {
        val EMPTY = BridgeMutations(emptyList(), emptyList(), emptyList(), PersistentAnnotationStore.BatchResult.EMPTY, 0)
    }
}

data class IdReallocation(val forgeCardId: ForgeCardId, val oldInstanceId: InstanceId, val newInstanceId: InstanceId)
```

`GameBridge.applyMutations(mutations: BridgeMutations)` applies all five in a fixed order:

```kotlin
fun applyMutations(m: BridgeMutations) {
    for (r in m.idReallocations) ids.applyRealloc(r)  // or similar — see below
    for (id in m.retiredIds) retireToLimbo(id)
    for ((iid, zid) in m.zoneRecordings) recordZone(iid, zid)
    annotations.applyBatchResult(m.persistentBatch)
    annotations.setAnnotationId(m.nextAnnotationId)
}
```

The fixed order is the former in-line order inside `buildFromSnapshot` today — behavior-preserving.

**Id-realloc complication:** `ZoneTransferDetector` today calls `bridge.ids.realloc(fid)` which BOTH allocates a new id AND returns the old one in a `IdReallocation` result. That's a mutation. To make it returnable-as-data, either:
- **(i) Pre-allocate** inside the detector (still mutates `bridge.ids`), just return the realloc as part of `TransferResult`. Keeps the mutation in-line during compute but records it for later re-application. **Doesn't achieve purity** — the allocation is the mutation we want to defer.
- **(ii) Pure compute + apply-after** — `ZoneTransferDetector` works against a pure id-allocation context that doesn't commit; returns planned reallocations; `applyMutations` commits them to `bridge.ids`. Requires a small refactor of `InstanceIdRegistry` to support "dry-run alloc" semantics.

Chose **(ii)**. Otherwise the bead is performative — we've moved the write later but the bridge was still mutated inside the stage. Scope: add `InstanceIdRegistry.planRealloc(fid): IdReallocation` (computes the new id deterministically without committing) + `applyRealloc(realloc)` (commits). The detector uses `planRealloc`; `applyMutations` calls `applyRealloc`. `ids.nextInstanceId` counter is only incremented during apply.

### Snap growth — persistent annotation state

For replay to round-trip, `buildDiff`'s persistent-annotation output must be a pure function of its inputs. Today that output depends on `bridge.annotations.snapshot()` read at the start of `buildFromSnapshot`. To lift the dependency out:

Add to `GsmSnapshot`:

```kotlin
data class PersistentAnnotationState(
    val activeAnnotations: Map<Int, AnnotationInfo>,
    val nextAnnotationId: Int,
    val nextPersistentId: Int,
)

val GsmSnapshot.persistentAnnotationState: PersistentAnnotationState
```

`SnapshotCapture.run` reads these from `bridge.annotations` at capture time.

`buildDiff` reads `cur.persistentAnnotationState.activeAnnotations` / `nextPersistentId` / `nextAnnotationId` from the snap, not from bridge. The caller no longer needs to read these off bridge.

This is a principled growth: snap-is-state and persistent annotations ARE state at time T. Keeps the "input shape = snap + events" invariant clean.

### Caller contract (BundleBuilder)

Every BundleBuilder bundle becomes:

```kotlin
val nextGs = counter.nextGsId()
val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)
val events = bridge.drainEvents().events + bridge.drainReveals(seatId).map {
    GameEvent.CardsRevealed(it.forgeCardIds, it.ownerSeatId)
}
val result = StateMapper.buildDiff(bridge.lastSent, snap, events, nextGs, matchId, bridge, ...)
bridge.applyMutations(result.mutations)
val gs = GsmBuilder.embedActions(result.gsm, actions, ...)   // or whatever the bundle-specific step is
bridge.lastSent = snap
```

The drain-before / apply-after pattern makes the caller the orchestrator of mutation lifecycle. `buildDiff` itself only READS from bridge (for pure-lookup helpers) and RETURNS mutations.

### Drain sites relocated

The following drains move from inside `buildFromSnapshot` to the BundleBuilder caller:

- `bridge.drainEvents().events` → caller, passed as `events`
- `bridge.drainReveals(viewingSeatId)` → caller, mapped into `events`
- `bridge.annotations.drainDeletions()` → caller, passed as `persistentDeletions: List<Int>` parameter (or folded into a `DiffContext` value if the signature grows too wide; see "Open questions" in the brainstorm decisions)
- `bridge.effects.emitInitEffectsOnce()` → caller (produces an `EffectTracker.DiffResult` that gets passed in as `initEffectDiff`)

These all move; their compute-and-write pattern splits into compute-at-caller + pass-as-value.

### Residual in-stage impurities (accepted, documented)

`buildFromSnapshot` still invokes these bridge methods internally. They are accepted as the impurity island:

- `bridge.getOrAllocInstanceId(fid)` — monotonic allocator for NEW cards (never-before-seen fids). Out-of-scope per brainstorm.
- `bridge.cardRepository.findGrpIdByName / findByGrpId` — effectively-immutable read-only DB.
- `bridge.effects.diffBoosts(boostSnapshot)` / `diffKeywords(...)` / `nextEffectId()` — EffectTracker state + counter. Out-of-scope; see non-goal.
- `bridge.revealProxies.*` — RevealProxyTracker reads + writes. Scoped; tied to a transactional reveal-choose effect that lives across bundles. Out-of-scope; see non-goal.
- `bridge.annotations.activeStealForgeCardIds()` / `addSteals(...)` / `removeSteals(...)` — steal lifecycle tracker. Read-then-write pattern inside stage. Tightly coupled to the event loop; lifting would require tracking steal state on snap. Out-of-scope unless the replay test fails on a steal scenario; then in-scope.

Add a class-level KDoc comment on `StateMapper` enumerating these and linking to this spec for rationale. Detekt doesn't (and shouldn't) flag them — they're not `forge.game.Game` reads; they're bridge-bound services.

## Migration cadence

Single branch `diff-pure` off fresh main (post-9d8 merge `31b27fd`). Already created at `~/src/leyline--diff-pure`. One PR at end, commits stack. Same b3h/9d8 pattern.

Dual-path discipline: the existing `buildDiff(prev, cur, ...)` (today's) stays alongside the new signature during migration commits. `DevCheck.strict` asserts byte-equal GSMs between old path and new. Cut over when clean.

Commit stack (approximate — plan will finalise):

```
1.  feat(snapshot): add PersistentAnnotationState on GsmSnapshot; capture reads from bridge.annotations
2.  feat(bridge): InstanceIdRegistry planRealloc / applyRealloc split (dry-run alloc)
3.  feat(state): BridgeMutations type; GameBridge.applyMutations(m)
4.  refactor(state): ZoneTransferDetector uses planRealloc; returns planned reallocations
5.  feat(state): new buildDiff signature with events + mutations return; old signature retained under dual-check
6.  refactor(bundle): BundleBuilder drains events + reveals + annotation-deletions before calling buildDiff; applies mutations after
7.  refactor(state): cut over — delete old buildDiff signature and in-stage drains
8.  test(replay): scripted deterministic round-trip test
9.  chore(state): update StateMapper class KDoc — enumerate residual impurity island
```

Per-commit gate: `:matchdoor:detektMain :matchdoor:detektTest :matchdoor:spotlessApply :matchdoor:test`. Phase-boundary: `:matchdoor:testIntegration` after commits 6, 7, 8.

Wire invariant: `AnnotationShapeConformanceTest` green every commit.

## Replay test shape (acceptance forcing function)

`matchdoor/src/test/kotlin/leyline/game/PureDiffReplayTest.kt`:

```kotlin
class PureDiffReplayTest : FunSpec({
    tags(UnitTag)

    test("scripted puzzle + action sequence — snap-vs-snap diff byte-equal across replay") {
        // 1. Load deterministic puzzle (fixed decks, seed, starting hands).
        val (bridge, game, counter) = harness.startPuzzle("replay-fixture.pzl")

        // 2. Record the live run: capture (snap_i, events_i, diff_i) for each bundle.
        val liveRun = mutableListOf<BundleStep>()
        bridge.bundleListener = { step -> liveRun.add(step) }
        for (action in scriptedActions) bridge.performAction(action)

        // 3. Replay: recreate a pristine bridge + pipeline. Feed each (snap_i, events_i) back through
        //    buildDiff + applyMutations. Assert the emitted GSM at each step is byte-equal to the
        //    live run's diff_i.
        val replayBridge = fresh()
        for ((i, step) in liveRun.withIndex()) {
            val replayResult = StateMapper.buildDiff(step.prev, step.cur, step.events, step.gameStateId, ...)
            replayBridge.applyMutations(replayResult.mutations)
            replayResult.gsm shouldBe step.diff  // byte-equal
        }
    }
})
```

Concrete test fixture: a one-turn deterministic scenario — a creature already on the battlefield (or one in hand + land in play), human plays it if not already in play, passes to combat, declares attacker, no blockers (opponent has empty battlefield), combat damage, end turn. ~5–7 bundles end-to-end. Covers zone transfers (Hand → Stack → Battlefield for cast; Battlefield → Graveyard for any death), phase changes (newTurnStarted, PhaseOrStepModified for each step), combat (attacker declaration, combat damage, life total change), and persistent annotations (any PT/keyword active effects on the permanents involved). Does NOT exercise:
- Effect tracker layered effects (out of scope)
- Reveal-choose prompts (out of scope)
- Steal effects (out of scope; see residual impurity island)

If those topics need replay coverage later, they're follow-up beads lifting the corresponding state onto snap.

A `BundleStep` data class captures the per-bundle record:

```kotlin
data class BundleStep(
    val prev: GsmSnapshot?,
    val cur: GsmSnapshot,
    val events: List<GameEvent>,
    val gameStateId: Int,
    val diff: GameStateMessage,
    val mutations: BridgeMutations,
)
```

The test asserts byte-equal `diff` and optionally `mutations` (byte-equal mutations would additionally confirm the ordering-sensitive outputs are deterministic).

## Acceptance criteria

- `StateMapper.buildDiff(prev, cur, events, ...)` — events is a param, not drained inside.
- `BuildResult.mutations: BridgeMutations` returned; `BundleBuilder` applies via `bridge.applyMutations`.
- `bridge.ids` alloc is gated through `planRealloc` / `applyRealloc` during diff; no in-stage `bridge.ids.realloc(...)` calls.
- `bridge.annotations.applyBatchResult` / `setAnnotationId` fire ONLY inside `applyMutations`, never inside `buildFromSnapshot` or its callees.
- `GsmSnapshot.persistentAnnotationState` populated at capture; `buildDiff` reads it (not from bridge).
- Drains (`drainEvents`, `drainReveals`, `annotations.drainDeletions`, `effects.emitInitEffectsOnce`) moved to BundleBuilder callers.
- `PureDiffReplayTest` passes: scripted scenario round-trips byte-equal Diff GSMs.
- `:matchdoor:test` + `:matchdoor:testIntegration` green throughout the branch.
- `AnnotationShapeConformanceTest` green at every commit.
- `StateMapper` class KDoc enumerates the residual impurity island (bridge-bound services remaining in-stage) with rationale.

## Shipping discipline

**Do NOT auto-merge on CI green.** Push to the PR, wait for human review + merge. The 9d8 auto-merge flow was the exception (explicit `/loop` authorization); for this bead, treat PR as the human-gate.

## Diagnostics — "how do we know we nailed it"

From the brainstorm:

1. **Add-a-pAnn test** (informal): a future new annotation should land as `CardSnapshot` field + capture + consume + fixture test, <30 min, no `bridge.getGame()` inside any pipeline stage.
2. **`PureDiffReplayTest` stays green** as future features ship. Any new bead that breaks replay = impurity surfaced.
3. **Detekt `NoGameInMappers` baseline stays empty.** Any new entry = back-sliding.
4. **Mapper-tier fixture test density climbs** — count of `forTest(...)`-using tests grows as features arrive.
5. **No `bridge.lastSent`-shaped parallel-state re-emergence.** Watch for new caches that shadow snap.

Pass all five over the next N beads = model nailed. Fail one = diagnose + adjust.

## Open questions

*(Answered during brainstorming — captured for traceability)*

- **Scope?** a+b: extract ordering-sensitive mutations as data + minimal replay test as acceptance. (c) full recording-driven fuzzer deferred to follow-up.
- **Mutation return shape?** Typed `BridgeMutations` data class (Q1-i). Sealed-class mutation list rejected as ceremony without payoff.
- **Allocator scope?** Monotonic counters stay in-place. Only the ordering-sensitive mutations become data.
- **Events threading?** Separate `events: List<GameEvent>` parameter (Q2-i). `Transition(prev, events, cur)` wrapper rejected.
- **Persistent annotation state?** Lifted onto snap as `GsmSnapshot.persistentAnnotationState` (Q3-i). Separate threaded value rejected.
- **Replay test scope?** Scripted deterministic scenario (Q4-i). Recording-driven replay deferred.
- **Forge purity stance?** Unchanged from 9d8. Forge stays mutable; purity stops at capture + event-collect boundaries.

## Predecessors

- **arena-lab-9d8** — closed 2026-04-18; leyline PR #21. Made `buildDiff` inputs pure (snap-vs-snap). This bead makes outputs pure.
- **arena-lab-b3h** — closed 2026-04-18; leyline PR #19. GsmSnapshot pipeline migration.
- **arena-lab-k8r** — closed; PR #17. PromptJournal (prompt-journal lifted to value).
