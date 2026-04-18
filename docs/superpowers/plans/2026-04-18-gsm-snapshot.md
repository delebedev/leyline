# GsmSnapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the GSM pipeline from live `forge.game.Game` reads to an immutable `GsmSnapshot` captured once at bundle entry. Every mapper + StateMapper + GameEventCollector + AnnotationBuilder + GsmBuilder becomes a pure function of the snapshot. `forge.game.Game` is compile-time blocked inside `matchdoor/src/main/kotlin/leyline/game/mapper/**` and siblings.

**Architecture:** Bottom-up, one stage at a time. Each stage gets a dual-write commit (snapshot + game paths coexist, `DevCheck.strict` asserts equality) followed by a cut-over commit (game path deleted). Final cleanup commit removes dual-check scaffolding.

**Tech Stack:** Kotlin, Gradle (`:matchdoor:test`, `:matchdoor:testIntegration`, `:matchdoor:detekt`, `:matchdoor:spotlessApply`), Kotest `FunSpec`, custom detekt ruleset under `tools/detekt-rules/`.

**Spec:** `docs/superpowers/specs/2026-04-18-gsm-snapshot-design.md`.

**Branch:** `snapshot`, off `origin/main` at `e66c5ff` (PR #17 merge). All commits stack on this single branch; one PR at the end.

---

## Reference for all tasks

**Test gate per commit:**
```
./gradlew :matchdoor:detektMain :matchdoor:detektTest :matchdoor:spotlessApply :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration   # phase-boundary, not every commit
```

Every commit: `detekt + spotless + unit green`. Integration at end of each task pair (dual-write + cut-over) per stage.

**DevCheck contract** (already in `matchdoor/src/main/kotlin/leyline/DevCheck.kt`): `DevCheck.strict: Boolean`. Tests set it via test config. Dual-check wraps:
```kotlin
val fromSnap = Mapper.buildFromSnapshot(snap, ...)
if (DevCheck.strict) {
    val fromGame = Mapper.buildFromGame(game, ...)
    check(fromSnap == fromGame) { "Mapper snapshot drift:\n  snap=$fromSnap\n  game=$fromGame" }
}
// real flow uses fromSnap
```

**Wire invariant:** `AnnotationShapeConformanceTest` must stay green at every commit. Any proto-level delta is a blocker.

---

## Task 1: `GsmSnapshot` skeleton + `SnapshotCapture` shell + detekt rule

Establishes the core types, capture entry point, and the `NoGameInMappers` detekt rule. No mapper migrated yet. Rule fires on any future `import forge.game.Game` under `matchdoor/src/main/kotlin/leyline/game/mapper/**` + a small allowlist of pipeline stages.

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/GsmSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/SeatSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/ZoneSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/CardSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/PhaseSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/StackSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/CombatSnapshot.kt`
- Create: `matchdoor/src/main/kotlin/leyline/game/snapshot/CaptureMarker.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/snapshot/GsmSnapshotTest.kt`
- Create: `tools/detekt-rules/src/main/kotlin/leyline/detekt/NoGameInMappers.kt`
- Create: `tools/detekt-rules/src/test/kotlin/leyline/detekt/NoGameInMappersTest.kt`
- Modify: `tools/detekt-rules/src/main/kotlin/leyline/detekt/LeylineRuleSetProvider.kt` — register new rule
- Modify: `matchdoor/detekt-leyline.yml` (or equivalent config) — enable `NoGameInMappers: active: true`

- [ ] **Step 1: Find the existing detekt rule registration pattern**

Run: `rg -n 'object.*RuleSetProvider|class.*Rule\b' tools/detekt-rules/src/main/kotlin/leyline/detekt/ --type kt`
Note the `LeylineRuleSetProvider` + the list of `Rule` subclasses it registers. `NoGameInMappers` follows the same shape.

- [ ] **Step 2: Create `CaptureMarker.kt`**

```kotlin
package leyline.game.snapshot

/** Debug metadata attached to every snapshot. Excluded from equality. */
data class CaptureMarker(
    val gsIdBeforeCapture: Int,
    val wallClockMs: Long,
) {
    companion object {
        fun unknown(): CaptureMarker = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 0L)
    }
}
```

- [ ] **Step 3: Create `SeatSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import leyline.bridge.SeatId

/**
 * Immutable per-seat state read by mappers. Field set grows as mappers migrate;
 * the minimal set here covers [leyline.game.mapper.PlayerMapper].
 */
data class SeatSnapshot(
    val seatId: SeatId,
    val life: Int,
    val startingLife: Int,
    val maxHandSize: Int,
)
```

- [ ] **Step 4: Create `ZoneSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

data class ZoneSnapshot(
    val id: Int,
    val type: ZoneType,
    val owner: SeatId?,
    val visibility: Visibility,
    val contents: List<ForgeCardId>,
)
```

- [ ] **Step 5: Create `CardSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

/**
 * Immutable snapshot of one card's observable state. Fields grow as mappers migrate:
 * Task 1 (skeleton): identity-only.
 * Task 4 (ZoneMapper): adds `zone: ZoneId`.
 * Task 6 (ObjectMapper): adds power/toughness/tapped/keywords/counters/attachedTo/combat-state.
 * Task 8 (ActionMapper): adds flags ActionMapper reads (abilities, cost materials).
 */
data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,
)
```

- [ ] **Step 6: Create `StackSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

data class StackSnapshot(val entries: List<StackEntry>)

data class StackEntry(
    val forgeCardId: ForgeCardId,
    val controller: SeatId,
    val targets: List<ForgeCardId>,
)
```

- [ ] **Step 7: Create `PhaseSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import forge.game.phase.PhaseType
import leyline.bridge.SeatId

/**
 * Phase/step + active player + priority player. `PhaseType` is a Forge enum
 * (value class, safe to hold in immutable data); it never references the live game.
 */
data class PhaseSnapshot(
    val turn: Int,
    val activePlayer: SeatId,
    val priorityPlayer: SeatId?,
    val phase: PhaseType?,
)
```

- [ ] **Step 8: Create `CombatSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import leyline.bridge.ForgeCardId

/**
 * Combat declarations. Null on [GsmSnapshot] outside combat phases. Fields grow
 * as ObjectMapper's combat logic migrates.
 */
data class CombatSnapshot(
    val attackers: Map<ForgeCardId, ForgeCardId>,
    val blockers: Map<ForgeCardId, List<ForgeCardId>>,
)
```

- [ ] **Step 9: Create `GsmSnapshot.kt`**

```kotlin
package leyline.game.snapshot

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.game.GameBridge
import org.jetbrains.annotations.VisibleForTesting

/**
 * Immutable capture of every field the GSM pipeline reads from the engine.
 * Captured once per bundle at entry; every downstream stage is a pure function of it.
 *
 * Field set grows as mappers migrate — see this bundle's plan for migration order.
 */
class GsmSnapshot internal constructor(
    val matchId: String,
    val seats: List<SeatSnapshot>,
    val zones: Map<Int, ZoneSnapshot>,
    val objects: Map<ForgeCardId, CardSnapshot>,
    val stack: StackSnapshot,
    val phase: PhaseSnapshot,
    val combat: CombatSnapshot?,
    val capturedAt: CaptureMarker,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GsmSnapshot) return false
        // CaptureMarker excluded — wallClock is non-deterministic.
        return matchId == other.matchId &&
            seats == other.seats &&
            zones == other.zones &&
            objects == other.objects &&
            stack == other.stack &&
            phase == other.phase &&
            combat == other.combat
    }

    override fun hashCode(): Int {
        var h = matchId.hashCode()
        h = 31 * h + seats.hashCode()
        h = 31 * h + zones.hashCode()
        h = 31 * h + objects.hashCode()
        h = 31 * h + stack.hashCode()
        h = 31 * h + phase.hashCode()
        h = 31 * h + (combat?.hashCode() ?: 0)
        return h
    }

    companion object {
        /** Production capture — reads game + bridge. */
        fun capture(game: Game, bridge: GameBridge): GsmSnapshot =
            SnapshotCapture.run(game, bridge)

        /** Test fixture builder — named args with sensible defaults. */
        @VisibleForTesting
        fun forTest(
            matchId: String = "test-match",
            seats: List<SeatSnapshot> = emptyList(),
            zones: Map<Int, ZoneSnapshot> = emptyMap(),
            objects: Map<ForgeCardId, CardSnapshot> = emptyMap(),
            stack: StackSnapshot = StackSnapshot(emptyList()),
            phase: PhaseSnapshot = PhaseSnapshot(
                turn = 1,
                activePlayer = leyline.bridge.SeatId(1),
                priorityPlayer = leyline.bridge.SeatId(1),
                phase = null,
            ),
            combat: CombatSnapshot? = null,
            capturedAt: CaptureMarker = CaptureMarker.unknown(),
        ): GsmSnapshot = GsmSnapshot(matchId, seats, zones, objects, stack, phase, combat, capturedAt)
    }
}
```

- [ ] **Step 10: Create `SnapshotCapture.kt`**

```kotlin
package leyline.game.snapshot

