package leyline.mechanics.grantedalternativecost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Casting through Dream Halls discards a card that shares a color with the
 * spell and records Dream Halls' static row as the casting-time cost.
 */
class GrantedAlternativeCostLifecycleTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Dream Halls
            humanhand=Wind Drake;Raging Goblin;Opt;Shock
            humanlibrary=Island;Island;Island
            ailibrary=Forest;Forest;Forest
            """.trimIndent()

        fun MatchFlowHarness.castOffer(card: String): Action {
            val iid = human.hand.iid(card)
            return allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .single { it.actionType == ActionType.Cast && it.instanceId == iid }
        }

        fun MatchFlowHarness.castDiscarding(
            card: String,
            discard: String,
        ): Pair<Action, Int> {
            val offer = castOffer(card)
            val castStart = messageSnapshot()
            submitAction(offer)
            val discardIid = human.hand.iid(discard)
            allMessages.last().selectNReq.idsList shouldContain discardIid
            respondToSelectN(listOf(discardIid))
            passUntilResolved(maxPasses = 20)
            val castingTimeOption =
                messagesSince(castStart)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .single { it.typeList.contains(AnnotationType.CastingTimeOption) }
            return offer to castingTimeOption.detailInt("alternateCostGrpId")
        }

        session("each spell cast through Dream Halls discards a card sharing its color and records its row", puzzle = puzzle) {
            val (drakeOffer, drakeCost) = castDiscarding("Wind Drake", "Opt")
            val (goblinOffer, goblinCost) = castDiscarding("Raging Goblin", "Shock")
            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContainExactlyInAnyOrder
                    listOf("Dream Halls", "Wind Drake", "Raging Goblin")
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContainExactlyInAnyOrder listOf("Opt", "Shock")
                drakeCost shouldBe drakeOffer.alternativeGrpId
                goblinCost shouldBe goblinOffer.alternativeGrpId
            }
        }
    })
