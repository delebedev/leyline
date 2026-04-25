package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

class EatenAliveInteractionTest :
    InteractionTest({
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
            val iid =
                human
                    .getZone(forge.game.zone.ZoneType.Hand)
                    .cards
                    .first { it.name == cardName }
                    .let { harness.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }
            return allMessages
                .asReversed()
                .first { it.hasActionsAvailableReq() }
                .actionsAvailableReq
                .ofType(ActionType.Cast)
                .filter { it.instanceId == iid }
        }

        fun zoneInstanceId(
            player: forge.game.player.Player,
            zone: ForgeZoneType,
            cardName: String,
        ): Int =
            player
                .getZone(zone)
                .cards
                .first { it.name == cardName }
                .let { harness.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }

        test("Eaten Alive exposes a single cast action with base mana cost") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val casts = latestCastActionsFor("Eaten Alive")
            casts shouldHaveSize 1
            casts.single().manaCostList.map { req -> req.count } shouldBe listOf(1)
        }

        test("cast shows choose-or-cost then reaches targeting for sacrifice mode") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val snap = messageSnapshot()
            castSpellByName("Eaten Alive") shouldBe true

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

        test("cast shows choose-or-cost then reaches targeting for mana mode") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            val snap = messageSnapshot()
            castSpellByName("Eaten Alive") shouldBe true

            val ctoReq = messagesSince(snap).firstOrNull { it.hasCastingTimeOptionsReq() }?.castingTimeOptionsReq
            ctoReq shouldNotBe null

            val targetSnap = messageSnapshot()
            respondToOptionalCost(2)
            messagesSince(targetSnap).any { it.hasSelectTargetsReq() } shouldBe true
        }

        test("mana-mode cast resolves fully after target selection") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            castSpellByName("Eaten Alive") shouldBe true
            respondToOptionalCost(2)
            selectTargets(listOf(zoneInstanceId(ai, ForgeZoneType.Battlefield, "Centaur Courser")))

            ai.getZone(ForgeZoneType.Exile).cards.map { it.name } shouldBe listOf("Centaur Courser")
            human.getZone(ForgeZoneType.Graveyard).cards.none { it.name == "Walking Corpse" } shouldBe true
        }

        test("sacrifice-mode cast resolves fully after target and sacrifice selection") {
            startPuzzle(eatenAliveState, name = "Eaten Alive")

            castSpellByName("Eaten Alive") shouldBe true
            respondToOptionalCost(1)

            val targetId = zoneInstanceId(ai, ForgeZoneType.Battlefield, "Centaur Courser")
            val sacrificePromptSnap = messageSnapshot()
            selectTargets(listOf(targetId))
            messagesSince(sacrificePromptSnap).any { it.hasPayCostsReq() } shouldBe true

            val sacId = zoneInstanceId(human, ForgeZoneType.Battlefield, "Walking Corpse")
            respondToEffectCost(listOf(sacId))

            passUntilResolved(maxPasses = 8)

            ai.getZone(ForgeZoneType.Exile).cards.map { it.name } shouldBe listOf("Centaur Courser")
            human.getZone(ForgeZoneType.Graveyard).cards.any { it.name == "Walking Corpse" } shouldBe true
        }
    })