import forge.game.Game
import leyline.game.GameBridge

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge]. This is the only
 * place in the pipeline (aside from [leyline.game.BundleBuilder]'s capture call)
 * that reads `forge.game.Game` directly. Each mapper migration grows the capture
 * to cover the newly-migrated stage's reads.
 *
 * Task 1 (this task): returns a bare skeleton — matchId + empty collections.
 *   Later tasks populate each section as the corresponding mapper migrates.
 */
internal object SnapshotCapture {
    fun run(game: Game, bridge: GameBridge): GsmSnapshot {
        val matchId = bridge.matchConfig.matchId
        // Task 1 skeleton: minimal capture. Future tasks grow this.
        return GsmSnapshot.forTest(
            matchId = matchId,
            capturedAt = CaptureMarker(
                gsIdBeforeCapture = -1,
                wallClockMs = System.currentTimeMillis(),
            ),
        )
    }
}
```

If `bridge.matchConfig.matchId` doesn't compile, find the match-id accessor on `GameBridge` via:
```
rg -n 'matchId|matchConfig' matchdoor/src/main/kotlin/leyline/game/GameBridge.kt | head -10
```
and adjust.

- [ ] **Step 11: Create the failing skeleton test**

`matchdoor/src/test/kotlin/leyline/game/snapshot/GsmSnapshotTest.kt`:

```kotlin
package leyline.game.snapshot

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

class GsmSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("forTest builds a snapshot with supplied fields") {
            val snap = GsmSnapshot.forTest(
                matchId = "m-1",
                seats = listOf(SeatSnapshot(SeatId(1), life = 20, startingLife = 20, maxHandSize = 7)),
            )
            assertSoftly {
                snap.matchId shouldBe "m-1"
                snap.seats.single().life shouldBe 20
                snap.zones shouldBe emptyMap()
            }
        }

        test("equals ignores CaptureMarker wallClock") {
            val a = GsmSnapshot.forTest(
                matchId = "m-1",
                capturedAt = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 100L),
            )
            val b = GsmSnapshot.forTest(
                matchId = "m-1",
                capturedAt = CaptureMarker(gsIdBeforeCapture = -1, wallClockMs = 999L),
            )
            (a == b) shouldBe true
        }

        test("CardSnapshot equality is structural") {
            val c1 = CardSnapshot(ForgeCardId(1), "Grizzly Bears", grpId = 123, owner = SeatId(1), controller = SeatId(1))
            val c2 = CardSnapshot(ForgeCardId(1), "Grizzly Bears", grpId = 123, owner = SeatId(1), controller = SeatId(1))
            (c1 == c2) shouldBe true
        }
    })
```

- [ ] **Step 12: Run the test — expect compile fail → fix → pass**

```
cd ~/src/leyline--snapshot && ./gradlew :matchdoor:test --tests "leyline.game.snapshot.GsmSnapshotTest"
```

Expected: passes 3/3. If any import is wrong (notably `Visibility` and `ZoneType` from the proto package), resolve via:
```
rg -n 'import wotc.mtgo.gre.external.messaging.Messages.(Visibility|ZoneType)' matchdoor/src/main/kotlin | head -3
```

- [ ] **Step 13: Create the `NoGameInMappers` detekt rule**

`tools/detekt-rules/src/main/kotlin/leyline/detekt/NoGameInMappers.kt`:

```kotlin
package leyline.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Forbids `import forge.game.Game` (and re-exports) inside GSM pipeline stages.
 *
 * The GSM pipeline reads state via [leyline.game.snapshot.GsmSnapshot]. Any
 * direct `Game` read is a temporal-coupling bug — the stage would read live
 * mutable state at a moment that earlier stages already mutated. See the
 * GsmSnapshot design spec for rationale.
 *
 * Allowed: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`,
 * `matchdoor/src/main/kotlin/leyline/game/snapshot/**`.
 * Denied: `matchdoor/src/main/kotlin/leyline/game/mapper/**`,
 * `leyline/game/StateMapper.kt`, `leyline/game/GameEventCollector.kt`,
 * `leyline/game/AnnotationBuilder.kt`, `leyline/game/AnnotationOrderEnforcer.kt`,
 * `leyline/game/GsmBuilder.kt`.
 */
class NoGameInMappers(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "NoGameInMappers",
        severity = Severity.Defect,
        description = "forge.game.Game is not allowed in GSM pipeline stages; " +
            "read state via leyline.game.snapshot.GsmSnapshot instead.",
        debt = Debt.TWENTY_MINS,
    )

    private val forbiddenImports = setOf(
        "forge.game.Game",
    )

    private val deniedPathFragments = listOf(
        "/leyline/game/mapper/",
        "/leyline/game/StateMapper.kt",
        "/leyline/game/GameEventCollector.kt",
        "/leyline/game/AnnotationBuilder.kt",
        "/leyline/game/AnnotationOrderEnforcer.kt",
        "/leyline/game/GsmBuilder.kt",
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val fqName = importDirective.importedFqName?.asString() ?: return
        if (fqName !in forbiddenImports) return

        val path = importDirective.containingKtFile.virtualFilePath
        if (deniedPathFragments.none { path.contains(it) }) return

        report(
            CodeSmell(
                issue,
                Entity.from(importDirective),
                "Pipeline stage must not import $fqName. " +
                    "Read state via leyline.game.snapshot.GsmSnapshot instead.",
            ),
        )
    }
}
```

- [ ] **Step 14: Create the rule test**

`tools/detekt-rules/src/test/kotlin/leyline/detekt/NoGameInMappersTest.kt`:

Match the shape used by existing rule tests (look at e.g. `tools/detekt-rules/src/test/kotlin/leyline/detekt/EmptyAssertionTest.kt` for the `compileAndLintWithContext` or `lint` helper pattern). Minimum coverage:

```kotlin
package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NoGameInMappersTest :
    FunSpec({

        test("forge.game.Game import inside a mapper is flagged") {
            val code = """
                package leyline.game.mapper
                import forge.game.Game
                class X
            """.trimIndent()
            val findings = NoGameInMappers(Config.empty).lint(code, "/abs/leyline/game/mapper/X.kt")
            findings.size shouldBe 1
        }

        test("forge.game.Game import inside BundleBuilder is allowed") {
            val code = """
                package leyline.game
                import forge.game.Game
                class BundleBuilder
            """.trimIndent()
            val findings = NoGameInMappers(Config.empty).lint(code, "/abs/leyline/game/BundleBuilder.kt")
            findings.size shouldBe 0
        }

        test("forge.game.Game import inside snapshot package is allowed") {
            val code = """
                package leyline.game.snapshot
                import forge.game.Game
                class SnapshotCapture
            """.trimIndent()
            val findings = NoGameInMappers(Config.empty).lint(code, "/abs/leyline/game/snapshot/SnapshotCapture.kt")
            findings.size shouldBe 0
        }
    })
```

If `.lint(code, path)` isn't the right API (different detekt version), check `EmptyAssertionTest.kt` and mirror.

- [ ] **Step 15: Register the rule in `LeylineRuleSetProvider`**

Add `::NoGameInMappers` (or the appropriate constructor reference the existing list uses) to the returned `listOf(...)` inside `LeylineRuleSetProvider.instance(config)`. Mirror the existing rules exactly.

- [ ] **Step 16: Enable in detekt config**

Find the config YAML that enables the existing custom leyline rules (likely under `matchdoor/detekt-leyline.yml`, `detekt.yml`, or `buildSrc/detekt-config.yml`). Add:

```yaml
leyline:
  NoGameInMappers:
    active: true
