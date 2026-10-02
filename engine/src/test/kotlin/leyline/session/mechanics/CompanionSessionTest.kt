package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.CompanionAction
import leyline.game.mapping.ZoneIds
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.testkit.SessionTest
import leyline.testkit.StateMapperShell
import leyline.testkit.detailInt
import leyline.testkit.detailString
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility

private val COMPANION_PUZZLE =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    humancommand=Lurrus of the Dream-Den
    humanbattlefield=Plains;Plains;Plains;Plains;Plains;Plains
    humanlibrary=Plains;Plains;Plains;Plains
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

class CompanionSessionTest :
    SessionTest({
        session("chosen companion is paid into hand without a stack ability and then cast", puzzle = COMPANION_PUZZLE) {
            val action = accumulator.actions!!.actionsList.single { it.actionType == ActionType.Special_add3 }
            val originalIid = action.instanceId
            val initial = allMessages.flatMap { it.gameStateMessage.gameObjectsList }.last { it.instanceId == originalIid }
            val designations =
                allMessages
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .filter {
                        AnnotationType.Designation in it.typeList &&
                            it.detailInt("DesignationType") == CompanionAction.DESIGNATION_TYPE
                    }
            assertSoftly {
                action.abilityGrpId shouldBe CompanionAction.ABILITY_GRP_ID
                action.facetId shouldBe originalIid
                action.grpId shouldBe initial.grpId
                action.manaCostList.single().count shouldBe 3
                action.manaCostList.single().abilityGrpId shouldBe CompanionAction.ABILITY_GRP_ID
                action.manaCostList.single().colorList shouldBe listOf(wotc.mtgo.gre.external.messaging.Messages.ManaColor.Generic)
                action.autoTapSolution.autoTapActionsList.shouldHaveSize(3)
                action.sourceId shouldBe 0
                action.alternativeGrpId shouldBe 0
                initial.zoneId shouldBe ZoneIds.P1_SIDEBOARD
                initial.visibility shouldBe Visibility.Public
                designations.map { it.affectedIdsList.single() }.toSet() shouldBe setOf(1, originalIid)
                designations.all { it.affectorId == it.affectedIdsList.single() && it.detailInt("grpid") == initial.grpId } shouldBe true
            }
            val before = messageSnapshot()
            submitAction(action)
            human.getCardsIn(ZoneType.Hand).single { it.name == "Lurrus of the Dream-Den" }.let { card ->
                bridge.instanceId(card) shouldNotBe originalIid
            }
            assertSoftly {
                game().stack.isEmpty shouldBe true
                human.getCardsIn(ZoneType.Battlefield).count { it.isTapped } shouldBe 3
                accumulator.actions!!.actionsList.none { it.actionType == ActionType.Special_add3 } shouldBe true
            }
            val messages = gameStateMessagesSince(before)
            val annotations = messages.flatMap { it.annotationsList }
            val transfer =
                annotations.single {
                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                        it.detailInt("zone_src") == ZoneIds.P1_SIDEBOARD
                }
            val handIid = transfer.affectedIdsList.single()
            val opponentHand = bridge.companionOpponentView()
            val knownCompanion = opponentHand.gameObjectsList.single { it.type == GameObjectType.RevealedCard }
            assertSoftly {
                knownCompanion.grpId shouldBe initial.grpId
                knownCompanion.zoneId shouldBe ZoneIds.P1_HAND
                knownCompanion.viewersList shouldBe listOf(2)
                opponentHand.zonesList.single { it.zoneId == ZoneIds.REVEALED_P1 }.objectInstanceIdsList shouldBe
                    listOf(knownCompanion.instanceId)
                opponentHand.gameObjectsList.none { it.instanceId == handIid } shouldBe true
                bridge
                    .companionOpponentView()
                    .gameObjectsList
                    .single { it.type == GameObjectType.RevealedCard }
                    .instanceId shouldBe
                    knownCompanion.instanceId
                transfer.detailInt("zone_dest") shouldBe ZoneIds.P1_HAND
                transfer.detailString("category") shouldBe "Put"
                transfer.affectorId shouldBe originalIid
                annotations.filter { AnnotationType.ManaPaid in it.typeList }.shouldHaveSize(3)
                annotations.filter { AnnotationType.ManaPaid in it.typeList }.all { it.affectedIdsList == listOf(originalIid) } shouldBe
                    true
                val identityIndex =
                    annotations.indexOfFirst {
                        AnnotationType.ObjectIdChanged in it.typeList &&
                            it.detailInt("orig_id") == originalIid
                    }
                identityIndex shouldBeGreaterThanOrEqual 0
                annotations.indexOfLast { AnnotationType.ManaPaid in it.typeList } shouldBeLessThan identityIndex
                identityIndex shouldBeLessThan annotations.indexOf(transfer)
                annotations.none { AnnotationType.AbilityInstanceCreated in it.typeList && it.affectorId == originalIid } shouldBe true
                annotations
                    .single {
                        AnnotationType.UserActionTaken in it.typeList &&
                            it.detailInt("actionType") == ActionType.Special_add3.number
                    }.affectedIdsList shouldBe listOf(handIid)
            }
            castSpellByName("Lurrus of the Dream-Den") shouldBe true
            passUntil(maxPasses = 8) { human.getCardsIn(ZoneType.Battlefield).any { it.name == "Lurrus of the Dream-Den" } }
            val later = gameStateMessagesSince(before)
            assertSoftly {
                later.flatMap { it.diffDeletedPersistentAnnotationIdsList }.intersect(designations.map { it.id }.toSet()).shouldBeEmpty()
                later.flatMap { it.gameObjectsList }.any { it.instanceId == originalIid && it.zoneId == ZoneIds.LIMBO } shouldBe true
                bridge.companionOpponentView().gameObjectsList.none { it.type == GameObjectType.RevealedCard } shouldBe true
                bridge
                    .projectionStateSnapshot()
                    .revealProxies.entries.size shouldBe 0
            }
        }

        session(
            "companion cannot be taken without three mana",
            fullControl = true,
            puzzle = COMPANION_PUZZLE.replace("Plains;Plains;Plains;Plains;Plains;Plains", "Plains;Plains"),
        ) {
            assertSoftly {
                accumulator.actions!!.actionsList.none { it.actionType == ActionType.Special_add3 } shouldBe true
                accumulator.actions!!
                    .inactiveActionsList
                    .single { it.actionType == ActionType.Special_add3 }
                    .manaCostList
                    .single()
                    .count shouldBe 3
                human.getCardsIn(ZoneType.Hand).none { it.name == "Lurrus of the Dream-Den" } shouldBe true
            }
        }
    })

private fun GameBridge.companionOpponentView(): GameStateMessage {
    val snapshot = viewerProjectionCursor().previousSnapshot!!
    return StateMapperShell
        .buildFromSnapshot(
            snapshot,
            snapshot.gameStateId,
            snapshot.matchId,
            this,
            viewingSeatId = 2,
            effectFacts = EffectProjectionFacts(),
            abilityExhaustionFacts = AbilityExhaustionFacts(),
        ).gsm
}
