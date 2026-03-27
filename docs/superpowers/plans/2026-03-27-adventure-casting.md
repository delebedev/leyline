# Adventure Casting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make adventure cards playable — CastAdventure action from hand, adventure resolution to exile, cast creature from exile, Qualification pAnn lifecycle.

**Architecture:** Two new action paths: (1) CastAdventure in ActionMapper emits a second action for adventure-capable hand cards with adventure grpId + mana cost, MatchSession routes it to Forge's adventure SpellAbility. (2) Cast-from-exile already works via `addZoneCastActions` + Forge's `MayPlay` static — just needs Exile→Stack category fix in inferCategory. Qualification pAnn marks exiled adventure cards as castable; deleted when creature is cast.

**Tech Stack:** Kotlin, Kotest FunSpec, protobuf, Forge engine API

**Evidence:** `docs/conformance/adventure-2026-03-25.md`, `docs/card-specs/ratcatcher-trainee.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt` | Modify | Emit CastAdventure action for adventure hand cards |
| `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` | Modify | Handle CastAdventure → route to adventure SpellAbility |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` | Modify | Exile→Stack = CastSpell in inferCategory |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Modify | Add `qualification()` builder |
| `matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt` | Modify | Add Qualification detail keys |
| `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` | Modify | Add AdventureExiled event variant |
| `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` | Modify | Detect adventure exile, emit AdventureExiled |
| `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt` | Modify | Qualification upsert/delete in computeBatch |
| `matchdoor/src/test/kotlin/leyline/game/AdventureCastActionTest.kt` | Create | ActionMapper CastAdventure tests |
| `matchdoor/src/test/kotlin/leyline/game/AdventureInferCategoryTest.kt` | Create | Exile→Stack category test |
| `matchdoor/src/test/kotlin/leyline/game/AdventurePuzzleTest.kt` | Create | Integration: full adventure lifecycle |

---

### Task 1: Exile→Stack inferCategory fix

Smallest blocking change. Exile→Stack should produce CastSpell, not ZoneTransfer.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:718-721`
- Create: `matchdoor/src/test/kotlin/leyline/game/AdventureInferCategoryTest.kt`

- [ ] **Step 1: Write failing test**

Create `AdventureInferCategoryTest.kt`:

```kotlin
package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapper.ZoneIds

class AdventureInferCategoryTest :
    FunSpec({

        tags(UnitTag)

        test("Exile to Stack infers CastSpell") {
            val category = AnnotationPipeline.inferCategory(
                srcZone = ZoneIds.EXILE,
                destZone = ZoneIds.STACK,
            )
            category shouldBe TransferCategory.CastSpell
        }

        test("Exile to Hand still infers Return") {
            val category = AnnotationPipeline.inferCategory(
                srcZone = ZoneIds.EXILE,
                destZone = ZoneIds.P1_HAND,
            )
            category shouldBe TransferCategory.Return
        }

        test("Exile to Battlefield still infers Return") {
            val category = AnnotationPipeline.inferCategory(
                srcZone = ZoneIds.EXILE,
                destZone = ZoneIds.BATTLEFIELD,
            )
            category shouldBe TransferCategory.Return
        }
    })
```