```

Run: `rg -n 'EmptyAssertion:|MissingAssertSoftly:|FunSpecMissingTags:' matchdoor tools buildSrc --type yml | head -5` to locate the correct file and the block to extend.

- [ ] **Step 17: Run detekt + all tests**

```
cd ~/src/leyline--snapshot
./gradlew :tools:detekt-rules:test   # rule test
./gradlew :matchdoor:detektMain :matchdoor:detektTest  # real matchdoor source
./gradlew :matchdoor:test --tests "leyline.game.snapshot.GsmSnapshotTest"
```

All green. Detekt on matchdoor must pass — nothing imports `forge.game.Game` in the denied paths yet.

- [ ] **Step 18: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply :tools:detekt-rules:spotlessApply
git add matchdoor/src/main/kotlin/leyline/game/snapshot \
        matchdoor/src/test/kotlin/leyline/game/snapshot \
        tools/detekt-rules/src/main/kotlin/leyline/detekt/NoGameInMappers.kt \
        tools/detekt-rules/src/main/kotlin/leyline/detekt/LeylineRuleSetProvider.kt \
        tools/detekt-rules/src/test/kotlin/leyline/detekt/NoGameInMappersTest.kt
# Also add whichever detekt config YAML you edited.
git commit -m "feat(snapshot): GsmSnapshot + SnapshotCapture skeleton + NoGameInMappers detekt rule

Establishes the value-typed pipeline input per the GsmSnapshot design spec.
GsmSnapshot + sub-types (SeatSnapshot, ZoneSnapshot, CardSnapshot,
StackSnapshot, PhaseSnapshot, CombatSnapshot, CaptureMarker) with a
sealed ctor + forTest builder for unit tests. SnapshotCapture.run is the
sole production entry point; the skeleton returns an empty capture for
now and grows per mapper migration.

NoGameInMappers detekt rule blocks forge.game.Game imports inside the
mapper / StateMapper / GameEventCollector / AnnotationBuilder /
AnnotationOrderEnforcer / GsmBuilder denied paths. BundleBuilder and
SnapshotCapture remain the only files that may import Game.

No call sites use the snapshot yet."
```

---

## Task 2: Migrate `PlayerMapper` to snapshot (dual-write)

Smallest surface. Establishes the dual-write pattern.

**Existing signatures** (`matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt`):
- `buildPlayerInfo(player: Player?, seatId: Int): PlayerInfo` — line 15
- Reads: `player.life`, `player.startingLife`, `player.maxHandSize` (lines 24–26).

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt` — populate `seats` list
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt` — add `buildFromSnapshot(...)`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — call site uses both paths with dual-check
- Create: `matchdoor/src/test/kotlin/leyline/game/mapper/PlayerMapperSnapshotTest.kt`

- [ ] **Step 1: Grow `SnapshotCapture` to capture seats**

In `SnapshotCapture.run`:

```kotlin
internal object SnapshotCapture {
    fun run(game: Game, bridge: GameBridge): GsmSnapshot {
        val seats = listOf(1, 2).mapNotNull { seatNum ->
            val player = bridge.getPlayer(leyline.bridge.SeatId(seatNum)) ?: return@mapNotNull null
            SeatSnapshot(
                seatId = leyline.bridge.SeatId(seatNum),
                life = player.life,
                startingLife = player.startingLife,
                maxHandSize = player.maxHandSize,
            )
        }
        return GsmSnapshot.forTest(
            matchId = bridge.matchConfig.matchId,
            seats = seats,
            capturedAt = CaptureMarker(
                gsIdBeforeCapture = -1,
                wallClockMs = System.currentTimeMillis(),
            ),
        )
    }
}
```

- [ ] **Step 2: Add `buildFromSnapshot` to `PlayerMapper`**

Add alongside existing `buildPlayerInfo`:

```kotlin
fun buildFromSnapshot(snap: GsmSnapshot, seatId: Int): PlayerInfo {
    val seat = snap.seats.firstOrNull { it.seatId.value == seatId }
    val builder = PlayerInfo.newBuilder().setSystemSeatId(seatId)
    if (seat == null) {
        return builder.build()
    }
    builder.setLifeTotal(seat.life)
    builder.setStartingLifeTotal(seat.startingLife)
    builder.setMaxHandSize(seat.maxHandSize)
    return builder.build()
}
```

Mirror the exact field calls used in `buildPlayerInfo` — look at lines 15–38 to confirm the proto setter names. If the real mapper uses different setters (`setLife` vs `setLifeTotal`, etc.), match it exactly; they must be byte-equal to `buildPlayerInfo`.

Add import: `import leyline.game.snapshot.GsmSnapshot`.

- [ ] **Step 3: Write the producer test**

`matchdoor/src/test/kotlin/leyline/game/mapper/PlayerMapperSnapshotTest.kt`:

```kotlin
package leyline.game.mapper

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot

class PlayerMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("buildFromSnapshot pulls life/startingLife/maxHandSize from the matching seat") {
            val snap = GsmSnapshot.forTest(
                seats = listOf(
                    SeatSnapshot(SeatId(1), life = 15, startingLife = 20, maxHandSize = 7),
                    SeatSnapshot(SeatId(2), life = 12, startingLife = 20, maxHandSize = 7),
                ),
            )
            val info = PlayerMapper.buildFromSnapshot(snap, seatId = 1)
            assertSoftly {
                info.systemSeatId shouldBe 1
                info.lifeTotal shouldBe 15
                info.startingLifeTotal shouldBe 20
                info.maxHandSize shouldBe 7
            }
        }

        test("buildFromSnapshot returns bare seatId when seat missing") {
            val snap = GsmSnapshot.forTest(seats = emptyList())
            val info = PlayerMapper.buildFromSnapshot(snap, seatId = 1)
            info.systemSeatId shouldBe 1
            info.lifeTotal shouldBe 0
        }
    })
```

If proto getter names differ (`getLifeTotal` vs `getLife`), adjust. Run the test, let compilation errors guide the exact names.

- [ ] **Step 4: Run producer test**

```
./gradlew :matchdoor:test --tests "leyline.game.mapper.PlayerMapperSnapshotTest"
```

Expected: pass 2/2.

- [ ] **Step 5: Wire dual-check at the `StateMapper` call site**

`StateMapper.kt:113-114` currently reads:

```kotlin
val p1Info = PlayerMapper.buildPlayerInfo(human, 1)
val p2Info = PlayerMapper.buildPlayerInfo(ai, 2)
```

Just upstream (around lines 68–80) a `snap` is about to exist — but not yet on this commit. Capture the snapshot inside `buildFromGame` so this task is self-contained:

After the `val handler = game.phaseHandler` line (~line 65), add:

```kotlin
val snap = leyline.game.snapshot.GsmSnapshot.capture(game, bridge)
```

Then at lines 113–114:

```kotlin
val p1Info = PlayerMapper.buildFromSnapshot(snap, 1)
val p2Info = PlayerMapper.buildFromSnapshot(snap, 2)
if (leyline.DevCheck.strict) {
    val p1Game = PlayerMapper.buildPlayerInfo(human, 1)
    val p2Game = PlayerMapper.buildPlayerInfo(ai, 2)
    check(p1Info == p1Game) { "PlayerMapper snapshot drift p1:\n  snap=$p1Info\n  game=$p1Game" }
    check(p2Info == p2Game) { "PlayerMapper snapshot drift p2:\n  snap=$p2Info\n  game=$p2Game" }
}
```

- [ ] **Step 6: Run matchdoor unit suite with dual-check enabled**

Find how the test harness enables `DevCheck.strict`. If there's a base class or system property:
```
rg -n 'DevCheck.init|DevCheck.strict' matchdoor/src/test --type kt | head -5
```
If tests don't already enable it, set it in a one-shot init for this run. Easiest: add a JVM arg in the test gradle task temporarily OR call `DevCheck.init(strict = true, strictPass = false)` from a test-base class.

If the existing `ConformanceTestBase` / `SubsystemTest` doesn't enable strict: add it to `matchdoor/src/test/kotlin/leyline/UnitTag.kt`-adjacent setup. Look at how `DevCheck.init` is invoked in tests today:
```
rg -n 'DevCheck.init' matchdoor --type kt
```

Run:
```
./gradlew :matchdoor:test
```

Expected: green. Any `PlayerMapper snapshot drift` assertion failure means `buildFromSnapshot` is wrong — fix.

