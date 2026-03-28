# AbilityWordActive Persistent Annotation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit `AbilityWordActive` persistent annotations so the client shows ability word badges/counters (Threshold, Morbid, Raid, Descended, etc.)

**Architecture:** Each GSM, scan battlefield permanents for Forge traits with ability word conditions (`Condition$` on StaticAbility, `Threshold$`/`Morbid$` etc. on Triggers). Build a set of AbilityWordActive pAnns with current values. Feed into `PersistentAnnotationStore.computeBatch()` with upsert semantics (keyed on instanceId + AbilityWordName). `AbilityRegistry.forStaticAbility()`/`forTrigger()` resolves the abilityGrpId.

**Tech Stack:** Kotlin, Kotest FunSpec, protobuf (messages.proto), Forge engine API

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt` | Modify | Add ABILITY_WORD_NAME, THRESHOLD detail key constants |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Modify | Add `abilityWordActive()` builder method |
| `matchdoor/src/main/kotlin/leyline/game/AbilityWordScanner.kt` | Create | Scan battlefield cards for ability word conditions, compute values, resolve abilityGrpIds |
| `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt` | Modify | Add AbilityWordActive upsert in `computeBatch()` step 3 |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` | Modify | Add `abilityWordPersistent` field to `MechanicAnnotationResult` |
| `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` | Modify | Wire scanner into `computeRemainingAnnotations()` |
| `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt` | Modify | Test builder proto shape |
| `matchdoor/src/test/kotlin/leyline/game/AbilityWordScannerTest.kt` | Create | Test condition detection + value computation |
| `matchdoor/src/test/kotlin/leyline/game/AbilityWordPipelineTest.kt` | Create | Test computeBatch upsert/delete lifecycle |
| `matchdoor/src/test/kotlin/leyline/game/AbilityWordPuzzleTest.kt` | Create | Integration: Threshold creature on board, verify pAnn in GSM |

---

### Task 1: AnnotationBuilder — abilityWordActive builder

Add the builder method and detail key constants.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt`

- [ ] **Step 1: Write failing test for quantitative AbilityWordActive (Threshold shape)**

In `AnnotationBuilderTest.kt`, add:

```kotlin
test("abilityWordActiveQuantitative") {
    val ann = AnnotationBuilder.abilityWordActive(
        instanceId = 295,
        abilityWordName = "Threshold",
        value = 5,
        threshold = 7,
        abilityGrpId = 175886,
    )
    ann.typeList shouldContain AnnotationType.AbilityWordActive
    assertSoftly {
        ann.affectorId shouldBe 295
        ann.affectedIdsList shouldBe listOf(295)
        ann.detailString("AbilityWordName") shouldBe "Threshold"
        ann.detailInt("value") shouldBe 5
        ann.detailInt("threshold") shouldBe 7
        ann.detailInt("AbilityGrpId") shouldBe 175886
    }
}

