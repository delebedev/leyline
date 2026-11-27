package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.SessionTest
import leyline.tooling.headless.ClientAccumulator
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Emrakul, the Promised End lets its caster control the opponent during the opponent's
 * next turn (CR 723); the opponent then takes an extra turn on their own.
 */
class EmrakulTurnControlTest :
    SessionTest({
        session(
            "the controlling player sees and plays the controlled player's hand until control ends",
            puzzle = PUZZLE,
        ) {
            val bearsIid = ai.hand.iid("Grizzly Bears")
            val mountainIid = ai.hand.iid("Mountain")
            castSpellByName("Emrakul, the Promised End").shouldBeTrue()
            if (allMessages.lastOrNull()?.hasSelectTargetsReq() == true) selectTargets(listOf(OPPONENT_SEAT))

            // Controlled turn: the controller is offered the controlled player's plays (CR 723.5).
            passUntil(maxPasses = 40) { turn() == 2 && offeredDuringTurn(2).contains(bearsIid) }.shouldBeTrue()
            val firstControlledRequest =
                allMessages.firstOrNull { it.hasActionsAvailableReq() && activeSeatAt(it) == OPPONENT_SEAT }.shouldNotBeNull()
            val seenAtRequest =
                ClientAccumulator().apply { processAll(allMessages.takeWhile { it !== firstControlledRequest }) }.objects
            assertSoftly {
                seenAtRequest[bearsIid]?.grpId shouldNotBe 0
                seenAtRequest[mountainIid]?.grpId shouldNotBe 0
                offeredDuringTurn(2) shouldContainAll listOf(bearsIid, mountainIid)
            }

            playLand("Mountain").shouldBeTrue()
            val castBears =
                latestActions()
                    .firstOrNull { it.actionType == ActionType.Cast && cardByIid(it.instanceId)?.name == "Grizzly Bears" }
                    .shouldNotBeNull()
            submitAction(castBears)
            passUntilResolved()

            // The spells are the controlled player's: their cards, their battlefield (CR 723.3, 723.5a).
            val aiBattlefield = ai.getZone(ForgeZoneType.Battlefield).cards
            assertSoftly {
                aiBattlefield.map { it.name } shouldContainAll listOf("Mountain", "Grizzly Bears")
                aiBattlefield.first { it.name == "Grizzly Bears" }.controller shouldBe ai
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .map { it.name }
                    .toSet() shouldBe setOf("Forest", "Emrakul, the Promised End")
            }

            // Extra turn: control has ended, so the hand is hidden and nothing of the opponent's is offered.
            passUntilTurn(3, maxPasses = 60)
            passUntil(maxPasses = 20) { phase() == "MAIN1" }
            val handNow = ai.getZone(ForgeZoneType.Hand).cards.map { bridge.instanceId(it) }
            val seenAfter = ClientAccumulator().apply { processAll(allMessages) }.objects
            assertSoftly {
                handNow.map { seenAfter[it]?.grpId ?: 0 }.toSet() shouldContainExactlyInAnyOrder setOf(0)
                offeredDuringTurn(3) shouldNotContainAnyOf handNow
            }
        }

        session(
            "the controlling player is offered the controlled player's commander",
            puzzle = COMMANDER_PUZZLE,
        ) {
            val commander = ai.commanders.single()
            castSpellByName("Emrakul, the Promised End").shouldBeTrue()
            if (allMessages.lastOrNull()?.hasSelectTargetsReq() == true) selectTargets(listOf(OPPONENT_SEAT))

            passUntil(maxPasses = 40) { turn() == 2 && offeredDuringTurn(2).contains(bridge.instanceId(commander)) }.shouldBeTrue()
            val castCommander =
                latestActions()
                    .firstOrNull { it.actionType == ActionType.Cast && it.instanceId == bridge.instanceId(commander) }
                    .shouldNotBeNull()
            submitAction(castCommander)
            passUntilResolved()

            ai
                .getZone(ForgeZoneType.Battlefield)
                .cards
                .single { it.name == commander.name }
                .controller shouldBe ai
        }

        session(
            "the controlling player sees the top library card the controlled player may look at",
            puzzle = LOOK_AT_TOP_PUZZLE,
        ) {
            fun topCardSeen(messages: List<GREToClientMessage>): Boolean {
                val top = ai.getZone(ForgeZoneType.Library).cards.first()
                return (ClientAccumulator().apply { processAll(messages) }.objects[bridge.instanceId(top)]?.grpId ?: 0) != 0
            }
            topCardSeen(allMessages) shouldBe false
            castSpellByName("Emrakul, the Promised End").shouldBeTrue()
            if (allMessages.lastOrNull()?.hasSelectTargetsReq() == true) selectTargets(listOf(OPPONENT_SEAT))

            passUntil(maxPasses = 40) { turn() == 2 && phase() == "MAIN1" && allMessages.last().hasActionsAvailableReq() }.shouldBeTrue()
            topCardSeen(allMessages) shouldBe true
        }
    }) {
    private companion object {
        val PUZZLE =
            """
            [metadata]
            Name:Emrakul turn control
            Goal:Survive
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Emrakul, the Promised End
            humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
            humanlibrary=Forest;Forest;Forest
            aihand=Mountain;Grizzly Bears
            aibattlefield=Forest;Forest
            ailibrary=Island;Island;Island
            """.trimIndent()

        val COMMANDER_PUZZLE =
            PUZZLE
                .replace("aihand=Mountain;Grizzly Bears", "aihand=Island")
                .replace("ailibrary=Island;Island;Island", "ailibrary=Island;Island;Island\naicommand=Ayula, Queen Among Bears|IsCommander")

        val LOOK_AT_TOP_PUZZLE = PUZZLE.replace("aibattlefield=Forest;Forest", "aibattlefield=Forest;Forest;Bolas's Citadel")

        fun MatchFlowHarness.activeSeatAt(message: GREToClientMessage): Int {
            var activeSeat = 0
            for (m in allMessages) {
                if (m.hasGameStateMessage() && m.gameStateMessage.hasTurnInfo()) {
                    m.gameStateMessage.turnInfo.activePlayer
                        .takeIf { it != 0 }
                        ?.let { activeSeat = it }
                }
                if (m === message) break
            }
            return activeSeat
        }

        fun MatchFlowHarness.offeredDuringTurn(turnNumber: Int): List<Int> {
            var turn = 0
            return allMessages
                .filter { m ->
                    if (m.hasGameStateMessage() && m.gameStateMessage.turnInfo.turnNumber != 0) {
                        turn = m.gameStateMessage.turnInfo.turnNumber
                    }
                    turn == turnNumber && m.hasActionsAvailableReq()
                }.flatMap { it.actionsAvailableReq.actionsList.map { action -> action.instanceId } }
        }

        fun MatchFlowHarness.latestActions(): List<Action> =
            allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
    }
}