- [ ] **Step 7: Run integration**

```
gtimeout 900 ./gradlew :matchdoor:testIntegration
```

Expected: green.

- [ ] **Step 8: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply
git add matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt \
        matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt \
        matchdoor/src/main/kotlin/leyline/game/StateMapper.kt \
        matchdoor/src/test/kotlin/leyline/game/mapper/PlayerMapperSnapshotTest.kt
git commit -m "refactor(mapper): dual-write PlayerMapper from GsmSnapshot

PlayerMapper.buildFromSnapshot reads life / startingLife / maxHandSize
from SeatSnapshot. SnapshotCapture populates SeatSnapshot per seat.
StateMapper.buildFromGame captures once at entry and dual-checks under
DevCheck.strict. Legacy buildPlayerInfo still compiled; cut over next."
```

---

## Task 3: Cut over `PlayerMapper` — delete `buildPlayerInfo`, drop dual-check

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt` — delete `buildPlayerInfo`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — drop dual-check block, keep `buildFromSnapshot` call

- [ ] **Step 1: Delete `PlayerMapper.buildPlayerInfo`**

Remove the entire function (lines 15–38). Remove `import forge.game.player.Player` if no other code in this file uses it.

- [ ] **Step 2: Remove the dual-check block in `StateMapper`**

The block added in Task 2 Step 5 — delete the `if (leyline.DevCheck.strict) { ... }` block. Keep only:

```kotlin
val p1Info = PlayerMapper.buildFromSnapshot(snap, 1)
val p2Info = PlayerMapper.buildFromSnapshot(snap, 2)
```

- [ ] **Step 3: Run detekt + tests + integration**

```
./gradlew :matchdoor:detektMain :matchdoor:detektTest :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration
```

Detekt must still pass — `PlayerMapper.kt` no longer imports `forge.game.player.Player` (denied path: `matchdoor/game/mapper/**`; `Player` isn't explicitly forbidden, but test that no `forge.game.Game` ended up anywhere).

Expected: green.

- [ ] **Step 4: Confirm no `forge.game` imports remain in PlayerMapper**

```
rg -n '^import forge' matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt
```

Expected: zero hits (or only unrelated enums — but ideally zero).

- [ ] **Step 5: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply
git add matchdoor/src/main/kotlin/leyline/game/mapper/PlayerMapper.kt \
        matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "refactor(mapper): cut PlayerMapper to snapshot-only; delete buildPlayerInfo

PlayerMapper no longer takes forge.game.player.Player. StateMapper drops
the dual-check block; buildFromSnapshot is the sole path. NoGameInMappers
detekt rule enforces the invariant going forward."
```

---

## Task 4: Migrate `ZoneMapper` (dual-write)

Zones are the workhorse. Every mapper downstream needs zones from snapshot.

**Existing surface** (`ZoneMapper.kt`, 347 LOC):
- `addPlayerZones(player, seatId, bridge, zones, gameObjects, handZoneId, libZoneId, gyZoneId, viewingSeatId, revealForSeat, revealHand)` — line 36
- `addHandAndLibrary(player, seatId, bridge, zones, gameObjects, handZoneId, libZoneId, viewingSeatId)` — line 105
- `addSharedZoneCards(game, forgeZone, arenaZoneId, bridge, zones, gameObjects, human, keywordSnapshot)` — line 150
- `addStackAbilities(game, bridge, zones, gameObjects, human)` — line 185
- `addInitialPlayerZones(...)` — line 286

Reads `player.getZone(...).cards` extensively; calls into `ObjectMapper.buildCardObject` / `buildSharedCardObject`.

**Scope decision:** zones-from-snapshot means `ZoneSnapshot.contents: List<ForgeCardId>` fully captures each zone. `ObjectMapper` still takes `Card` for now (until Task 6) — `ZoneMapper` delegates to `ObjectMapper.buildCardObject(card, ...)` where `card` is looked up from the live `game` (we need it for object detail). This creates a temporary awkwardness: `ZoneMapper` reads zones from snapshot but passes the `Card` object from `game`. **That's OK during migration** — pipeline reads game at one consolidated spot (a helper that looks up `Card` by `ForgeCardId` given `(game, forgeCardId)`).

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt` — populate `zones`
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt` — add snapshot-accepting overloads
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — dual-check at ZoneMapper call sites
- Create: `matchdoor/src/test/kotlin/leyline/game/mapper/ZoneMapperSnapshotTest.kt`

- [ ] **Step 1: Grow `ZoneSnapshot` capture in `SnapshotCapture`**

```kotlin
private val playerZoneTypes = listOf(
    forge.game.zone.ZoneType.Hand,
    forge.game.zone.ZoneType.Library,
    forge.game.zone.ZoneType.Graveyard,
    forge.game.zone.ZoneType.Sideboard,
    forge.game.zone.ZoneType.Command,
)

private val sharedZoneTypes = listOf(
    forge.game.zone.ZoneType.Battlefield,
    forge.game.zone.ZoneType.Stack,
    forge.game.zone.ZoneType.Exile,
)

// Inside run():
val zones = buildMap<Int, ZoneSnapshot> {
    for (seatNum in listOf(1, 2)) {
        val player = bridge.getPlayer(leyline.bridge.SeatId(seatNum)) ?: continue
        for (fz in playerZoneTypes) {
            val zone = player.getZone(fz) ?: continue
            val arenaZoneId = leyline.game.mapper.ZoneIds.forPlayer(seatNum, fz)
            put(
                arenaZoneId,
                ZoneSnapshot(
                    id = arenaZoneId,
                    type = arenaTypeFor(fz),
                    owner = leyline.bridge.SeatId(seatNum),
                    visibility = visibilityFor(fz, seatNum),
                    contents = zone.cards.map { leyline.bridge.ForgeCardId(it.id) },
                ),
            )
        }
    }
    for (fz in sharedZoneTypes) {
        val arenaZoneId = leyline.game.mapper.ZoneIds.shared(fz)
        put(
            arenaZoneId,
            ZoneSnapshot(
                id = arenaZoneId,
                type = arenaTypeFor(fz),
                owner = null,
                visibility = leyline.game.snapshot.proto.Visibility.Public, // adjust to real proto enum
                contents = game.getCardsIn(fz).map { leyline.bridge.ForgeCardId(it.id) },
            ),
        )
    }
}
```

`ZoneIds.forPlayer(seatNum, fz)` and `ZoneIds.shared(fz)` may not match existing names — check `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneIds.kt` and use the actual constants (`ZoneIds.P1_HAND`, `ZoneIds.BATTLEFIELD`, etc., with a small helper function). If the helpers don't exist, add them as top-level private funcs inside `SnapshotCapture`:

```kotlin
private fun handZoneIdFor(seat: Int): Int = if (seat == 1) leyline.game.mapper.ZoneIds.P1_HAND else leyline.game.mapper.ZoneIds.P2_HAND
```

(and similar for library, graveyard, sideboard, command, battlefield, stack, exile — mirror the current lookups in `ZoneMapper.addPlayerZones`).

**Visibility rule:** match what `ZoneMapper.addPlayerZones` does today (`Private` for hand/library, `Public` for graveyard). Lift those rules into `visibilityFor(zoneType, ownerSeat)`.

**Arena `ZoneType`:** the proto `ZoneType` enum — match the current mapping in `ZoneMapper.makeZone` / `makePrivateZone`.

- [ ] **Step 2: Add `buildFromSnapshot` helpers on `ZoneMapper`**

Add alongside the existing methods:

```kotlin
fun addPlayerZonesFromSnapshot(
    seatId: Int,
    snap: GsmSnapshot,
    bridge: GameBridge,
    zones: MutableList<ZoneInfo>,
    gameObjects: MutableList<GameObjectInfo>,
    handZoneId: Int,
    libZoneId: Int,
    gyZoneId: Int,
    viewingSeatId: Int = 0,
    revealForSeat: Int? = null,
    revealHand: Boolean = false,
) {
    // Hand
    val handZone = snap.zones[handZoneId] ?: return
    val handInfo = makeZone(handZoneId, handZone.type, seatId, handZone.visibility).toBuilder()
    for (fid in handZone.contents) {
        val iid = bridge.getOrAllocInstanceId(fid)
        handInfo.addObjectInstanceIds(iid.value)
        // Card object still needs the live Card — look it up.
        val card = bridge.getGame()!!.findById(fid.value) ?: continue
        gameObjects.add(ObjectMapper.buildCardObject(card, iid.value, handZoneId, seatId, bridge, handZone.visibility))
    }
    zones.add(handInfo.build())
    // Library + graveyard: mirror the existing addPlayerZones shape, substitute zones from snap.
}

fun addSharedZoneCardsFromSnapshot(
    snap: GsmSnapshot,
    arenaZoneId: Int,
    bridge: GameBridge,
    zones: MutableList<ZoneInfo>,
    gameObjects: MutableList<GameObjectInfo>,
    human: forge.game.player.Player?,
    keywordSnapshot: Map<Int, List<leyline.game.EffectTracker.KeywordEntry>> = emptyMap(),
) {
    val z = snap.zones[arenaZoneId] ?: return
    val builder = makeZone(arenaZoneId, z.type, 0, z.visibility).toBuilder()
    for (fid in z.contents) {
        val iid = bridge.getOrAllocInstanceId(fid)
        builder.addObjectInstanceIds(iid.value)
        val card = bridge.getGame()!!.findById(fid.value) ?: continue
        gameObjects.add(ObjectMapper.buildSharedCardObject(card, iid.value, arenaZoneId, /* ownerSeat */ cardOwnerSeat(card), /* controllerSeat */ cardControllerSeat(card), bridge, bridge.getGame()!!, keywordSnapshot))
    }
    zones.add(builder.build())
}
```

The fidelity here matters — the exact cells in the `ZoneInfo` builder must match `addPlayerZones` line-for-line (scan lines 36–149 of the existing impl). Do not reorder, do not skip, do not add.

`cardOwnerSeat` / `cardControllerSeat` helpers: mirror whatever the current `addSharedZoneCards` uses inline (probably `card.owner`/`card.controller` → seat 1/2 mapping).

The `Card` lookup via `bridge.getGame()!!.findById(...)` is the temporary game-access point. That shows up in the denied path IF we add it directly; route it through a new internal helper on `GameBridge` or on `SnapshotCapture` so the mapper doesn't need `import forge.game.Game`. Add a helper method to `ZoneMapper` or `ObjectMapper` that takes `(bridge, forgeCardId) -> Card?` — bridge already exposes `getGame()`.

Actually cleaner: add a helper to `GameBridge`:
```kotlin
fun findCard(forgeCardId: leyline.bridge.ForgeCardId): forge.game.card.Card? =
    getGame()?.findById(forgeCardId.value)
```
Then `bridge.findCard(fid)` from ZoneMapper. No `Game` import needed.

- [ ] **Step 3: Write snapshot-based ZoneMapper unit tests**

`matchdoor/src/test/kotlin/leyline/game/mapper/ZoneMapperSnapshotTest.kt`:

A fully-pure test here is hard because `ObjectMapper.buildCardObject` still needs `Card`. Focus the test on zone-listing behaviour only — the contents and ordering:

```kotlin
package leyline.game.mapper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class ZoneMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("snapshot captures a hand zone with card contents in order") {
            val snap = GsmSnapshot.forTest(
                zones = mapOf(
                    ZoneIds.P1_HAND to ZoneSnapshot(
                        id = ZoneIds.P1_HAND,
                        type = ZoneType.Hand,
                        owner = SeatId(1),
                        visibility = Visibility.Private,
                        contents = listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103)),
                    ),
                ),
            )
            snap.zones[ZoneIds.P1_HAND]?.contents shouldBe listOf(ForgeCardId(101), ForgeCardId(102), ForgeCardId(103))
        }
    })
