# DFC Activated Transform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement in-place grpId mutation for activated-ability DFC transforms (Concealing Curtains → Revealing Eye) with keyword Qualification annotations.

**Architecture:** Explicit `CardTransformed` event from `GameEventCollector` signals transform; snapshot-compare diff naturally captures grpId/P/T/subtype changes; new `othersideGrpId` field links front↔back faces; Qualification persistent annotation emits keyword badges.

**Tech Stack:** Kotlin, Kotest FunSpec, protobuf (messages.proto), Forge engine API

---

### Task 1: CardTransformed event — failing test

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt`

- [ ] **Step 1: Add `CardTransformed` variant to `GameEvent`**

In `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`, add after `PowerToughnessChanged` (line ~276):

```kotlin
    /** A double-faced card transformed (front ↔ back).
     *  Emitted from GameEventCardStatsChanged when isBackSide() flips. */
    data class CardTransformed(
        val cardId: ForgeCardId,
        val isBackSide: Boolean,
    ) : GameEvent
```

- [ ] **Step 2: Write failing test for CardTransformed**

In `GameEventCollectorTest.kt`, add a test that fires `GameEventCardStatsChanged` with a DFC card that has `isBackSide() == true` (after being false) and asserts `CardTransformed` is emitted.

The test pattern follows existing `GameEventCollectorTest` style — use `startWithBoard{}`, add a DFC card to the battlefield, manually toggle its state via `card.setState(CardStateName.Backside, true)`, then fire `GameEventCardStatsChanged(card)`.

```kotlin
        test("transform emits CardTransformed") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents() // clear setup events

            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Concealing Curtains" }
            // Simulate transform — toggle to back side
            card.setState(CardStateName.Backside, true)
            game.fireEvent(GameEventCardStatsChanged(card))

            val events = collector.drainEvents().events
            val transformed = events.filterIsInstance<GameEvent.CardTransformed>()
            transformed.size shouldBe 1
            transformed[0].cardId shouldBe ForgeCardId(card.id)
            transformed[0].isBackSide shouldBe true
        }

        test("non-transform stats change does not emit CardTransformed") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val collector = b.eventCollector!!
            collector.drainEvents()

            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            game.fireEvent(GameEventCardStatsChanged(card))

            val events = collector.drainEvents().events
            events.filterIsInstance<GameEvent.CardTransformed>().shouldBeEmpty()
        }
```

Add import for `CardStateName`:
```kotlin
import forge.game.card.CardStateName
```

- [ ] **Step 3: Run test to verify it fails**

Run: `just test-one GameEventCollectorTest`
Expected: FAIL — `CardTransformed` not emitted (visitor doesn't detect transform yet)

- [ ] **Step 4: Commit failing test**

```bash
git add matchdoor/src/main/kotlin/leyline/game/GameEvent.kt matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt
git commit -m "test: failing test for CardTransformed event on DFC transform"
```

### Task 2: CardTransformed event — make it pass

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`

- [ ] **Step 1: Add lastBackSide tracking and emit CardTransformed**

In `GameEventCollector.kt`, add alongside `lastPT` (line ~85):

```kotlin
    /** Last-seen backside state per card ID — used to detect transform on GameEventCardStatsChanged. */
    private val lastBackSide = ConcurrentHashMap<ForgeCardId, Boolean>()
```

In the `visit(GameEventCardStatsChanged)` method (line ~314), add transform detection **before** the P/T check, inside the `for (card in ev.cards())` loop:

```kotlin
            // Detect transform: backside state changed
            val newBackSide = card.isBackSide
            val prevBackSide = lastBackSide.put(id, newBackSide)
            if (prevBackSide != null && prevBackSide != newBackSide) {
                queue.add(
                    GameEvent.CardTransformed(
                        cardId = id,
                        isBackSide = newBackSide,
                    ),
                )
                log.debug("event: CardTransformed card={} backSide={}", card.name, newBackSide)
            }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `just test-one GameEventCollectorTest`
Expected: PASS — both `CardTransformed` tests green

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt
git commit -m "feat: emit CardTransformed event on DFC backside flip"
```