Note: Check if `inferCategory` is a public function or if it's accessed via a different path. The existing `InferCategoryTest.kt` may show the pattern. If `inferCategory` is private, test through `detectZoneTransfers` or make it internal.

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AdventureInferCategoryTest`
Expected: First test fails — Exile→Stack returns `ZoneTransfer` not `CastSpell`

- [ ] **Step 3: Fix inferCategory**

In `AnnotationPipeline.kt`, around line 718, change:

```kotlin
srcZone == ZONE_EXILE -> when (destZone) {
    ZONE_P1_HAND, ZONE_P2_HAND, ZONE_BATTLEFIELD -> TransferCategory.Return
    else -> TransferCategory.ZoneTransfer
}
```

To:

```kotlin
srcZone == ZONE_EXILE -> when (destZone) {
    ZONE_P1_HAND, ZONE_P2_HAND, ZONE_BATTLEFIELD -> TransferCategory.Return
    ZONE_STACK -> TransferCategory.CastSpell
    else -> TransferCategory.ZoneTransfer
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one AdventureInferCategoryTest`
Expected: all 3 pass

- [ ] **Step 5: Commit**

```
fix(matchdoor): Exile→Stack infers CastSpell category

Enables correct ZoneTransfer category for cast-from-exile
(adventure creature, flashback, escape, etc.)

Refs #173
```

---

### Task 2: CastAdventure action in ActionMapper

Emit a second action for adventure-capable hand cards with `ActionType.CastAdventure`, adventure grpId, and adventure mana cost.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ActionMapper.kt`
- Create: `matchdoor/src/test/kotlin/leyline/game/AdventureCastActionTest.kt`

- [ ] **Step 1: Write failing test**

Create `AdventureCastActionTest.kt`:

```kotlin
package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class AdventureCastActionTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("adventure card in hand produces both Cast and CastAdventure actions") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                // Enough mana for both sides
                repeat(3) { base.addCard("Mountain", human, ZoneType.Battlefield) }
            }

            val actions = leyline.game.mapper.ActionMapper.buildActionList(
                seatId = 1,
                bridge = b,
                checkLegality = true,
            )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }

            // Should have one Cast (creature side, grpId 86845) and one CastAdventure (adventure side, grpId 86846)
            castActions shouldHaveSize 1
            castActions[0].grpId shouldBe 86845

            adventureActions shouldHaveSize 1
            adventureActions[0].grpId shouldBe 86846
            adventureActions[0].manaCostCount shouldBe > 0 // has mana cost
        }

        test("non-adventure card does not produce CastAdventure") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
                repeat(2) { base.addCard("Forest", human, ZoneType.Battlefield) }
            }

            val actions = leyline.game.mapper.ActionMapper.buildActionList(
                seatId = 1,
                bridge = b,
                checkLegality = true,
            )

            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }
            adventureActions shouldHaveSize 0
        }
    })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AdventureCastActionTest`
Expected: first test fails — no CastAdventure actions emitted

- [ ] **Step 3: Implement CastAdventure in ActionMapper**

In `ActionMapper.kt`, after the non-land spells loop (around line 191, after `builder.addActions(actionBuilder)`), add adventure action emission:

```kotlin
            // Adventure: emit CastAdventure for adventure-capable hand cards
            if (card.isAdventureCard) {
                val adventureState = card.getState(forge.game.card.CardStateName.Secondary)
                val adventureSa = adventureState?.spellAbilities
                    ?.firstOrNull { it.isAdventure }
                if (adventureSa != null) {
                    adventureSa.setActivatingPlayer(player)
                    val canCastAdventure = if (checkLegality) {
                        try {
                            adventureSa.canPlay() && ComputerUtilMana.canPayManaCost(adventureSa, player, 0, false)
                        } catch (_: Exception) { false }
                    } else { true }

                    if (canCastAdventure) {
                        val adventureName = adventureState.name
                        val adventureGrpId = cards?.findGrpIdByName(adventureName) ?: grpId
                        val advBuilder = Action.newBuilder()
                            .setActionType(ActionType.CastAdventure)
                            .setInstanceId(instanceId)
                            .setGrpId(adventureGrpId)
                            .setFacetId(instanceId)
                            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastAdventure))

                        // Adventure mana cost
                        val advManaCost = adventureSa.payCosts?.totalMana
                        if (advManaCost != null && !advManaCost.isNoCost) {
                            addManaCostFromForge(advManaCost, advBuilder)
                        }
                        builder.addActions(advBuilder)
                    }
                }
            }
```

Note: `card.isAdventureCard` is a Java getter — in Kotlin it's `card.isAdventureCard()` or `card.isAdventureCard` depending on the accessor pattern. Check the Forge API. You need access to `cards` (the `CardRepository`) for the adventure grpId lookup — check how the enclosing loop gets its `cardDataLookup` and adapt. The `adventureState.name` returns the adventure face name (e.g., "Pest Problem").

Also add `import forge.ai.ComputerUtilMana` if not already present.

- [ ] **Step 4: Run tests**

Run: `just test-one AdventureCastActionTest`
Expected: both pass

- [ ] **Step 5: Commit**

```
feat(matchdoor): emit CastAdventure action for adventure hand cards

ActionMapper now checks isAdventureCard() on hand spells and emits
a second action with ActionType.CastAdventure, adventure-face grpId,
and adventure mana cost.

Refs #173
```

---

### Task 3: CastAdventure handler in MatchSession

Route CastAdventure to Forge's adventure SpellAbility.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt:235-293`

- [ ] **Step 1: Add CastAdventure arm to onPerformAction**

In `MatchSession.kt`, in the `when (action.actionType)` block (around line 235), add before the `else` arm:

```kotlin
ActionType.CastAdventure -> {
    val forgeCardId = bridge.getForgeCardId(InstanceId(action.instanceId))
    val submitted = if (forgeCardId != null) {
        // Find the adventure SpellAbility index
        val card = bridge.findCard(forgeCardId)
        val adventureIndex = card?.getSpells()
            ?.indexOfFirst { it.isAdventure }
            ?.takeIf { it >= 0 }
            ?: 0
        seatBridge.action.submitAction(
            pending.actionId,
            PlayerAction.CastSpell(forgeCardId, adventureIndex),
        )
    } else {
        seatBridge.action.submitAction(pending.actionId, PlayerAction.PassPriority)
    }
    Tap.actionResult(action.actionType, action.instanceId, forgeCardId?.value, submitted)
}
```

Note: Check if `PlayerAction.CastSpell` accepts an ability index parameter. If it only takes `forgeCardId`, check how the bridge selects which SpellAbility to cast — it may need a separate `PlayerAction.CastAdventure` variant, or the ability index may be passed differently. Look at how `Activate_add3` passes `abilityIndex` via `PlayerAction.ActivateAbility`.

Also check: `bridge.findCard(forgeCardId)` — does this method exist? It may be `bridge.getPlayer(SeatId(1))?.game?.findCardById(forgeCardId.value)` or similar. Check `GameBridge` and the existing `findCard` extension.

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :matchdoor:compileKotlin`

- [ ] **Step 3: Run existing MatchSession tests for regressions**

Run: `./gradlew :matchdoor:testGate`
Expected: all pass — the new arm is unreachable by existing tests

- [ ] **Step 4: Commit**

```
feat(matchdoor): handle CastAdventure in MatchSession

Routes CastAdventure action to Forge's adventure SpellAbility
by finding the SA where isAdventure() is true and passing its
index to CastSpell.

Refs #173
```

---

### Task 4: Qualification persistent annotation

Builder + lifecycle for the Qualification pAnn that marks exiled adventure cards as castable.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/DetailKeys.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/PersistentAnnotationStore.kt`
- Test: `matchdoor/src/test/kotlin/leyline/game/AnnotationBuilderTest.kt`

- [ ] **Step 1: Add detail key constants**

In `DetailKeys.kt`:

```kotlin
const val QUALIFICATION_TYPE = "QualificationType"
const val QUALIFICATION_SUBTYPE = "QualificationSubtype"
const val SOURCE_PARENT = "SourceParent"
```

- [ ] **Step 2: Add builder method**

In `AnnotationBuilder.kt`:

```kotlin
/**
 * Persistent annotation marking a card as eligible for an alternate cast
 * (adventure from exile, revealed for cast, etc.).
 *
 * Wire shape from recordings (adventure):
 * - types: [Qualification]
 * - affectedIds: [exiled card instanceId]
 * - affectorId: absent
 * - details: QualificationType=47, QualificationSubtype=0, grpid=196, SourceParent=0
 */
fun qualification(
    instanceId: Int,
    qualificationType: Int,
    qualificationSubtype: Int = 0,
    grpId: Int,
    sourceParent: Int = 0,
): AnnotationInfo = AnnotationInfo.newBuilder()
    .addType(AnnotationType.Qualification)
    .addAffectedIds(instanceId)
    .addDetails(int32Detail(DetailKeys.QUALIFICATION_TYPE, qualificationType))
    .addDetails(int32Detail(DetailKeys.QUALIFICATION_SUBTYPE, qualificationSubtype))
    .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
    .addDetails(int32Detail(DetailKeys.SOURCE_PARENT, sourceParent))
    .build()
```

- [ ] **Step 3: Write builder test**

In `AnnotationBuilderTest.kt`:

```kotlin
test("qualificationAdventure") {
    val ann = AnnotationBuilder.qualification(
        instanceId = 348,
        qualificationType = 47,
        grpId = 196,
    )
    ann.typeList shouldContain AnnotationType.Qualification
    assertSoftly {
        ann.affectedIdsList shouldBe listOf(348)
        ann.detailInt("QualificationType") shouldBe 47
        ann.detailInt("QualificationSubtype") shouldBe 0
        ann.detailUint("grpid") shouldBe 196
        ann.detailInt("SourceParent") shouldBe 0
    }
    ann.hasAffectorId() shouldBe false
}
```

- [ ] **Step 4: Add AdventureExiled game event**

In `GameEvent.kt`, add:

```kotlin
/** Card exiled via adventure resolution — triggers Qualification pAnn. */
data class AdventureExiled(
    val forgeCardId: Int,
    val seatId: Int,
) : GameEvent
```

- [ ] **Step 5: Detect adventure exile in GameEventCollector**

In `GameEventCollector.kt`, in the `GameEventCardChangeZone` handler (where exile is detected), add adventure detection:

```kotlin
// After existing CardExiled emission for exile destinations:
if (card.isOnAdventure()) {
    events.add(GameEvent.AdventureExiled(forgeCardId = card.id, seatId = seatId))
}
```

The exact insertion point depends on how the collector currently handles `GameEventCardChangeZone` with destination=Exile. Read the collector to find the right spot.

- [ ] **Step 6: Wire into MechanicAnnotationResult**

In `AnnotationPipeline.mechanicAnnotations`, add a `when` branch for `AdventureExiled`:

```kotlin
is GameEvent.AdventureExiled -> {
    val instanceId = idResolver(ForgeCardId(ev.forgeCardId)).value
    persistent.add(AnnotationBuilder.qualification(
        instanceId = instanceId,
        qualificationType = 47,
        grpId = 196,
    ))
}
```

- [ ] **Step 7: Add Qualification cleanup to computeBatch**

In `PersistentAnnotationStore.computeBatch`, the Qualification pAnn should be deleted when the card is cast from exile (it leaves the exile zone). This already happens naturally if the zone transfer detection removes the instanceId from exile — but verify. If not, add cleanup similar to AbilityWordActive: when a card with a Qualification pAnn is no longer in exile, delete the pAnn.

Alternatively: handle it in the existing exile-source cleanup or via the zone transfer pipeline's `diffDeletedPersistentAnnotationIds`.

- [ ] **Step 8: Run tests**

Run: `just test-one AnnotationBuilderTest` and `./gradlew :matchdoor:testGate`

- [ ] **Step 9: Commit**

```
feat(matchdoor): Qualification persistent annotation for adventure exile

Builder, GameEvent.AdventureExiled, collector detection, pipeline
wiring. Qualification pAnn (type=47, grpId=196) marks exiled
adventure cards as eligible for creature-side cast.

Refs #173
```

---

### Task 5: Adventure proxy objects

Phantom companion objects (type=Adventure) that accompany the card in every zone. Not in zone objectIds but present as GameObjectInfo entries.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ObjectMapper.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/mapper/ZoneMapper.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`

- [ ] **Step 1: Understand the proxy pattern**

From conformance report: every adventure card gets a companion object with:
- `type = Adventure_a4aa (10)` (not Card)
- `grpId = adventure face grpId` (e.g., 86846)
- `zoneId = same as the real card`
- NOT in zone `objectIds` — phantom
- Same owner/controller, cardTypes, subtypes as the adventure face
- Deleted (via `diffDeletedInstanceIds`) when the real card leaves the zone or dies

The proxy needs a separate instanceId (allocated but not zone-tracked).

- [ ] **Step 2: Add proxy builder to ObjectMapper**

In `ObjectMapper.kt`, add:

```kotlin
/**
 * Build an Adventure proxy companion object for an adventure card.
 * The proxy is a phantom — same zone, not in zone objectIds.
 */
fun makeAdventureProxy(
    proxyInstanceId: Int,
    adventureGrpId: Int,
    zoneId: Int,
    ownerSeatId: Int,
    controllerSeatId: Int,
    card: Card,
): GameObjectInfo {
    val adventureState = card.getState(CardStateName.Secondary)
    val builder = GameObjectInfo.newBuilder()
        .setInstanceId(proxyInstanceId)
        .setGrpId(adventureGrpId)
        .setType(GameObjectType.Adventure_a4aa)
        .setZoneId(zoneId)
        .setVisibility(Visibility.Public)
        .setOwnerSeatId(ownerSeatId)
        .setControllerSeatId(controllerSeatId)
    // Copy card types from adventure state
    if (adventureState != null) {
        for (ct in adventureState.type.coreTypes) {
            builder.addCardTypes(ct.name)
        }
        for (st in adventureState.type.subtypes) {
            builder.addSubtypes(st.toString())
        }
    }
    return builder.build()
}
```

- [ ] **Step 3: Emit proxies in ZoneMapper**

This is the most complex part. For each adventure card encountered during zone population (hand, stack, battlefield, exile), also emit a companion proxy object. The proxy instanceId must be allocated from the bridge's registry (or a separate phantom ID space) and tracked for deletion.

**Approach:** In `StateMapper.buildFromGame`, after populating all zones and objects, iterate the objects list. For each adventure card, create a proxy and add to the objects list (but NOT to the zone's objectIds).

The proxy instanceId lifecycle:
- Allocate when the card enters a zone (new proxy per zone)
- Delete (add to `diffDeletedInstanceIds`) when the card leaves the zone
- Track proxy→card mapping for deletion

This needs a new tracking structure in `GameBridge` — `adventureProxyIds: Map<ForgeCardId, InstanceId>` — to track which proxy belongs to which card.

**This task is complex and tightly coupled to zone transfer detection. The implementer should:**
1. Read `StateMapper.buildFromGame` to understand where objects are assembled
2. Read `AnnotationPipeline.detectZoneTransfers` to understand instance ID reallocation
3. Design the proxy lifecycle to integrate with the existing snapshot-compare diff strategy

- [ ] **Step 4: Test with Ratcatcher Trainee puzzle**

This should be tested as part of Task 6 (integration test).

- [ ] **Step 5: Commit**

```
feat(matchdoor): adventure proxy companion objects

Emit phantom Adventure-type objects alongside adventure cards in
every zone. Proxies share grpId/zone but use type=Adventure and
are not in zone objectIds. Tracked and deleted on zone transitions.

Refs #173
```

---

### Task 6: Integration test — full adventure lifecycle

End-to-end puzzle test: cast adventure from hand → tokens created → card exiled with Qualification → cast creature from exile → creature on battlefield.

**Files:**
- Create: `matchdoor/src/test/kotlin/leyline/game/AdventurePuzzleTest.kt`

- [ ] **Step 1: Write puzzle test**

```kotlin
package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.conformance.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AdventurePuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Ratcatcher Trainee adventure lifecycle: cast adventure, tokens, exile, cast creature") {
            val pzl = """
            [metadata]
            Name:Adventure Lifecycle
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:Full adventure card lifecycle test.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanhand=Ratcatcher Trainee
            humanbattlefield=Mountain;Mountain;Mountain;Swamp;Swamp
            humanlibrary=Mountain
            aibattlefield=Forest
            ailibrary=Forest
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)
            h.phase() shouldBe "MAIN1"

            // Should have CastAdventure action available
            val snap = h.messageSnapshot()
            val aar = snap.lastAar()
            val adventureAction = aar?.actionsList?.firstOrNull { it.actionType == ActionType.CastAdventure }
            adventureAction shouldNotBe null
            adventureAction!!.grpId shouldBe 86846 // Pest Problem

            // Cast the adventure side
            h.performAction(adventureAction)

            // Pass until adventure resolves and tokens appear
            val human = h.game().registeredPlayers.first()
            h.passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.isToken }
            }.shouldBeTrue()

            // Verify tokens created
            val tokens = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
            tokens.size shouldBe 2

            // Verify card is in exile (adventure rule)
            val exiled = human.getZone(ZoneType.Exile).cards
                .firstOrNull { it.name == "Ratcatcher Trainee" }
            exiled shouldNotBe null

            // Should have Cast action for creature from exile
            // (pass priority to get fresh actions)
            h.passPriority()
            val snap2 = h.messageSnapshot()
            val aar2 = snap2.lastAar()
            val castFromExile = aar2?.actionsList?.firstOrNull {
                it.actionType == ActionType.Cast && it.grpId == 86845
            }
            castFromExile shouldNotBe null

            // Cast creature from exile
            h.performAction(castFromExile!!)

            // Pass until creature resolves to battlefield
            h.passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Ratcatcher Trainee" }
            }.shouldBeTrue()
        }
    })
```

Note: This test depends on Tasks 2-4 being complete. The harness API (`performAction`, `messageSnapshot`, `lastAar`) may differ — check `MatchFlowHarness` for the actual method names. The adventure side casting may require selecting the action by a different mechanism.

- [ ] **Step 2: Run test**

Run: `just test-one AdventurePuzzleTest`

- [ ] **Step 3: Commit**

```
test(matchdoor): adventure lifecycle integration test

Full adventure lifecycle: CastAdventure → tokens → exile → cast
creature from exile → battlefield. Ratcatcher Trainee / Pest Problem.

Refs #173
```

---

### Task 7: Format, gate, docs

- [ ] **Step 1: Run formatter**

Run: `just fmt`

- [ ] **Step 2: Run detekt + spotlessCheck**

Run: `./gradlew :matchdoor:detekt :matchdoor:spotlessCheck`

- [ ] **Step 3: Run test gate**

Run: `./gradlew :matchdoor:testGate`

- [ ] **Step 4: Run integration tests**

Run: `./gradlew :matchdoor:testIntegration`

- [ ] **Step 5: Update catalog.yaml**

Change `cast-adventure: missing` to `cast-adventure: partial` with notes about what's implemented.

- [ ] **Step 6: Update rosetta.md**

Update Qualification (type 42) from MISSING to PARTIAL. Add CastAdventure action type notes.

- [ ] **Step 7: Commit**

```
docs: update catalog + rosetta for adventure casting

Refs #173
```

---

## Out of scope

- **UserActionTaken actionType=16** — cosmetic, Cast actionType=1 works
- **Conditional first-strike static** — independent from adventure, separate issue
- **Adventure targeting** (SelectTargetsReq for adventure spells that target) — Pest Problem doesn't target, unobserved
- **Limbo body** — creature body in Limbo during adventure cast. Confirmed by recording but complex to wire. Can be added incrementally — the game functions without it (client may show stale hand card briefly)