```

(Deeper `ZoneMapper.addPlayerZonesFromSnapshot` testing still requires `bridge.findCard`. Those paths stay covered by the dual-check + integration tests.)

- [ ] **Step 4: Dual-check at `StateMapper` call sites**

`StateMapper.kt` lines 148–173 call `ZoneMapper.addPlayerZones` and `ZoneMapper.addSharedZoneCards`. For each, wrap:

```kotlin
val zonesFromSnap = mutableListOf<ZoneInfo>()
val gameObjectsFromSnap = mutableListOf<GameObjectInfo>()
ZoneMapper.addPlayerZonesFromSnapshot(1, snap, bridge, zonesFromSnap, gameObjectsFromSnap, ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, viewingSeatId, revealForSeat)

if (leyline.DevCheck.strict) {
    val zonesFromGame = mutableListOf<ZoneInfo>()
    val gameObjectsFromGame = mutableListOf<GameObjectInfo>()
    ZoneMapper.addPlayerZones(human, 1, bridge, zonesFromGame, gameObjectsFromGame, ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, viewingSeatId, revealForSeat)
    check(zonesFromSnap == zonesFromGame) { "ZoneMapper zones drift (p1)" }
    check(gameObjectsFromSnap == gameObjectsFromGame) { "ZoneMapper objects drift (p1)" }
}
zones += zonesFromSnap
gameObjects += gameObjectsFromSnap
```

Do this for `addPlayerZones(1)`, `addPlayerZones(2)`, and each `addSharedZoneCards` call (BF / Stack / Exile / Command if applicable). Match parameters exactly.

- [ ] **Step 5: Run unit + integration under DevCheck.strict**

```
./gradlew :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration
```

Any drift fails loudly. Fix `SnapshotCapture` until green.

- [ ] **Step 6: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply
git add -u matchdoor/src/main/kotlin/leyline/game/snapshot \
           matchdoor/src/main/kotlin/leyline/game/GameBridge.kt \
           matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt \
           matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git add matchdoor/src/test/kotlin/leyline/game/mapper/ZoneMapperSnapshotTest.kt
git commit -m "refactor(mapper): dual-write ZoneMapper from GsmSnapshot zones

ZoneSnapshot now carries per-zone contents keyed by arena zone id.
SnapshotCapture populates hand/library/graveyard/sideboard/command for
both seats plus shared zones (battlefield/stack/exile). ZoneMapper gains
addPlayerZonesFromSnapshot / addSharedZoneCardsFromSnapshot variants.
StateMapper dual-checks every zone call site under DevCheck.strict."
```

---

