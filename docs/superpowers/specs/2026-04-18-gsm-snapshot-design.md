# GsmSnapshot — Design Spec

## Goal

Replace the live mutable `forge.game.Game` input to the GSM pipeline with a single immutable `GsmSnapshot` captured once at bundle entry. Every downstream stage (diff, events, actions, annotations, assembly) becomes a pure function of that snapshot. `game: Game` does not appear as a parameter inside `matchdoor/game/mapper/**` or `matchdoor/game/StateMapper.kt` after migration.

## Context

Follow-up to the matchdoor interaction-level refactor epic (`arena-lab-k8r`, PR #17, merged 2026-04-18). Phase 1 of that epic migrated six mutable flag-contract fields on `InteractivePromptBridge` + `GameBridge` to a typed `PromptJournal`. Same lever — values over places — applied one level deeper: the pipeline's state input, not just its side-effects channel.

Current smell (`BundleBuilder.kt` class KDoc):

> "every method that includes actions calls `StateMapper.buildDiffFromGame` *first*. Diff-building triggers instanceId reallocation for zone transfers — if actions were built before the diff, they'd reference pre-realloc instanceIds and the client couldn't match them."

That ordering invariant is a temporal coupling between stages that all read the same mutable `Game`. Reorder → silent wrong ids → client desync. No compiler help; the KDoc is the only safety net.

## Non-goals

- **Changing Forge internals.** `Game` stays mutable. We own the snapshot point; the engine stays as-is.
- **Event-sourcing the whole match.** We snapshot at bundle entry for pipeline purity. No history reconstruction, no replay server rewrite.
- **Unifying coordinator/handler pairs** (`PromptExchange` proposal) — separate bead, independent.
- **`PersistentAnnotationStore` O(n²) indexes** — trigger-gated; out of scope.
- **Splitting `GsmSnapshot` into narrow per-stage views** — start with a single monolithic snapshot. View decomposition is a later refactor if capture cost or ergonomics demand it.

## Architecture

### Pipeline shape — before

```
BundleBuilder.postAction(game, counter) {
    frame   = GsmFrame.from(game, bridge)                   // reads game
    result  = StateMapper.buildDiffFromGame(game, bridge)   // reads + mutates bridge.ids
    actions = ActionMapper.buildActions(seatId, bridge)     // reads bridge post-mutation
    phase/annotation stages also read game                  // ordering-sensitive
    embed(state, actions, frame)
}
```

Four separate reads of live `game` across stages. Ordering invariant documented in comments.

### Pipeline shape — after

```
BundleBuilder.postAction(game, counter) {
    snap    = GsmSnapshot.capture(game, bridge)             // one read, frozen
    diff    = StateMapper.buildDiff(bridge.lastSent, snap)  // pure function
    events  = EventCollector.collect(bridge.lastSent, snap, bridge.promptBridge(seat).journal)
    actions = ActionMapper.buildActions(snap, seatId)
    gsm     = GsmBuilder.assemble(snap, diff, events, actions, counter)
    bridge.lastSent = snap
    gsm
}
```

Stages are pure functions of the snapshot; `game` is not in scope inside any mapper. `bridge.ids` reallocation (the mutation that today happens inside diff-building) moves into an explicit step on the snapshot-diff result, applied against `bridge.ids` once per bundle.

## `GsmSnapshot` type

### Package + file layout

New package: `leyline.game.snapshot`.

```
matchdoor/src/main/kotlin/leyline/game/snapshot/
  GsmSnapshot.kt          — root type + capture() entry point
  SnapshotCapture.kt      — internal capture implementation (reads game + bridge)
  SeatSnapshot.kt         — per-seat data class
  ZoneSnapshot.kt         — per-zone data class
  CardSnapshot.kt         — per-card data class (grows as mappers migrate)
  StackSnapshot.kt        — stack contents (ordered)
  PhaseSnapshot.kt        — phase/step + active player + stops
  CombatSnapshot.kt       — attackers/blockers/damage (null outside combat)
  CaptureMarker.kt        — debug metadata (gsId source, wall-clock)
```

### Root type

```kotlin
package leyline.game.snapshot

class GsmSnapshot internal constructor(
    val matchId: String,
    val seats: List<SeatSnapshot>,
    val zones: Map<ZoneId, ZoneSnapshot>,
    val objects: Map<ForgeCardId, CardSnapshot>,
    val stack: StackSnapshot,
    val phase: PhaseSnapshot,
    val combat: CombatSnapshot?,
    val capturedAt: CaptureMarker,
) {
    companion object {
        /** Single entry point for production capture. */
        fun capture(game: Game, bridge: GameBridge): GsmSnapshot =
            SnapshotCapture.run(game, bridge)

        /** Test-only fixture builder — named args with sensible defaults. */
        @VisibleForTesting
        fun forTest(
            matchId: String = "test-match",
            seats: List<SeatSnapshot> = emptyList(),
            zones: Map<ZoneId, ZoneSnapshot> = emptyMap(),
            objects: Map<ForgeCardId, CardSnapshot> = emptyMap(),
            stack: StackSnapshot = StackSnapshot(emptyList()),
            phase: PhaseSnapshot = PhaseSnapshot(1, SeatId(1), PhaseType.Main1, null, emptyList()),
            combat: CombatSnapshot? = null,
            capturedAt: CaptureMarker = CaptureMarker.unknown(),
        ): GsmSnapshot = GsmSnapshot(matchId, seats, zones, objects, stack, phase, combat, capturedAt)
    }
}
```

The `internal` ctor blocks direct construction outside the package. Production code calls `capture(game, bridge)`; tests call `forTest(...)`.

### Sub-snapshot shapes

All data classes — `equals` / `hashCode` / `copy` free. Exact fields grow as mappers migrate; the initial shape covers `PlayerMapper`'s reads:

```kotlin
data class SeatSnapshot(
    val seatId: SeatId,
    val life: Int,
    val poison: Int,
    val librarySize: Int,
    val handSize: Int,
    val manaPool: ManaPoolSnapshot,
    val counters: Map<CounterType, Int>,
)

data class ManaPoolSnapshot(val w: Int, val u: Int, val b: Int, val r: Int, val g: Int, val c: Int)

data class ZoneSnapshot(
    val id: ZoneId,
    val type: ZoneType,
    val owner: SeatId?,
    val visibility: Visibility,
    val contents: List<ForgeCardId>,
)

data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,
    val zone: ZoneId,
    // grows per-mapper migration: power/toughness/tapped/keywords/counters/attachedTo/etc.
)

data class StackSnapshot(val entries: List<StackEntry>)
data class StackEntry(val forgeCardId: ForgeCardId, val controller: SeatId, val targets: List<ForgeCardId>)

data class PhaseSnapshot(
    val turn: Int,
    val activePlayer: SeatId,
    val phase: PhaseType,
    val step: StepType?,
    val stopPoints: List<StopPoint>,
)

data class CombatSnapshot(
    val attackers: Map<ForgeCardId, AttackTarget>,
    val blockers: Map<ForgeCardId, List<ForgeCardId>>,
    val damageAssignments: Map<ForgeCardId, Int>,
)

data class CaptureMarker(val gsIdBeforeCapture: Int, val wallClockMs: Long) {
    companion object { fun unknown() = CaptureMarker(-1, 0L) }
}
```

## Capture contract

`SnapshotCapture.run(game, bridge)`:

- Reads `game` + `bridge` only. No side effects on `game`. No side effects on `bridge` (id allocation happens later, against the snapshot's diff result).
- **Must be total** for every field any migrated mapper reads. Starts covering only `PlayerMapper`'s needs (seats + zones basic info); grows per-mapper PR.
- Deterministic: same `(game, bridge)` produces byte-equal `GsmSnapshot` (modulo `CaptureMarker.wallClockMs`, which is excluded from `equals`).
- **Sealed from mutation after capture.** All fields `val`. Collections are immutable (`List`/`Map`, not `MutableList`/`MutableMap`).

## Migration strategy: bottom-up, dual-check per mapper

All work lands on branch `snapshot` stacked off fresh `main` (merge commit `e66c5ff`). One PR at the end, commits grouped logically. Same single-branch pattern as `sessionops-split` (PR #17).

### Per-mapper migration template

Each mapper gets two commits:

**Commit A — "introduce snapshot overload + dual-check"**:
1. Grow `SnapshotCapture` + relevant sub-snapshot data classes to cover every field the target mapper reads.
2. Add `buildFromSnapshot(snap: GsmSnapshot, ...)` overload alongside the existing `buildFromGame(game: Game, ...)`. Internally, `buildFromSnapshot` reproduces the same output from snapshot.
3. In `BundleBuilder`, under `DevCheck` (no-op in prod, asserts in dev/tests):
   ```kotlin
   val fromSnap = Mapper.buildFromSnapshot(snap, ...)
   if (DevCheck.strict) {
       val fromGame = Mapper.buildFromGame(game, ...)
       check(fromSnap == fromGame) { "Mapper snapshot drift:\n  snap=$fromSnap\n  game=$fromGame" }
   }
   // real flow uses fromSnap
   ```
4. Run `:matchdoor:test` + `:matchdoor:testIntegration` with `DevCheck` forcing dual-path. Any drift → `SnapshotCapture` incomplete → fix + re-run.

**Commit B — "cut over + delete game overload"**:
1. Delete `Mapper.buildFromGame`.
2. Delete the dual-check block in `BundleBuilder`; only `buildFromSnapshot` call remains.
3. Run full gate (`:matchdoor:test` + `:matchdoor:testIntegration` + detekt + spotless).

The two commits land together in the final PR but are separately bisectable.

### Migration order

Actual stages in `matchdoor/src/main/kotlin/leyline/game/`:

| # | Stage | Why this order |
|---|---|---|
| 1 | `mapper/PlayerMapper` | Pattern-proof: smallest surface (life / mana / lib size), pure data, no ids, no zones. Establishes `GsmSnapshot` infra + `DevCheck` dual-check + detekt rule in the first commit. |
| 2 | `mapper/ZoneMapper` | Zones feed every other stage's lookups. Must land before stages that read `zones[...].contents`. |
| 3 | `mapper/ObjectMapper` | Per-card state (power/toughness/tapped/keywords/counters). Widest `CardSnapshot` growth here. |
| 4 | `mapper/ActionMapper` | Legal actions per seat. Depends on zones + objects already on snapshot. |
| 5 | `StateMapper.buildDiffFromGame` — phase/stack/combat reads fold into snapshot | Stack + phase + combat are currently read inline in `StateMapper`; migrate via `StackSnapshot`, `PhaseSnapshot`, `CombatSnapshot`. |
| 6 | `GameEventCollector` | Event collection depends on zone-change detection. Input switches from `(prev, cur: Game)` to `(prev, cur: GsmSnapshot)`. |
| 7 | `AnnotationBuilder` + `AnnotationOrderEnforcer` | Depends on events + object identity. Last because it's the most sensitive to ordering-invariant drift. |
| 8 | `GsmBuilder.assemble` | Final wire assembly. After this step, `BundleBuilder` is the only file in `matchdoor/game/` that imports `forge.game.Game`. |

After the last stage: a cleanup commit deletes `StateMapper.buildDiffFromGame`, renames to `StateMapper.buildDiff`, deletes the ordering-invariant KDoc in `BundleBuilder`, retires the `if (DevCheck.strict)` dual-check scaffolding.

### Commit stack on `snapshot` branch

```
feat(snapshot): GsmSnapshot + SnapshotCapture skeleton + detekt NoGameInMappers rule
refactor(mapper): dual-write PlayerMapper.buildFromSnapshot
refactor(mapper): cut over PlayerMapper to snapshot, delete buildFromGame
refactor(mapper): dual-write ZoneMapper.buildFromSnapshot
refactor(mapper): cut over ZoneMapper
refactor(mapper): dual-write ObjectMapper.buildFromSnapshot
refactor(mapper): cut over ObjectMapper
refactor(mapper): dual-write ActionMapper.buildFromSnapshot
refactor(mapper): cut over ActionMapper
refactor(game): fold StateMapper phase/stack/combat reads into snapshot pipeline
refactor(game): migrate GameEventCollector to snapshot input
refactor(game): migrate AnnotationBuilder + AnnotationOrderEnforcer
refactor(game): migrate GsmBuilder.assemble to snapshot-only signature
chore(snapshot): retire DevCheck dual-check, rename StateMapper.buildDiff, drop ordering-invariant KDoc
```

## Enforcement

Two mechanisms, layered:

1. **Package-level visibility.** `leyline.game.snapshot.SnapshotCapture` is `internal`. Only `GsmSnapshot.capture(...)` is public. Mappers import `GsmSnapshot`; they cannot reach back through to `game`.

2. **Detekt custom rule: `NoGameInMappers`** (addition to the existing leyline ruleset under `buildSrc/` or `detekt/`). Forbids any `import forge.game.Game` inside:
   - `matchdoor/src/main/kotlin/leyline/game/mapper/**`
   - `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`
   - `matchdoor/src/main/kotlin/leyline/game/EventCollector.kt` (new file, extracts collector logic)
   - `matchdoor/src/main/kotlin/leyline/game/GsmBuilder.kt`

   The rule fails the gate — no baseline entries accepted. Lands with commit 1 (`GsmSnapshot` skeleton) so every subsequent migration is verified.

3. **`BundleBuilder` keeps access** to `game` (it's the capture site and needs `game` to feed `SnapshotCapture`). That's the only file under `matchdoor/src/main/kotlin/leyline/game/` that may import `forge.game.Game` after the migration completes.

## Tests

The core win: **tests build snapshots directly, no Forge boot.**

After PR 1 lands, a mapper unit test looks like:

```kotlin
class PlayerMapperSnapshotTest : FunSpec({
    tags(UnitTag)

    test("player info reflects life + mana pool from snapshot") {
        val snap = GsmSnapshot.forTest(
            seats = listOf(
                SeatSnapshot(
                    seatId = SeatId(1),
                    life = 15,
                    poison = 0,
                    librarySize = 42,
                    handSize = 7,
                    manaPool = ManaPoolSnapshot(w = 0, u = 2, b = 0, r = 1, g = 0, c = 0),
                    counters = emptyMap(),
                )
            ),
        )
        val info = PlayerMapper.buildFromSnapshot(snap).first { it.seatId == 1 }
        info.life shouldBe 15
        info.manaPoolProto.getBlue() shouldBe 2
        info.manaPoolProto.getRed() shouldBe 1
    }
})
```

No `startWithBoard { }`, no Forge card DB init, no game loop, no tier selection. Sub-millisecond test. This is the payoff.

Existing tests (`ConformanceTestBase`, `SubsystemTest`, `MatchFlowHarness`) continue to cover end-to-end behaviour. Snapshot unit tests complement them at the mapper layer.

**Dual-check during migration** runs via `DevCheck` — asserts in CI (both `Gate` and `Integration` jobs), no-op at runtime. Any capture-incompleteness surfaces as a loud test failure with a diff.

## Acceptance criteria

- `GsmSnapshot` + sub-snapshot data classes land in `leyline.game.snapshot` package.
- `SnapshotCapture.run(game, bridge)` captures every field read by every migrated mapper.
- Every `matchdoor/game/mapper/**` file + `StateMapper.kt` drops `game: Game` parameter; `NoGameInMappers` detekt rule passes with no baseline entries.
- `bridge.lastSent: GsmSnapshot?` replaces the diff baseline fields on `DiffSnapshotter` (id registry + limbo stay).
- `BundleBuilder.postAction` (and siblings) captures the snapshot at entry, passes it through stages, assigns `bridge.lastSent` at exit.
- At least one mapper test uses a `GsmSnapshot.forTest(...)` literal fixture and runs without Forge boot.
- `:matchdoor:test` + `:matchdoor:testIntegration` green throughout the branch.
- `AnnotationShapeConformanceTest` (wire-invariant canary) passes.
- Ordering-invariant KDoc in `BundleBuilder` deleted.
- Branch `snapshot` lands as a single merge-commit PR off fresh `main`.

## Open questions

*(Answered during brainstorming — captured here for traceability)*

- **Granularity?** Single monolithic `GsmSnapshot`. Views later if capture cost forces it.
- **Migration?** Bottom-up per mapper, dual-check under `DevCheck`, one PR at the end stacking all commits on `snapshot` branch.
- **First bite?** `PlayerMapper` — smallest surface, proves infra without ordering-invariant risk.
- **Capture freshness?** Captured at `BundleBuilder.xxxBundle()` entry. The engine is single-threaded; SBA + triggers complete before control returns to the bundle-build seam. No mid-capture mutation.