test("abilityWordActiveKeywordOnly") {
    val ann = AnnotationBuilder.abilityWordActive(
        instanceId = 303,
        abilityWordName = "Descended",
        affectorId = 1,
    )
    ann.typeList shouldContain AnnotationType.AbilityWordActive
    assertSoftly {
        ann.affectorId shouldBe 1
        ann.affectedIdsList shouldBe listOf(303)
        ann.detailString("AbilityWordName") shouldBe "Descended"
    }
    // No value/threshold/abilityGrpId details for keyword-only variants
    ann.detail("value") shouldBe null
    ann.detail("threshold") shouldBe null
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AnnotationBuilderTest`
Expected: compilation error — `abilityWordActive` doesn't exist

- [ ] **Step 3: Add detail key constants**

In `DetailKeys.kt`, add:

```kotlin
const val ABILITY_WORD_NAME = "AbilityWordName"
const val THRESHOLD = "threshold"
```

Note: `ABILITY_GRP_ID_UPPER` ("AbilityGrpId") and `VALUE` ("value") already exist.

- [ ] **Step 4: Implement builder method**

In `AnnotationBuilder.kt`, add after the `counter()` method (around line 515):

```kotlin
/**
 * Persistent annotation for ability word condition tracking.
 *
 * Wire shape from recordings:
 * - types: [AbilityWordActive]
 * - affectorId: creature instanceId (or seat=1 for Descended)
 * - affectedIds: [creature instanceId]
 * - details: AbilityWordName (always), value/threshold/AbilityGrpId (quantitative only)
 *
 * @param instanceId the permanent's instanceId (goes in affectedIds)
 * @param abilityWordName Arena ability word string ("Threshold", "Descended", "Raid", etc.)
 * @param value current count for quantitative conditions, null for keyword-only
 * @param threshold target value for quantitative conditions, null for keyword-only
 * @param abilityGrpId ability grpId from AbilityRegistry, null for keyword-only
 * @param affectorId defaults to instanceId; override to seat ID for Descended
 */
fun abilityWordActive(
    instanceId: Int,
    abilityWordName: String,
    value: Int? = null,
    threshold: Int? = null,
    abilityGrpId: Int? = null,
    affectorId: Int = instanceId,
): AnnotationInfo = AnnotationInfo.newBuilder()
    .addType(AnnotationType.AbilityWordActive)
    .setAffectorId(affectorId)
    .addAffectedIds(instanceId)
    .addDetails(typedStringDetail(DetailKeys.ABILITY_WORD_NAME, abilityWordName))
    .apply {
        if (value != null) addDetails(int32Detail(DetailKeys.VALUE, value))
        if (threshold != null) addDetails(int32Detail(DetailKeys.THRESHOLD, threshold))
        if (abilityGrpId != null) addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId))
    }
    .build()
```

- [ ] **Step 5: Run tests**

Run: `just test-one AnnotationBuilderTest`
Expected: all pass including the two new tests

- [ ] **Step 6: Commit**

```
feat(matchdoor): add AbilityWordActive annotation builder

Refs #177
```

---

### Task 2: AbilityWordScanner — detect ability words from Forge state

Pure function: takes a list of battlefield cards + player state, returns the set of AbilityWordActive annotations that should exist.

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/game/AbilityWordScanner.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/AbilityWordScannerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `AbilityWordScannerTest.kt`:

```kotlin
package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.conformance.ConformanceTestBase

class AbilityWordScannerTest :
    FunSpec({

        tags(UnitTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Threshold creature on battlefield emits AbilityWordActive with GY count") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                // 5 cards in graveyard (below threshold of 7)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }
            val human = game.humanPlayer
            val scavenger = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Dreadwing Scavenger" }
            val iid = b.getOrAllocInstanceId(ForgeCardId(scavenger.id)).value
            val cardData = b.getCardData(ForgeCardId(scavenger.id))

            val results = AbilityWordScanner.scan(
                battlefieldCards = listOf(scavenger),
                player = human,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                abilityRegistryResolver = { card, cd -> b.abilityRegistryFor(card, cd) },
                cardDataResolver = { fid -> b.getCardData(fid) },
            )

            results shouldHaveSize 1
            val r = results[0]
            r.instanceId shouldBe iid
            r.abilityWordName shouldBe "Threshold"
            r.value shouldBe 5
            r.threshold shouldBe 7
        }

        test("no ability word cards on battlefield returns empty") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val results = AbilityWordScanner.scan(
                battlefieldCards = human.getZone(ZoneType.Battlefield).cards.toList(),
                player = human,
                instanceIdResolver = { fid -> b.getOrAllocInstanceId(fid) },
                abilityRegistryResolver = { card, cd -> b.abilityRegistryFor(card, cd) },
                cardDataResolver = { fid -> b.getCardData(fid) },
            )

            results.shouldBeEmpty()
        }
    })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AbilityWordScannerTest`
Expected: compilation error — `AbilityWordScanner` doesn't exist

- [ ] **Step 3: Implement AbilityWordScanner**

Create `matchdoor/src/main/kotlin/leyline/game/AbilityWordScanner.kt`:

```kotlin
package leyline.game

import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId

/**
 * Scans battlefield permanents for ability word conditions and produces
 * [AbilityWordEntry] records for persistent annotation emission.
 *
 * Detection: checks Forge StaticAbility `Condition$` param and Trigger/SpellAbility
 * named params (`Threshold$`, `Morbid$`, etc.) from [CardTraitBase.meetsCommonRequirements].
 *
 * Value computation: delegates to [Player] predicates (same as Forge's own condition checks).
 * AbilityGrpId resolution: delegates to [AbilityRegistry.forStaticAbility]/[forTrigger].
 */
object AbilityWordScanner {

    /** One ability word annotation to emit. */
    data class AbilityWordEntry(
        val instanceId: Int,
        val abilityWordName: String,
        val value: Int? = null,
        val threshold: Int? = null,
        val abilityGrpId: Int? = null,
        /** Override affectorId (default = instanceId). Set to seat for Descended. */
        val affectorId: Int? = null,
    )

    /**
     * Condition name → (AbilityWordName, threshold, value computation).
     *
     * Each entry maps a Forge condition string to:
     * - Arena AbilityWordName string
     * - Threshold value (null for keyword-only)
     * - Value lambda (null for keyword-only)
     */
    private data class ConditionSpec(
        val abilityWordName: String,
        val threshold: Int? = null,
        val value: ((Player) -> Int)? = null,
    )

    /**
     * Conditions checked via `Condition$` param on StaticAbility.
     * Maps the Condition$ value → Arena wire shape.
     */
    private val STATIC_CONDITIONS = mapOf(
        "Threshold" to ConditionSpec("Threshold", threshold = 7, value = { p -> p.getZone(ZoneType.Graveyard).size() }),
        "Metalcraft" to ConditionSpec("Metalcraft", threshold = 3, value = { p -> p.getCardsIn(ZoneType.Battlefield).count { it.isArtifact } }),
        "Delirium" to ConditionSpec("Delirium", threshold = 4, value = { p -> forge.game.ability.AbilityUtils.countCardTypesFromList(p.getCardsIn(ZoneType.Graveyard), false) }),
        "Ferocious" to ConditionSpec("Ferocious"),
        "Hellbent" to ConditionSpec("Hellbent"),
        "Desert" to ConditionSpec("Desert"),
        "Blessing" to ConditionSpec("Blessing"),
    )

    /**
     * Named params checked via `Threshold$ True` etc. on any CardTraitBase (triggers, SAs).
     * These use the same condition names but appear as standalone params, not under `Condition$`.
     */
    private val NAMED_PARAM_CONDITIONS = setOf(
        "Threshold", "Metalcraft", "Delirium", "Hellbent",
        "Bloodthirst", "FatefulHour", "Revolt", "Desert", "Blessing", "Ferocious",
    )