### Task 3: othersideGrpId on DFC gameObjects — failing test

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`
- Modify or create: `matchdoor/src/test/kotlin/leyline/game/mapper/ObjectMapperTest.kt`

- [ ] **Step 1: Write failing test for othersideGrpId**

Check if `ObjectMapperTest` exists. If not, create it following Kotest FunSpec pattern. The test should verify that a DFC card's gameObject has `othersideGrpId` set to the back-face grpId.

Use `startWithBoard{}` to place Concealing Curtains, then call `ObjectMapper.buildSharedCardObject()` and assert `othersideGrpId` is set.

```kotlin
class ObjectMapperTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("DFC card has othersideGrpId set") {
        val (b, game, _) = base.startWithBoard { _, human, _ ->
            base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
        }
        val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Concealing Curtains" }
        val instanceId = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
        val zoneId = ZoneIds.P1_BATTLEFIELD

        val obj = ObjectMapper.buildSharedCardObject(card, instanceId, zoneId, 1, 1, b, game)

        val frontGrpId = b.cards.findGrpIdByName("Concealing Curtains")!!
        val backGrpId = b.cards.findGrpIdByName("Revealing Eye")!!
        obj.grpId shouldBe frontGrpId
        obj.othersideGrpId shouldBe backGrpId
    }

    test("non-DFC card has othersideGrpId zero") {
        val (b, game, _) = base.startWithBoard { _, human, _ ->
            base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
        val instanceId = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
        val zoneId = ZoneIds.P1_BATTLEFIELD

        val obj = ObjectMapper.buildSharedCardObject(card, instanceId, zoneId, 1, 1, b, game)

        obj.othersideGrpId shouldBe 0
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one ObjectMapperTest`
Expected: FAIL — `othersideGrpId` is 0 for DFC card

- [ ] **Step 3: Commit failing test**

```bash
git add matchdoor/src/test/kotlin/leyline/game/mapper/ObjectMapperTest.kt
git commit -m "test: failing test for othersideGrpId on DFC gameObjects"
```

### Task 4: othersideGrpId — make it pass

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`

- [ ] **Step 1: Add othersideGrpId resolution**

In `ObjectMapper.kt`, add a new method alongside `resolveGrpId()`:

```kotlin
    /** Resolve the other face's grpId for DFC cards. Returns 0 for non-DFC. */
    internal fun resolveOthersideGrpId(card: Card, cards: CardRepository): Int {
        if (!card.isDoubleFaced) return 0
        val altStateName = card.alternateStateName ?: return 0
        val altState = card.getState(altStateName) ?: return 0
        return cards.findGrpIdByName(altState.name) ?: 0
    }
```

Add import for `forge.game.card.Card` if not present (it already is).

Then modify `buildCardObject()` and `buildSharedCardObject()` to set `othersideGrpId` on the builder. In both methods, after the `resolveGrpId()` call and before `.build()`, add:

```kotlin
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
```

Specifically in `buildCardObject()` (line ~47-56), insert before `.build()`:
```kotlin
        return bridge.cardProto.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
            .applyCardFields(card)
            .build()
```

Same for `buildSharedCardObject()` (line ~72-81):
```kotlin
        return bridge.cardProto.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(controllerSeatId)
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
            .applyCardFields(card, bridge, game)
            .build()
```

- [ ] **Step 2: Run test to verify it passes**

Run: `just test-one ObjectMapperTest`
Expected: PASS

- [ ] **Step 3: Run broader tests to check nothing broke**

Run: `./gradlew :matchdoor:testGate`
Expected: All pass — `othersideGrpId` defaults to 0 for non-DFC, so existing gameObjects unchanged

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt
git commit -m "feat: set othersideGrpId on DFC gameObjects for front↔back face linkage"
```

### Task 5: Qualification annotation builder — failing test

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt`

- [ ] **Step 1: Write failing test for Qualification annotation shape**

In `AnnotationBuilderTest.kt`, add:

```kotlin
        test("qualification annotation shape") {
            val ann = AnnotationBuilder.qualification(
                affectorId = 287,
                instanceId = 287,
                grpId = 142,
                qualificationType = 40,
                qualificationSubtype = 0,
                sourceParent = 287,
            )
            ann.typeList shouldContain AnnotationType.Qualification
            ann.affectorId shouldBe 287
            ann.affectedIdsList shouldContain 287
            assertSoftly {
                ann.detailUint("grpid") shouldBe 142
                ann.detailUint("QualificationType") shouldBe 40
                ann.detailUint("QualificationSubtype") shouldBe 0
                ann.detailUint("SourceParent") shouldBe 287
            }
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AnnotationBuilderTest`
Expected: FAIL — `qualification()` method doesn't exist

- [ ] **Step 3: Commit failing test**

```bash
git add matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt
git commit -m "test: failing test for Qualification annotation builder"
```

### Task 6: Qualification annotation builder — make it pass

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`

- [ ] **Step 1: Add `qualification()` builder method**

In `AnnotationBuilder.kt`, add after the `instanceRevealedToOpponent` method (~line 810):

```kotlin
    /** Keyword qualification badge on a permanent. Persistent. Arena type 42.
     *  [grpId] = keyword grpId (e.g. 142 for Menace).
     *  [qualificationType] = Arena qualification subtype (e.g. 40 for combat keyword).
     *  [sourceParent] = instanceId of the permanent granting the keyword (usually self). */
    fun qualification(
        affectorId: Int,
        instanceId: Int,
        grpId: Int,
        qualificationType: Int,
        qualificationSubtype: Int = 0,
        sourceParent: Int,
    ): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .addType(AnnotationType.Qualification)
            .setAffectorId(affectorId)
            .addAffectedIds(instanceId)
            .addDetails(uint32Detail("grpid", grpId))
            .addDetails(uint32Detail("QualificationType", qualificationType))
            .addDetails(uint32Detail("QualificationSubtype", qualificationSubtype))
            .addDetails(uint32Detail("SourceParent", sourceParent))
            .build()
```

- [ ] **Step 2: Run test to verify it passes**

Run: `just test-one AnnotationBuilderTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt
git commit -m "feat: Qualification persistent annotation builder (Arena type 42)"
```

### Task 7: Qualification emission in mechanic pipeline — failing test

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Create: `matchdoor/src/test/kotlin/leyline/conformance/DfcTransformTest.kt`

- [ ] **Step 1: Write failing conformance test for Qualification pAnn on transform**

This test uses `startWithBoard{}` to place Concealing Curtains, simulates transform, captures the GSM, and asserts a Qualification persistent annotation for Menace.

```kotlin
package leyline.conformance

import forge.game.card.CardStateName
import forge.game.event.GameEventCardStatsChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import forge.game.zone.ZoneType

class DfcTransformTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("transform emits Qualification pAnn for Menace on back face") {
        val (b, game, counter) = base.startWithBoard { _, human, _ ->
            base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
        }
        val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards
            .first { it.name == "Concealing Curtains" }

        // Simulate transform to back face
        card.setState(CardStateName.Backside, true)
        game.fireEvent(GameEventCardStatsChanged(card))

        val gsm = base.captureAfterAction(b, game, counter) { /* state already changed */ }

        val qualAnns = gsm.persistentAnnotationsList.filter {
            AnnotationType.Qualification in it.typeList
        }
        qualAnns.shouldNotBeEmpty()
        val menaceAnn = qualAnns.first()
        menaceAnn.detailUint("grpid") shouldBe 142  // Menace keyword grpId
        menaceAnn.detailUint("QualificationType") shouldBe 40
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one DfcTransformTest`
Expected: FAIL — no Qualification pAnn emitted

- [ ] **Step 3: Commit failing test**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/DfcTransformTest.kt
git commit -m "test: failing test for Qualification pAnn on DFC transform"
```

### Task 8: Qualification emission in mechanic pipeline — make it pass

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`

- [ ] **Step 1: Handle `CardTransformed` in `mechanicAnnotations()`**

In `AnnotationPipeline.mechanicAnnotations()`, add a `when` branch for `CardTransformed` (after the `CardsRevealed` handler, ~line 617):

```kotlin
                is GameEvent.CardTransformed -> {
                    if (ev.isBackSide) {
                        val instanceId = idResolver(ev.cardId).value
                        // Menace keyword Qualification — hardcoded for Phase 1.
                        // TODO: Generalize keyword→Qualification mapping when more DFCs are exercised.
                        persistent.add(
                            AnnotationBuilder.qualification(
                                affectorId = instanceId,
                                instanceId = instanceId,
                                grpId = 142, // Menace
                                qualificationType = 40,
                                qualificationSubtype = 0,
                                sourceParent = instanceId,
                            ),
                        )
                        log.debug("mechanic: Qualification (Menace) on transform iid={}", instanceId)
                    }
                }
```

Note: This hardcodes Menace for Phase 1. A keyword lookup table is the obvious next step when other DFCs are added — but YAGNI for now.

- [ ] **Step 2: Run test to verify it passes**

Run: `just test-one DfcTransformTest`
Expected: PASS

- [ ] **Step 3: Run testGate to verify no regressions**

Run: `./gradlew :matchdoor:testGate`
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt
git commit -m "feat: emit Qualification pAnn for Menace on DFC transform"
```

### Task 9: Puzzle — Concealing Curtains transform

**Files:**
- Create: `puzzles/concealing-curtains-transform.pzl`
- Create: `matchdoor/src/test/kotlin/leyline/conformance/ConcealingCurtainsPuzzleTest.kt`

- [ ] **Step 1: Write the puzzle file**

```
[metadata]
Name:Concealing Curtains Transform
Goal:Win
Turns:4
Difficulty:Easy
Description:Transform Concealing Curtains into Revealing Eye and attack for lethal.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=3

humanbattlefield=Concealing Curtains;Swamp|Swamp|Swamp
humanlibrary=Swamp
aibattlefield=
aihand=Grizzly Bears
ailibrary=Forest
```

AI at 3 life. Transform Concealing Curtains (costs {2}{B} = 3 Swamps), get 3/4 Menace Revealing Eye. Next turn attack for 3 = lethal.

- [ ] **Step 2: Validate puzzle card references**

Run: `just puzzle-check puzzles/concealing-curtains-transform.pzl`
Expected: All cards found (Concealing Curtains grpId=78895 resolves)

- [ ] **Step 3: Write puzzle test**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag

class ConcealingCurtainsPuzzleTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("concealing curtains transform puzzle loads and starts") {
        val pzl = java.io.File("puzzles/concealing-curtains-transform.pzl").readText()
        val (b, game, _) = base.startPuzzleAtMain1(pzl)
        // Verify board: Concealing Curtains on battlefield, 3 Swamps
        val bf = game.humanPlayer.getZone(forge.game.zone.ZoneType.Battlefield).cards
        bf.any { it.name == "Concealing Curtains" } shouldBe true
        bf.count { it.isLand } shouldBe 3
    }
})
```

- [ ] **Step 4: Run test**

Run: `just test-one ConcealingCurtainsPuzzleTest`
Expected: PASS — puzzle loads, board state matches

- [ ] **Step 5: Commit**

```bash
git add puzzles/concealing-curtains-transform.pzl matchdoor/src/test/kotlin/leyline/conformance/ConcealingCurtainsPuzzleTest.kt
git commit -m "feat: Concealing Curtains transform puzzle + load test"
```

### Task 10: Catalog update

**Files:**
- Modify: `docs/catalog.yaml`

- [ ] **Step 1: Add dfc-activated-transform entry**

In `docs/catalog.yaml`, add under the gameplay mechanics section (find a logical spot near existing transform/DFC entries):

```yaml
  dfc-activated-transform:
    status: wired
    notes: >
      Activated-ability DFC transform (Concealing Curtains → Revealing Eye, Delver of Secrets, etc.).
      In-place grpId mutation on the existing gameObject — same instanceId, no ZoneTransfer.
      Snapshot-compare diff catches all field changes (grpId, P/T, subtypes, abilities).
      othersideGrpId links front↔back face grpIds on all DFC gameObjects.
      Qualification pAnn emits keyword badges on back face (Menace hardcoded, others TBD).
      Distinct from saga DFC which uses ZoneTransfer pair (exile front → resolve back).
      On-transform trigger (reveal/choose/discard) autopassed — engine resolves via AI.
      Phase 2 (#256): interactive reveal UI wire.
```

- [ ] **Step 2: Commit**

```bash
git add docs/catalog.yaml
git commit -m "docs: add dfc-activated-transform to mechanic catalog"
```

### Task 11: Format + final gate

- [ ] **Step 1: Run formatter**

Run: `just fmt`

- [ ] **Step 2: Run full test gate**

Run: `just test-gate`
Expected: All pass

- [ ] **Step 3: Commit formatting if changed**

```bash
git add -A && git diff --cached --quiet || git commit -m "style: fmt"
```