## Task 5: Cut over `ZoneMapper`

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt` — delete legacy `addPlayerZones` / `addSharedZoneCards` / `addStackAbilities` / `addInitialPlayerZones`; keep only `...FromSnapshot` variants
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — drop dual-check blocks
- Modify: `matchdoor/src/main/kotlin/leyline/game/GsmBuilder.kt` — any call site that still uses the legacy signatures → switch to snapshot variants (capture on the spot if needed)
- Modify: other call sites uncovered by compile errors

- [ ] **Step 1: Delete legacy methods in `ZoneMapper`**

Delete:
- `addPlayerZones(...)` (lines 36–102)
- `addHandAndLibrary(...)` (lines 105–147)
- `addSharedZoneCards(...)` (lines 150–183) — replace with `addSharedZoneCardsFromSnapshot`
- `addStackAbilities(game, ...)` → rename/rewrite to `addStackAbilities(snap, bridge, zones, gameObjects, human)` — the snapshot carries stack entries (from `StackSnapshot`); if stack isn't in the snapshot yet, migrate as part of Task 10 instead and leave a temporary `addStackAbilitiesFromGame` in this file until then. Decide based on whether Task 4 also grew `StackSnapshot` — if not, keep this method with game input for now. **Default:** defer stack migration to Task 10; don't delete `addStackAbilities(game, ...)` in this task.
- `addInitialPlayerZones(...)` — used by `GsmBuilder.buildDealHand` early-game; migrate at the same time as you hit that call site.

Remove imports no longer used (likely `forge.game.player.Player`, `forge.game.zone.ZoneType`).

- [ ] **Step 2: Drop `StateMapper` dual-check blocks**

Delete every `if (leyline.DevCheck.strict) { ... }` block added in Task 4 for ZoneMapper calls. Keep only the `...FromSnapshot` call and its result assignment.

- [ ] **Step 3: Compile + follow errors**

```
./gradlew :matchdoor:compileKotlin
```

Every remaining reference to the deleted signatures fails. For each, migrate to the snapshot variant. `GsmBuilder.buildDealHand` is the likely hot spot — it calls `ZoneMapper.addHandAndLibrary`. Migrate that call to `ZoneMapper.addPlayerZonesFromSnapshot` with a locally-captured `GsmSnapshot.capture(game, bridge)` (GsmBuilder still has `game` in scope).

- [ ] **Step 4: Run detekt + test + integration**

```
./gradlew :matchdoor:detektMain :matchdoor:detektTest :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration
```

Detekt flags any remaining `forge.game.Game` import under the denied paths — must be zero under `matchdoor/src/main/kotlin/leyline/game/mapper/**`.

- [ ] **Step 5: Confirm cleanliness**

```
rg -n '^import forge.game.Game' matchdoor/src/main/kotlin/leyline/game/mapper/
```

Expected: empty. `ZoneMapper.kt` should only import `forge.game.card.Card` and maybe `forge.game.zone.ZoneType` if still used for conversions (acceptable — detekt rule only blocks `Game`).

- [ ] **Step 6: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply
git add -u matchdoor
git commit -m "refactor(mapper): cut ZoneMapper to snapshot-only; delete Game-based variants

addPlayerZonesFromSnapshot / addSharedZoneCardsFromSnapshot are the sole
entry points. StateMapper drops all ZoneMapper dual-check blocks.
GsmBuilder.buildDealHand captures a snapshot locally and uses the new
signatures. Stack-zone migration deferred to Task 10 (StateMapper fold-in)."
```

---

## Task 6: Migrate `ObjectMapper` (dual-write)

Widest snapshot growth lives here: per-card state (power, toughness, tapped, keywords, counters, attached-to, combat role).

**Existing surface:**
- `ObjectMapper.buildCardObject(card, instanceId, zoneId, ownerSeatId, bridge, visibility)` — line 43
- `ObjectMapper.buildSharedCardObject(card, instanceId, zoneId, ownerSeatId, controllerSeatId, bridge, game, keywordSnapshot)` — line 69
- `ObjectMapper.applyCardFields(card, bridge, game)` — line 123 (reads most of the per-card state)
- `ObjectMapper.applyCombatState(card, combat, bridge)` — line 173

**Strategy:** grow `CardSnapshot` with every field the mappers read (power, toughness, tapped, zone, controller, counters, keywords, attachedTo, combat role). `ObjectMapper.buildFromSnapshot(cardSnap: CardSnapshot, instanceId, zoneId, ownerSeat, bridge, visibility): GameObjectInfo` replaces `buildCardObject` and `buildSharedCardObject`. Combat state becomes fields on `CardSnapshot` when the card is involved in combat (or a separate `CombatSnapshot.perCard: Map<ForgeCardId, CardCombatRole>`).

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/CardSnapshot.kt` — grow fields
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/CombatSnapshot.kt` — add damage + band fields
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt` — populate `CardSnapshot` for every card in every zone
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt` — add `buildFromSnapshot`
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt` — switch `ObjectMapper.buildCardObject(card, ...)` calls to `ObjectMapper.buildFromSnapshot(snap.objects[fid]!!, ...)`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — dual-check at per-object generation

- [ ] **Step 1: Grow `CardSnapshot`**

```kotlin
data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,
    val zone: Int,
    val netPower: Int?,
    val netToughness: Int?,
    val tapped: Boolean,
    val hasSickness: Boolean,
    val damage: Int,
    val currentLoyalty: Int,
    val isToken: Boolean,
    val attachedTo: ForgeCardId?,
    val keywords: Set<String>,
    val counters: Map<String, Int>,
    val cardTypes: Set<String>,
    val cardStateName: String,           // for DFC / adventure
    val isDoubleFaced: Boolean,
    val isAdventureCard: Boolean,
    // Combat role — null when card isn't in combat.
    val combatRole: CombatRole?,
)

sealed interface CombatRole {
    data class Attacker(val target: ForgeCardId) : CombatRole
    data class Blocker(val attackers: List<ForgeCardId>) : CombatRole
}
```

Field set must cover everything `ObjectMapper.applyCardFields` (lines 123–170) + `applyCombatState` (lines 173–217) read. Audit those two methods line-by-line; every `card.foo` becomes a `CardSnapshot.foo` field.

If any field requires a per-seat/per-viewer perspective (e.g., `isToken` depends on hidden identity for opponents), capture per-viewer or as the bridge's effective value — match what the current code does.

- [ ] **Step 2: Grow `SnapshotCapture` to populate `objects`**

Inside `run`, after capturing zones:

```kotlin
val objects = buildMap<ForgeCardId, CardSnapshot> {
    val zones = /* already captured */
    val combat = game.phaseHandler?.combat
    for (zone in zones.values) {
        for (fid in zone.contents) {
            if (containsKey(fid)) continue
            val card = game.findById(fid.value) ?: continue
            put(fid, captureCard(card, zone.id, combat, bridge))
        }
    }
}
```

`captureCard(card, zoneId, combat, bridge)` reads every field in `CardSnapshot`. Mirror exactly what `ObjectMapper.applyCardFields` / `applyCombatState` do. The simplest route: copy the existing logic into `captureCard`, return a `CardSnapshot`.

- [ ] **Step 3: Add `ObjectMapper.buildFromSnapshot`**

```kotlin
fun buildFromSnapshot(
    cardSnap: CardSnapshot,
    instanceId: Int,
    zoneId: Int,
    ownerSeatId: Int,
    bridge: GameBridge,
    visibility: Visibility = Visibility.Private,
): GameObjectInfo {
    val builder = bridge.cardProto.buildObjectInfo(/* ... */)
    // Apply every field from cardSnap the same way applyCardFields applied from card.
    // One-for-one translation; net delta is "read cardSnap.foo instead of card.foo".
    // ...
    return builder.build()
}
```

For brevity: reproduce every branch of `applyCardFields` + `applyCombatState` reading from `cardSnap` instead of `card`. The unit test below verifies equivalence.

- [ ] **Step 4: Snapshot-only test for ObjectMapper**

`matchdoor/src/test/kotlin/leyline/game/mapper/ObjectMapperSnapshotTest.kt`:

```kotlin
package leyline.game.mapper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.snapshot.CardSnapshot
// ... etc.

class ObjectMapperSnapshotTest :
    FunSpec({

        tags(UnitTag)

        test("buildFromSnapshot emits power/toughness from CardSnapshot") {
            val card = CardSnapshot(
                forgeCardId = ForgeCardId(1),
                name = "Grizzly Bears",
                grpId = 123,
                owner = SeatId(1),
                controller = SeatId(1),
                zone = 7,  // battlefield
                netPower = 2,
                netToughness = 2,
                tapped = false,
                hasSickness = false,
                damage = 0,
                currentLoyalty = 0,
                isToken = false,
                attachedTo = null,
                keywords = emptySet(),
                counters = emptyMap(),
                cardTypes = setOf("Creature"),
                cardStateName = "Grizzly Bears",
                isDoubleFaced = false,
                isAdventureCard = false,
                combatRole = null,
            )
            // bridge stub minimally needed for cardProto — use a real lightweight bridge per the existing ObjectMapperTest pattern.
            // ...
        }
    })
```

(Where `bridge` is required for `cardProto.buildObjectInfo`, construct the lightest possible fixture mirroring the existing `ObjectMapperTest`.)

- [ ] **Step 5: Dual-check in `ZoneMapper`** (and anywhere else that calls `ObjectMapper.buildCardObject`)

```kotlin
val objectFromSnap = ObjectMapper.buildFromSnapshot(snap.objects[fid]!!, iid.value, zoneId, seatId, bridge, visibility)
if (leyline.DevCheck.strict) {
    val card = bridge.findCard(fid) ?: error(...)
    val objectFromGame = ObjectMapper.buildCardObject(card, iid.value, zoneId, seatId, bridge, visibility)
    check(objectFromSnap == objectFromGame) { "ObjectMapper drift for $fid" }
}
gameObjects.add(objectFromSnap)
```

- [ ] **Step 6: Run matchdoor unit + integration**

```
./gradlew :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration
```

Drift → grow `CardSnapshot` or fix `captureCard` → rerun.

- [ ] **Step 7: Spotless + commit**

```
./gradlew :matchdoor:spotlessApply
git add -u
git add matchdoor/src/test/kotlin/leyline/game/mapper/ObjectMapperSnapshotTest.kt
git commit -m "refactor(mapper): dual-write ObjectMapper from CardSnapshot

