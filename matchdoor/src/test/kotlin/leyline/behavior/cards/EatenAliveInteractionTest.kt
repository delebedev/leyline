package leyline.behavior.cards

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.haveManaCost
import leyline.testkit.ofType
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

class EatenAliveInteractionTest :
    SessionTest({
        val eatenAliveState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Eaten Alive
            humanbattlefield=Swamp;Swamp;Swamp;Swamp;Swamp;Walking Corpse
            humanlibrary=Swamp
            aibattlefield=Centaur Courser
            ailibrary=Swamp
            """.trimIndent()

        fun latestCastActionsFor(cardName: String): List<Action> {
            val iid = human.hand.iid(cardName)
            return allMessages
                .asReversed()
                .first { it.hasActionsAvailableReq() }
                .actionsAvailableReq
                .ofType(ActionType.Cast)
                .filter { it.instanceId == iid }
        }

        fun submitAction(action: Action) {
            harness.session.onPerformAction(
                performAction {
                    mergeFrom(action)
                },
            )
            harness.drainSink()
        }

        test("Eaten Alive exposes a single cast action with base mana cost") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val casts = latestCastActionsFor("Eaten Alive")
            casts shouldHaveSize 1
            casts.single() should haveManaCost(black = 1)
        }

        test("sacrifice-mode cast resolves fully after target and sacrifice selection") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            submitAction(latestCastActionsFor("Eaten Alive").single())
            respondToOptionalCost(1)

            val targetId = ai.battlefield.iid("Centaur Courser")
            after { selectTargets(listOf(targetId)) }.expectOnePayCostsReq()

            val sacId = human.battlefield.iid("Walking Corpse")
            respondToEffectCost(listOf(sacId))

            passUntilResolved(maxPasses = 8)

            ai.getZone(ForgeZoneType.Exile).cards.map { it.name } shouldBe listOf("Centaur Courser")
            "Walking Corpse" should beInGraveyardOf(human)
        }
    })
