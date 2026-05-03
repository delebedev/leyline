package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.types.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Room (split-room enchantment) cast actions — `CastLeftRoom` (action type 22)
 * and `CastRightRoom` (action type 23).
 *
 * Each emit carries `actionType + instanceId + manaCost` only — door identity
 * is encoded by `actionType` alone. From hand both doors are locked, so both
 * offers fire when payable. From battlefield only the still-locked side(s)
 * appear; once both doors are unlocked nothing surfaces.
 *
 * Test card: Surgical Suite // Hospital Room (grpId 92094). Left door
 * "Surgical Suite" {1}{W}; right door "Hospital Room" {3}{W}.
 */
@Suppress("WeakAssertionOnly")
class RoomsTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        fun roomOffersForIid(
            actions: List<Action>,
            instanceId: Int,
        ): List<Action> =
            actions.filter {
                (it.actionType == ActionType.CastLeftRoom || it.actionType == ActionType.CastRightRoom) &&
                    it.instanceId == instanceId
            }

        test("room in hand with both door costs payable → both CastLeft/RightRoom offers") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    // 4 Plains covers right door {3}{W}; left {1}{W} also payable.
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val offers = roomOffersForIid(actions.actionsList, iid)
            offers.map { it.actionType } shouldContainExactlyInAnyOrder
                listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            assertSoftly {
                offers.forEach { offer ->
                    // No grpId / facetId / abilityGrpId / alternativeGrpId — minimal envelope.
                    offer.grpId shouldBe 0
                    offer.facetId shouldBe 0
                    offer.abilityGrpId shouldBe 0
                    offer.alternativeGrpId shouldBe 0
                    offer.manaCostCount shouldNotBe 0
                }
            }
        }

        test("room in hand with insufficient mana for right door → only left offer") {
            // 2 Plains: left {1}{W} payable, right {3}{W} not.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val activeLeft =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.CastLeftRoom && it.instanceId == iid
                }
            val activeRight =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.CastRightRoom && it.instanceId == iid
                }
            val inactiveRight =
                actions.inactiveActionsList.firstOrNull {
                    it.actionType == ActionType.CastRightRoom && it.instanceId == iid
                }
            assertSoftly {
                activeLeft shouldNotBe null
                activeRight shouldBe null
                inactiveRight shouldNotBe null
            }
        }

        test("room in graveyard → no door cast offers") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.isRoom }
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            roomOffersForIid(actions.actionsList, iid).shouldBeEmpty()
            roomOffersForIid(actions.inactiveActionsList, iid).shouldBeEmpty()
        }

        test("room on battlefield with left already unlocked → only CastRightRoom offered") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            // Pretend the left door already unlocked. Forge's `unlockRoom` updates state too.
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val offers = roomOffersForIid(actions.actionsList, iid)
            offers.map { it.actionType } shouldContainExactlyInAnyOrder listOf(ActionType.CastRightRoom)
        }

        test("room on battlefield with both doors unlocked → no door cast offers") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            card.unlockRoom(human, forge.card.CardStateName.RightSplit)
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            roomOffersForIid(actions.actionsList, iid).shouldBeEmpty()
            roomOffersForIid(actions.inactiveActionsList, iid).shouldBeEmpty()
        }

        test("pickRoomDoorSa from hand returns the per-door SpellPermanent SA") {
            // Regression: ActionPerformer's CastRightRoom accept arm originally
            // looked up `card.getUnlockAbility(state)` and matched by reference
            // in `getAllCastableAbilities`. From hand the unlock SA's canPlay
            // returns false (zone restriction), so the filter dropped it,
            // indexOfFirst returned -1, abilityIndex was null, and PlayerAction
            // .CastSpell(cardId, null) fell through to candidates.first() —
            // always the LeftSplit SpellPermanent. CastRightRoom silently cast
            // the LEFT door. pickRoomDoorSa now picks the per-door SpellPermanent
            // from getSpells() so the offer side and accept side agree.
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    repeat(5) { base.addCard("Plains", human, ZoneType.Battlefield) }
                    base.addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }

            val leftSa = leyline.bridge.pickRoomDoorSa(card, forge.card.CardStateName.LeftSplit)
            val rightSa = leyline.bridge.pickRoomDoorSa(card, forge.card.CardStateName.RightSplit)
            val castable = leyline.bridge.getAllCastableAbilities(card, human)
            assertSoftly {
                leftSa shouldNotBe null
                rightSa shouldNotBe null
                leftSa!!.cardStateName shouldBe forge.card.CardStateName.LeftSplit
                rightSa!!.cardStateName shouldBe forge.card.CardStateName.RightSplit
                // Both must be reachable in getAllCastableAbilities so the
                // ActionPerformer accept arm's `===` lookup succeeds.
                castable.any { it === leftSa } shouldBe true
                castable.any { it === rightSa } shouldBe true
            }
            // Just to keep the unused warning quiet.
            b.toString()
        }

        test("StateMapper emits LeftUnlocked Designation pAnn for bf room with left door open") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            val iid = b.getOrAllocInstanceId(ForgeCardId(card.id)).value

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val result =
                leyline.game.mapping.StateMapper
                    .buildFromSnapshot(snap, 0, "test", b)

            val designations =
                result.gsm.persistentAnnotationsList.filter { ann ->
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.Designation in ann.typeList &&
                        ann.affectedIdsList.contains(iid)
                }
            val designationTypes =
                designations.flatMap { ann ->
                    ann.detailsList
                        .filter { it.key == "DesignationType" && it.valueInt32Count > 0 }
                        .map { it.getValueInt32(0) }
                }
            assertSoftly {
                designationTypes shouldContain 19 // LeftUnlocked
                designationTypes shouldNotContain 20 // Right not unlocked
            }
        }

        test("snapshot exposes door state for battlefield room") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                    base.addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.RightSplit)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects[ForgeCardId(card.id)]
            assertSoftly {
                cardSnap shouldNotBe null
                cardSnap!!.isRoom shouldBe true
                cardSnap.isLeftDoorUnlocked shouldBe false
                cardSnap.isRightDoorUnlocked shouldBe true
            }
        }
    })
