package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import wotc.mtgo.gre.external.messaging.Messages.*

class EatenAliveInteractionTest :
    InteractionTest({
        val eatenAliveState = """
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
            val iid = human.getZone(forge.game.zone.ZoneType.Hand).cards
                .first { it.name == cardName }
                .let { harness.bridge.getOrAllocInstanceId(leyline.bridge.ForgeCardId(it.id)).value }
            return allMessages.asReversed()
                .first { it.hasActionsAvailableReq() }
                .actionsAvailableReq
                .ofType(ActionType.Cast)
                .filter { it.instanceId == iid }
        }

        test("Eaten Alive exposes separate cast variants for sacrifice vs mana mode") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val casts = latestCastActionsFor("Eaten Alive")
            casts shouldHaveSize 2

            val tapCounts = casts.map { it.autoTapSolution.autoTapActionsCount }.sorted()
            tapCounts shouldBe listOf(1, 5)
            casts.map { it.manaCostList.map { req -> req.count } }.distinct() shouldBe listOf(listOf(1))
        }

        test("sacrifice-mode cast shows choose-or-cost then reaches targeting") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val sacrificeAction = latestCastActionsFor("Eaten Alive")
                .first { it.autoTapSolution.autoTapActionsCount == 1 }
            val snap = messageSnapshot()
            submitAction(sacrificeAction)

            val ctoReq = messagesSince(snap).firstOrNull { it.hasCastingTimeOptionsReq() }?.castingTimeOptionsReq
            assertSoftly {
                ctoReq shouldNotBe null
                ctoReq!!.castingTimeOptionReqList shouldHaveSize 1
                ctoReq.castingTimeOptionReqList.first().castingTimeOptionType shouldBe CastingTimeOptionType.ChooseOrCost
            }

            val targetSnap = messageSnapshot()
            respondToOptionalCost(1)
            messagesSince(targetSnap).any { it.hasSelectTargetsReq() } shouldBe true
        }

        test("mana-mode cast shows choose-or-cost then reaches targeting") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val manaAction = latestCastActionsFor("Eaten Alive")
                .first { it.autoTapSolution.autoTapActionsCount == 5 }
            val snap = messageSnapshot()
            submitAction(manaAction)

            val ctoReq = messagesSince(snap).firstOrNull { it.hasCastingTimeOptionsReq() }?.castingTimeOptionsReq
            ctoReq shouldNotBe null

            val targetSnap = messageSnapshot()
            respondToOptionalCost(2)
            messagesSince(targetSnap).any { it.hasSelectTargetsReq() } shouldBe true
        }
    })