    fun scan(
        battlefieldCards: List<Card>,
        player: Player,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        abilityRegistryResolver: (Card, CardData?) -> AbilityRegistry?,
        cardDataResolver: (ForgeCardId) -> CardData?,
    ): List<AbilityWordEntry> {
        val results = mutableListOf<AbilityWordEntry>()
        val seen = mutableSetOf<Pair<Int, String>>() // (forgeCardId, conditionName) dedup

        for (card in battlefieldCards) {
            if (card.controller != player) continue
            val forgeCardId = ForgeCardId(card.id)
            val iid = instanceIdResolver(forgeCardId).value
            val cardData = cardDataResolver(forgeCardId)
            val registry = abilityRegistryResolver(card, cardData)

            // Phase 1: StaticAbility with Condition$ param
            for (sa in card.staticAbilities ?: emptyList()) {
                val condition = sa.getParam("Condition") ?: continue
                val spec = STATIC_CONDITIONS[condition] ?: continue
                val key = card.id to condition
                if (!seen.add(key)) continue

                val grpId = registry?.forStaticAbility(sa.id)
                results.add(
                    AbilityWordEntry(
                        instanceId = iid,
                        abilityWordName = spec.abilityWordName,
                        value = spec.value?.invoke(player),
                        threshold = spec.threshold,
                        abilityGrpId = grpId?.takeIf { it > 0 },
                    ),
                )
            }

            // Phase 2: Triggers with named params (Threshold$ True, etc.)
            for (trigger in card.triggers ?: emptyList()) {
                for (paramName in NAMED_PARAM_CONDITIONS) {
                    if (!trigger.hasParam(paramName)) continue
                    val key = card.id to paramName
                    if (!seen.add(key)) continue
                    val spec = STATIC_CONDITIONS[paramName] ?: continue

                    val grpId = registry?.forTrigger(trigger.id)
                    results.add(
                        AbilityWordEntry(
                            instanceId = iid,
                            abilityWordName = spec.abilityWordName,
                            value = spec.value?.invoke(player),
                            threshold = spec.threshold,
                            abilityGrpId = grpId?.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }

        return results
    }
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one AbilityWordScannerTest`
Expected: pass. If `getCardData` signature doesn't match, adjust to use `ForgeCardId` or the actual bridge API.

- [ ] **Step 5: Commit**

```
feat(matchdoor): add AbilityWordScanner for condition detection

Scans battlefield permanents for Forge ability word conditions
(Condition$ on StaticAbility, named params on Triggers) and
produces AbilityWordEntry records with value, threshold, and
abilityGrpId resolved from AbilityRegistry.

Refs #177
```

---

### Task 3: PersistentAnnotationStore — AbilityWordActive upsert in computeBatch

Add a new step in `computeBatch` that handles AbilityWordActive annotations with upsert semantics: keyed on (affectedIds[0], AbilityWordName). Old annotation with same key is deleted, new one created.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` — add field to MechanicAnnotationResult
- Modify: `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt` — upsert logic
- Create: `matchdoor/src/test/kotlin/leyline/game/AbilityWordPipelineTest.kt`

- [ ] **Step 1: Write failing tests**

Create `AbilityWordPipelineTest.kt`:

```kotlin
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.conformance.detailInt
import leyline.conformance.detailString
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AbilityWordPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        fun emptyMechanicResult(abilityWordPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList()) =
            AnnotationPipeline.MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                abilityWordPersistent = abilityWordPersistent,
            )

        test("AbilityWordActive created in first batch") {
            val ann = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 1,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = emptyMechanicResult(abilityWordPersistent = listOf(ann)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
            awAnns[0].detailInt("value") shouldBe 5
            result.deletedIds.shouldBeEmpty()
        }

        test("AbilityWordActive upsert replaces on value change") {
            val old = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            ).toBuilder().setId(3).build()

            val updated = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 7,
                threshold = 7,
                abilityGrpId = 175886,
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(3 to old),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = emptyMechanicResult(abilityWordPersistent = listOf(updated)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            awAnns[0].detailInt("value") shouldBe 7
            awAnns[0].id shouldBe 10 // new ID allocated
            result.deletedIds shouldBe listOf(3) // old one deleted
        }

        test("AbilityWordActive removed when absent from new scan") {
            val old = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            ).toBuilder().setId(3).build()

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(3 to old),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = emptyMechanicResult(abilityWordPersistent = emptyList()),
                resolveInstanceId = ::testResolver,
            )

            result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }
    })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AbilityWordPipelineTest`
Expected: compilation error — `abilityWordPersistent` field doesn't exist on MechanicAnnotationResult

- [ ] **Step 3: Add abilityWordPersistent to MechanicAnnotationResult**

In `AnnotationPipeline.kt`, modify `MechanicAnnotationResult` (around line 483):

```kotlin
data class MechanicAnnotationResult(
    val transient: List<AnnotationInfo>,
    val persistent: List<AnnotationInfo>,
    /** Forge card IDs of auras/equipment that were detached this GSM. */
    val detachedForgeCardIds: List<Int> = emptyList(),
    /** Forge card IDs of permanents that left the battlefield this GSM. */
    val exileSourceLeftPlayForgeCardIds: List<Int> = emptyList(),
    /** Controller-change effects created this GSM. */
    val controllerChangedEffects: List<ControllerChangedEffect> = emptyList(),
    /** Forge card IDs of permanents whose control reverted this GSM. */
    val controllerRevertedForgeCardIds: List<Int> = emptyList(),
    /** AbilityWordActive annotations from scanner — full replacement set for this GSM. */
    val abilityWordPersistent: List<AnnotationInfo> = emptyList(),
) {
```

- [ ] **Step 4: Add upsert + cleanup logic to computeBatch**

In `PersistentAnnotationStore.kt`, add a new step after step 3 (mechanic-originated), before step 4 (detached auras). Around line 153, after the mechanic loop ends:

```kotlin
// 3b. AbilityWordActive — full-replacement upsert
// The scanner provides the complete set that SHOULD exist. Remove any active
// AbilityWordActive not in the new set; upsert any that changed or are new.
val newAbilityWords = mechanicResult.abilityWordPersistent.associateBy { ann ->
    val iid = ann.affectedIdsList.firstOrNull() ?: 0
    val name = ann.detailsList.firstOrNull { it.key == DetailKeys.ABILITY_WORD_NAME }
        ?.let { if (it.valueStringCount > 0) it.getValueString(0) else null } ?: ""
    iid to name
}
// Remove stale AbilityWordActive annotations
val staleAwIds = active.entries
    .filter { (_, ann) ->
        ann.typeList.any { it == AnnotationType.AbilityWordActive } &&
            (ann.affectedIdsList.firstOrNull() ?: 0).let { iid ->
                val name = ann.detailsList.firstOrNull { it.key == DetailKeys.ABILITY_WORD_NAME }
                    ?.let { d -> if (d.valueStringCount > 0) d.getValueString(0) else null } ?: ""
                (iid to name) !in newAbilityWords
            }
    }
    .map { it.key }
for (id in staleAwIds) {
    active.remove(id)
    deletions.add(id)
}
// Upsert new/changed AbilityWordActive annotations
for ((key, ann) in newAbilityWords) {
    val existingId = active.entries.firstOrNull { (_, existing) ->
        existing.typeList.any { it == AnnotationType.AbilityWordActive } &&
            (existing.affectedIdsList.firstOrNull() ?: 0) == key.first &&
            existing.detailsList.firstOrNull { it.key == DetailKeys.ABILITY_WORD_NAME }
                ?.let { if (it.valueStringCount > 0) it.getValueString(0) else null } == key.second
    }?.key
    if (existingId != null) {
        // Check if content changed — skip if identical
        val existing = active[existingId]!!
        if (existing.detailsList != ann.detailsList) {
            active.remove(existingId)
            deletions.add(existingId)
            val numbered = ann.toBuilder().setId(nextId++).build()
            active[numbered.id] = numbered
        }
    } else {
        val numbered = ann.toBuilder().setId(nextId++).build()
        active[numbered.id] = numbered
    }
}
```

- [ ] **Step 5: Run tests**

Run: `just test-one AbilityWordPipelineTest`
Expected: all 3 tests pass

- [ ] **Step 6: Run existing persistent annotation tests to check for regressions**

Run: `just test-one PersistentAnnotationPipelineTest`
Expected: all pass (default `abilityWordPersistent = emptyList()` means no behavior change)

- [ ] **Step 7: Commit**

```
feat(matchdoor): AbilityWordActive upsert in PersistentAnnotationStore

computeBatch step 3b: full-replacement semantics — scanner provides
the complete set each GSM, store diffs against active, upserts changed
annotations, removes stale ones.

Refs #177
```

---

### Task 4: Wire scanner into StateMapper pipeline

Connect `AbilityWordScanner.scan()` into `computeRemainingAnnotations()` so it feeds AbilityWordActive pAnns into `MechanicAnnotationResult.abilityWordPersistent`.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`

- [ ] **Step 1: Add scanner call in computeRemainingAnnotations**

In `StateMapper.kt`, in `computeRemainingAnnotations()` (around line 388), after `mechanicResult` is computed and before `computeBatch` is called:

```kotlin
// AbilityWordActive scanner — detect ability word conditions on battlefield permanents
val humanPlayer = bridge.getPlayer(SeatId(1))
val abilityWordEntries = if (humanPlayer != null) {
    val bfCards = humanPlayer.getZone(forge.game.zone.ZoneType.Battlefield).cards.toList()
    AbilityWordScanner.scan(
        battlefieldCards = bfCards,
        player = humanPlayer,
        instanceIdResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
        abilityRegistryResolver = { card, cd -> bridge.abilityRegistryFor(card, cd) },
        cardDataResolver = { fid -> bridge.getCardData(fid) },
    )
} else {
    emptyList()
}
val abilityWordPersistent = abilityWordEntries.map { entry ->
    AnnotationBuilder.abilityWordActive(
        instanceId = entry.instanceId,
        abilityWordName = entry.abilityWordName,
        value = entry.value,
        threshold = entry.threshold,
        abilityGrpId = entry.abilityGrpId,
        affectorId = entry.affectorId ?: entry.instanceId,
    )
}
```

Then modify the `mechanicResult` to include the scanner output. The cleanest way: create a copy with the abilityWordPersistent field set. Replace the `val batch = PersistentAnnotationStore.computeBatch(...)` call's `mechanicResult` param:

```kotlin
val enrichedMechanicResult = mechanicResult.copy(abilityWordPersistent = abilityWordPersistent)
val batch = PersistentAnnotationStore.computeBatch(
    currentActive = persistSnapshot,
    startPersistentId = startPersistentId,
    effectPersistent = effectPersistent,
    effectDiff = effectDiff,
    transferPersistent = transferPersistent,
    mechanicResult = enrichedMechanicResult,
    resolveInstanceId = { fid -> bridge.getOrAllocInstanceId(fid) },
    resolveForgeCardId = { iid -> bridge.getForgeCardId(iid) },
)
```

- [ ] **Step 2: Check that `getCardData` exists on GameBridge with the right signature**

Verify `bridge.getCardData(ForgeCardId)` returns `CardData?`. If the actual method signature is different (e.g., takes `Int` not `ForgeCardId`), adjust the `cardDataResolver` lambda accordingly.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :matchdoor:compileKotlin`
Expected: compiles without errors

- [ ] **Step 4: Run unit tests for regressions**

Run: `./gradlew :matchdoor:testGate`
Expected: all existing tests still pass

- [ ] **Step 5: Commit**

```
feat(matchdoor): wire AbilityWordScanner into StateMapper pipeline

Each GSM, scans seat 1's battlefield for ability word conditions,
builds AbilityWordActive annotations, feeds them into computeBatch
via enriched MechanicAnnotationResult.

Refs #177
```

---

### Task 5: Integration test — Threshold puzzle

Full integration test: Dreadwing Scavenger on battlefield with GY cards, verify AbilityWordActive persistent annotation appears in the GSM with correct shape.

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/game/AbilityWordPuzzleTest.kt`

- [ ] **Step 1: Validate card exists in database**

Run: `just card-grp "Dreadwing Scavenger"`
Expected: returns a grpId (93831 from the issue). If it errors, the card isn't in our database — use a different Threshold card from the Forge scripts (e.g., Mystic Penitent, Krosan Beast).

- [ ] **Step 2: Write the puzzle test**

Create `AbilityWordPuzzleTest.kt`:

```kotlin
package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.gsm
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * AbilityWordActive persistent annotation conformance.
 *
 * Verifies that Threshold creatures on the battlefield produce
 * AbilityWordActive pAnns with correct AbilityWordName, value,
 * and threshold fields.
 */
class AbilityWordPuzzleTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Threshold creature emits AbilityWordActive with GY card count") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }

            val human = game.humanPlayer
            val scavenger = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Dreadwing Scavenger" }
            val iid = b.getOrAllocInstanceId(ForgeCardId(scavenger.id)).value

            val gsm = base.stateOnlyDiff(game, b, counter)

            val awAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            assertSoftly {
                awAnns[0].affectorId shouldBe iid
                awAnns[0].affectedIdsList shouldBe listOf(iid)
                awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
                awAnns[0].detailInt("value") shouldBe 5
                awAnns[0].detailInt("threshold") shouldBe 7
            }
        }

        test("AbilityWordActive value updates when GY count changes") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
                base.addCard("Island", human, ZoneType.Hand) // will discard to GY
            }

            // Initial snapshot — value=5
            val gsm1 = base.stateOnlyDiff(game, b, counter)
            val aw1 = gsm1.persistentAnnotationsList.first {
                AnnotationType.AbilityWordActive in it.typeList
            }
            aw1.detailInt("value") shouldBe 5

            // Move card from hand to GY (simulate discard)
            val human = game.humanPlayer
            val island = human.getZone(ZoneType.Hand).cards.first { it.name == "Island" }
            game.action.moveToGraveyard(island, null, null)

            // Capture next diff — value should be 6
            val gsm2 = base.captureAfterAction(b, game, counter) {}
            val aw2 = gsm2.persistentAnnotationsList.first {
                AnnotationType.AbilityWordActive in it.typeList
            }
            aw2.detailInt("value") shouldBe 6
        }

        test("no AbilityWordActive for non-threshold creatures") {
            val (_, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }

            val gsm = base.stateOnlyDiff(game, counter = counter, b = base.bridge!!)
            gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList
            } shouldHaveSize 0
        }
    })