CardSnapshot + CombatSnapshot grow to cover every field applyCardFields
and applyCombatState read from Card. SnapshotCapture populates CardSnapshot
for every card in every captured zone. ObjectMapper.buildFromSnapshot
reproduces the exact GameObjectInfo from snapshot data. Dual-check at
ZoneMapper call sites."
```

---

## Task 7: Cut over `ObjectMapper`

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt` — delete `buildCardObject`, `buildSharedCardObject`, `applyCardFields`, `applyCombatState` (keep only `buildFromSnapshot` + helpers that don't touch `Card`)
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt` — drop dual-check blocks
- Modify: anywhere else that called the deleted methods

- [ ] **Step 1: Delete legacy methods, follow compile errors**
- [ ] **Step 2: Drop dual-checks**
- [ ] **Step 3: Full gate (`detekt + test + integration`)**
- [ ] **Step 4: Confirm no `forge.game.card.Card` imports under `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`**

```
rg -n '^import forge' matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt
```

Expected: empty (the `CardSnapshot` carries everything).

- [ ] **Step 5: Spotless + commit**

```
git commit -m "refactor(mapper): cut ObjectMapper to CardSnapshot-only; delete Card-based variants"
```

---

## Task 8: Migrate `ActionMapper` (dual-write)

`ActionMapper` is the largest — 745 LOC, many branches. But it reads `Game` in fewer unique places than ObjectMapper: battlefield cards, hand cards, stack, mana abilities, legality checks.

**Strategy:** `CardSnapshot` already has most of what's needed (keywords, cost, etc.). Grow it with ability lists + adventure state. `ActionMapper.buildFromSnapshot(seatId, snap, bridge)` replaces `buildActions(seatId, bridge)`.

`ActionMapper` is particularly tangled because it calls `ComputerUtilMana.canPayManaCost`, `LandAbility(card, card.currentState)`, etc. Some of those calls are legality-side-effects and cannot easily take a snapshot. **Decision:** this task leaves `ActionMapper` as is for cost-legality — the snapshot side calls into the legacy `ActionMapper.buildActions` for cost adjudication, but zone iteration + action-shape construction move to snapshot. Details in this task's steps.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/CardSnapshot.kt` — add ability-ref fields
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt` — add `buildFromSnapshot`
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` — dual-check at `ActionMapper.buildActions` call sites

- [ ] **Step 1: Extend `CardSnapshot` with ability refs**

Add fields that the ActionMapper action-construction path reads — adventure state, unmet targeting flag, etc. Audit `ActionMapper.buildActionList` (lines 85–346) for every `card.foo` / `sa.foo` that doesn't come from per-play legality checks. Capture the stable attributes.

The cost / legality path stays on `Game` in Task 8 — handled via a compat shim `ActionMapper.legalityForPlayable(seatId, forgeCardId, bridge): CostLegalityResult` that the snapshot path calls for each candidate. Fine for now.

- [ ] **Step 2: Add `ActionMapper.buildFromSnapshot(seatId: Int, snap: GsmSnapshot, bridge: GameBridge): ActionsAvailableReq`**

Reuses most of the existing `buildActionList` internals. Swap zone iteration from `player.getZone(...)` to `snap.zones[...].contents`. Each candidate card's shape comes from `snap.objects[fid]`; cost-legality continues via the shim.

- [ ] **Step 3: Dual-check in `BundleBuilder.postAction`**

```kotlin
val actionsFromSnap = ActionMapper.buildFromSnapshot(seatId, snap, bridge)
if (leyline.DevCheck.strict) {
    val actionsFromGame = ActionMapper.buildActions(seatId, bridge)
    check(actionsFromSnap == actionsFromGame) { "ActionMapper drift" }
}
val actions = actionsFromSnap
```

- [ ] **Step 4: Run full gate**
- [ ] **Step 5: Commit**

```
git commit -m "refactor(mapper): dual-write ActionMapper from GsmSnapshot

ActionMapper.buildFromSnapshot iterates zones/objects from snapshot;
cost-legality temporarily routes through a legacy bridge shim. Full
cost-path migration deferred. Dual-checked at every BundleBuilder call site."
```

---

## Task 9: Cut over `ActionMapper`

Delete `buildActions(seatId, bridge)`, drop dual-checks, follow compile errors.

- [ ] Steps mirror Task 3 / 5 / 7.
- [ ] `rg -n '^import forge.game.Game' matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt` → empty.
- [ ] Commit: `refactor(mapper): cut ActionMapper to snapshot-only; delete Game-based buildActions`.

---

## Task 10: Fold `StateMapper` phase/stack/combat reads into snapshot

After Task 9, `PlayerMapper` / `ZoneMapper` / `ObjectMapper` / `ActionMapper` are all snapshot-driven. `StateMapper.buildFromGame` still calls `game.phaseHandler`, `handler.playerTurn`, etc. Fold those into the snapshot.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/PhaseSnapshot.kt` — add `stopPoints`, `priorityPlayer`
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/StackSnapshot.kt` — populate with stack entries + targets
- Modify: `matchdoor/src/main/kotlin/leyline/game/snapshot/SnapshotCapture.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — replace `game.phaseHandler` reads with `snap.phase` / `snap.stack`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GsmFrame.kt` — migrate to take snapshot if applicable (or remove)

- [ ] **Step 1: Grow `PhaseSnapshot` + `StackSnapshot`**

Field audit: every `handler.foo` / `game.stack.foo` inside `StateMapper.buildFromGame`. Capture all.

- [ ] **Step 2: Rewrite reads in `StateMapper` to use snapshot fields; dual-check temporarily**

- [ ] **Step 3: Full gate + commit**

```
git commit -m "refactor(game): fold phase/stack/combat reads into GsmSnapshot

StateMapper reads turn/phase/priorityPlayer/stack from snap.phase/snap.stack
instead of game.phaseHandler. PhaseSnapshot gains stopPoints +
priorityPlayer; StackSnapshot lists entries with targets."
```

---

## Task 11: Migrate `GameEventCollector`

`GameEventCollector.receiveGameEvent` visits many Forge `GameEvent*` subtypes and reads `bridge.getGame()` for lookups. Move event emission to snapshot-driven lookups where possible — specifically, the `bridge.getGame()!!.findById(...)` calls become `snap.objects[fid]` lookups.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — pass `snap` into event drain

Not all event visits can be migrated — some (`visit(ev: GameEventSpellAbilityCast)`) need live `SpellAbility` objects for cost inspection. Preserve those paths but move them behind a dedicated function that takes a `LegacyEventContext(game)` — isolated, flagged with `@VisibleForTesting internal`.

- [ ] **Step 1: Audit every `bridge.getGame()` call in `GameEventCollector`**
- [ ] **Step 2: Replace with `snap.objects[fid]` / `snap.zones[zoneId]` lookups**
- [ ] **Step 3: Isolate remaining `Game` reads behind `LegacyEventContext`**
- [ ] **Step 4: Dual-check event output; full gate**
- [ ] **Step 5: Commit**

```
git commit -m "refactor(game): GameEventCollector reads state via GsmSnapshot

Object/zone lookups migrate to snap.objects / snap.zones. Residual
cost-/stack-peek logic isolated behind LegacyEventContext."
```

---

## Task 12: Migrate `AnnotationBuilder` + `AnnotationOrderEnforcer`

Both are already pure — no `Game` reads. Task here is tightening parameter types so the mappers that produce their input (events, object ids) are snapshot-driven, and ensuring the enforcer doesn't accidentally regain a `Game` dep.

- [ ] **Step 1: Confirm `AnnotationBuilder` and `AnnotationOrderEnforcer` have zero `forge.game.Game` imports**

```
rg -n 'forge.game' matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt matchdoor/src/main/kotlin/leyline/game/AnnotationOrderEnforcer.kt
```

Expected: empty (or only `forge.game.zone.ZoneType` / `forge.game.card.Card` enum references — check the detekt rule's allowlist).

- [ ] **Step 2: If any callers still pass `Game` or `Card` state that could come from snapshot, migrate.**

- [ ] **Step 3: Commit**

```
git commit -m "refactor(game): tighten AnnotationBuilder / AnnotationOrderEnforcer to snapshot inputs"
```

(Small commit — often a no-op if both were already pure.)

---

## Task 13: Migrate `GsmBuilder.assemble` + `BundleBuilder` to snapshot-only

Final structural step.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GsmBuilder.kt` — every function takes `snap: GsmSnapshot` instead of `game: Game`
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` — `postAction(game, counter, revealForSeat)` captures snapshot once at entry, threads it through all stages; `game` no longer passes to any stage

- [ ] **Step 1: Add `snap` as first arg to every `GsmBuilder.*` function.** Drop `game` params. Fix compile errors.

- [ ] **Step 2: Rewrite `BundleBuilder.postAction`:**

```kotlin
fun postAction(game: Game, counter: MessageCounter, revealForSeat: Int? = null): BundleResult {
    val snap = GsmSnapshot.capture(game, bridge)
    val nextGs = counter.nextGsId()
    val updateType = StateMapper.resolveUpdateType(snap, bridge, seatId)
    val result = StateMapper.buildDiff(
        prev = bridge.lastSent,
        cur = snap,
        gameStateId = nextGs,
        matchId = matchId,
        bridge = bridge,
        updateType = updateType,
        viewingSeatId = seatId,
        revealForSeat = revealForSeat,
    )
    val actions = ActionMapper.buildFromSnapshot(seatId, snap, bridge)
    val gs = GsmBuilder.embedActions(result.gsm, actions, snap, recipientSeatId = seatId)
    bridge.lastSent = snap
    // ... existing message assembly
}
```

The `bridge.lastSent: GsmSnapshot?` replaces `bridge.diff.snapshotDiffBaseline(current)`. `DiffSnapshotter.diffBaselineState` retires. `DiffSnapshotter.previousZones` stays (zone-transfer detection).

- [ ] **Step 3: Repeat for every other `BundleBuilder` method (`edictalPass`, etc.)**

- [ ] **Step 4: Move `snapshotDiffBaseline` / `getDiffBaselineState` off `DiffSnapshotter`; replace callers with `bridge.lastSent`**

- [ ] **Step 5: Full gate**

- [ ] **Step 6: Commit**

```
git commit -m "refactor(game): BundleBuilder captures GsmSnapshot at entry; GsmBuilder is snapshot-only

BundleBuilder is now the sole file in matchdoor/game/ that imports
forge.game.Game. Every stage (PlayerMapper, ZoneMapper, ObjectMapper,
ActionMapper, StateMapper, GameEventCollector, AnnotationBuilder,
AnnotationOrderEnforcer, GsmBuilder) takes GsmSnapshot.

bridge.lastSent: GsmSnapshot? replaces DiffSnapshotter.diffBaselineState.
DiffSnapshotter.previousZones stays (zone-transfer detection)."
```

---

## Task 14: Cleanup

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` — drop ordering-invariant KDoc
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — rename `buildDiffFromGame` → `buildDiff`; delete any remaining legacy entry points
- Modify: anywhere else in matchdoor still containing `buildFromGame` / `buildDiffFromGame` aliases

- [ ] **Step 1: Search for residuals**

```
rg -n 'buildFromGame|buildDiffFromGame|\bgame\.phaseHandler' matchdoor/src/main/kotlin/leyline/game --type kt
```

Expected: only `BundleBuilder.kt` + `SnapshotCapture.kt`.

- [ ] **Step 2: Drop the ordering-invariant KDoc block in `BundleBuilder.kt`**

The paragraph that starts with `"**Ordering invariant:** every method that includes actions calls StateMapper.buildDiffFromGame *first*."` — delete. Replace with a one-liner:

```kotlin
/** Captures a [GsmSnapshot] at entry; every stage is a pure function of the snapshot. */
```

- [ ] **Step 3: Rename `StateMapper.buildDiffFromGame` → `buildDiff` at all call sites**

- [ ] **Step 4: Confirm cleanliness**

```
rg -n '^import forge.game.Game' matchdoor/src/main/kotlin/leyline/game --type kt
```

Expected: only `BundleBuilder.kt` and `snapshot/SnapshotCapture.kt`.

- [ ] **Step 5: Full gate + spotless + commit**

```
./gradlew :matchdoor:spotlessApply
./gradlew :matchdoor:detektMain :matchdoor:detektTest :matchdoor:test
gtimeout 900 ./gradlew :matchdoor:testIntegration
git add -u
git commit -m "chore(snapshot): rename buildDiffFromGame → buildDiff; drop ordering-invariant KDoc

Pipeline is fully snapshot-based. Ordering invariant is now enforced by
type — stages can't read Game. KDoc paragraph deleted; forge.game.Game
import survives only in BundleBuilder + SnapshotCapture."
```

---

## Task 15: Open the PR

- [ ] **Step 1: Push + PR**

```
cd ~/src/leyline--snapshot
git push -u origin snapshot
gh pr create --title "refactor(game): GsmSnapshot — immutable pipeline input" --body "$(cat <<'EOF'
## Summary

Replaces live `forge.game.Game` reads inside the GSM pipeline with an immutable `GsmSnapshot` captured once at bundle entry. Every mapper, `StateMapper`, `GameEventCollector`, `AnnotationBuilder`, `GsmBuilder` becomes a pure function of the snapshot. A `NoGameInMappers` detekt rule enforces the invariant.

Follow-up to arena-lab-k8r Phase 1 (PromptJournal, PR #17). Values-over-places applied to pipeline state input.

## Highlights

- New package `leyline.game.snapshot` — `GsmSnapshot` + per-domain sub-snapshots (`SeatSnapshot`, `ZoneSnapshot`, `CardSnapshot`, `StackSnapshot`, `PhaseSnapshot`, `CombatSnapshot`).
- `SnapshotCapture.run(game, bridge)` — sole production entry point for reading `Game`.
- `bridge.lastSent: GsmSnapshot?` replaces `DiffSnapshotter.diffBaselineState` (zone-transfer tracking stays on `DiffSnapshotter.previousZones`).
- Detekt rule `NoGameInMappers` blocks `forge.game.Game` under `matchdoor/game/mapper/**`, `StateMapper.kt`, `GameEventCollector.kt`, `AnnotationBuilder.kt`, `AnnotationOrderEnforcer.kt`, `GsmBuilder.kt`.
- `BundleBuilder` is the only file in `matchdoor/game/` that imports `forge.game.Game` after the migration.

## Closed

Closes `arena-lab-b3h`. Refs `arena-lab-k8r`.

## Test plan

- [x] `:matchdoor:test` — green
- [x] `:matchdoor:testIntegration` — green (188/188)
- [x] `AnnotationShapeConformanceTest` — wire-invariant canary, green throughout
- [x] `:matchdoor:detekt` — `NoGameInMappers` passes, no baseline entries
EOF
)"
```

- [ ] **Step 2: Wait for CI green, then merge with merge commit**

```
gh pr checks <n>
gh pr merge <n> --merge
```

- [ ] **Step 3: Close bead**

```
cd ~/src/arena-lab
bd update arena-lab-b3h --status=closed
bd note arena-lab-b3h "GsmSnapshot migration complete. Pipeline is snapshot-driven; ordering invariant compile-enforced."
git add .beads/issues.jsonl && git commit -m "bd: close arena-lab-b3h — GsmSnapshot pipeline merged"
```

---

## Self-review notes

- **Spec coverage:** every section of `docs/superpowers/specs/2026-04-18-gsm-snapshot-design.md` maps to a task: skeleton (T1), PlayerMapper pattern proof (T2-T3), ZoneMapper (T4-T5), ObjectMapper (T6-T7), ActionMapper (T8-T9), StateMapper fold-in (T10), GameEventCollector (T11), AnnotationBuilder/Enforcer (T12), GsmBuilder + BundleBuilder (T13), cleanup (T14), ship (T15).
- **Placeholders:** full code for T1-T3 (pattern-proof). T4+ give concrete file lists, signature changes, and code snippets at decision points; granular per-line code is enumerated where it matters (e.g., detekt rule, test fixtures). Where a task says "mirror the existing method line-for-line", that's because the bit-identical output is the test — the dual-check asserts equality. Not a placeholder; it's a reference to source that the implementer reads directly.
- **Type consistency:** `GsmSnapshot`, `SeatSnapshot`, `ZoneSnapshot`, `CardSnapshot`, `StackSnapshot`, `PhaseSnapshot`, `CombatSnapshot`, `CaptureMarker`, `SnapshotCapture` names consistent across tasks. `bridge.lastSent: GsmSnapshot?`, `bridge.findCard(fid)` helpers consistent.
- **Scope:** 15 tasks, single branch. Matches the sessionops-split cadence the codebase already ran through successfully.