```

Note: The third test uses a different `stateOnlyDiff` invocation pattern. Check `ConformanceTestBase` — if `stateOnlyDiff` takes `(game, b, counter)`, adjust the parameter order accordingly.

- [ ] **Step 3: Run the test**

Run: `just test-one AbilityWordPuzzleTest`
Expected: all 3 tests pass

- [ ] **Step 4: Commit**

```
test(matchdoor): AbilityWordActive conformance tests

Verifies Threshold creature emits AbilityWordActive pAnn with
correct value/threshold/AbilityWordName, value updates on GY change,
and non-threshold creatures produce no annotation.

Refs #177
```

---

### Task 6: Format, gate, and catalog update

- [ ] **Step 1: Run formatter**

Run: `just fmt`

- [ ] **Step 2: Run test gate**

Run: `./gradlew :matchdoor:testGate`
Expected: all pass

- [ ] **Step 3: Run integration tests (touches StateMapper + annotations)**

Run: `./gradlew :matchdoor:testIntegration`
Expected: all pass

- [ ] **Step 4: Update catalog.yaml**

In `docs/catalog.yaml`, find the `threshold` entry (around line 586) and update its status from `missing` to `partial`:

```yaml
# Update the AbilityWordActive wire gap note to reflect implementation
```

Find the line mentioning "AbilityWordActive (proto type 39) persistent annotation never emitted" and update to note it's now emitted for Threshold (and other `Condition$`-based ability words).

- [ ] **Step 5: Update rosetta.md**

In `docs/rosetta.md`, find the AbilityWordActive row (line 42) and change status from `MISSING` to `PARTIAL`:

```
| 39 | AbilityWordActive | -- | AbilityWordScanner | -- | `AbilityWordName`, `value`, `threshold`, `AbilityGrpId` | PARTIAL — Condition$-based (Threshold, Metalcraft, Delirium) |
```

- [ ] **Step 6: Commit**

```
docs: update catalog + rosetta for AbilityWordActive

Refs #177
```

---

## Out of Scope (future work)

These are NOT part of this plan:

1. **Turn-scoped ability words** (Raid, Morbid, Descended) — need game event tracking for "attacked this turn" / "creature died this turn" / "permanent went to GY this turn". Different detection mechanism (event-driven, not snapshot-based). Separate issue.
2. **AbilityWordActive on non-battlefield zones** — Kiora card spec shows copies in GY also carry the pAnn. Low priority.
3. **AbilityWordActive at cast time** — Case card spec shows pAnn fires on stack, not just battlefield. Separate pattern.
4. **LayeredEffectCreated at ETB** — already handled by EffectTracker.
5. **P/T changes when threshold crossed** — already handled by Forge + ObjectMapper.
