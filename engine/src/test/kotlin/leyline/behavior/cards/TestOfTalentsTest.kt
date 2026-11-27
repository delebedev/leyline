package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.tooling.headless.HeadlessResponseMode
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.headless.ScriptedAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/**
 * Test of Talents searches the countered spell's controller's graveyard, hand and library.
 * The hand step reveals that hand to the caster, who chooses among its same-name cards.
 */
class TestOfTalentsTest :
    SessionTest({
        fun puzzle(aiHand: String) =
            """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Test of Talents
            humanbattlefield=Island;Island
            humanlibrary=Island;Island;Island;Island
            aihand=$aiHand
            aibattlefield=Island;Island;Island
            aigraveyard=Divination
            ailibrary=Divination;Forest;Forest;Forest;Forest;Forest
            """.trimIndent()

        fun MatchFlowHarness.actionsAvailable() =
            allMessages
                .lastOrNull { it.hasActionsAvailableReq() }
                ?.actionsAvailableReq
                ?.actionsList
                .orEmpty()

        fun MatchFlowHarness.divinations(zone: ZoneType) = ai.getZone(zone).cards.count { it.name == "Divination" }

        /** Counters the AI's Divination, answers every selection with all legal cards, and returns the SelectN requests seen. */
        fun MatchFlowHarness.counterDivinationTakingEverything(): List<SelectNReq> {
            passUntil(maxPasses = 10) {
                game().stack.size() > 0 &&
                    actionsAvailable().any { it.actionType == ActionType.Cast && cardByIid(it.instanceId)?.name == "Test of Talents" }
            } shouldBe true
            val stackIid = bridge.instanceId(game().stack.first().sourceCard)
            val start = messageSnapshot()
            castSpellByName("Test of Talents")
            passUntil(maxPasses = 5) { messagesSince(start).any { it.hasSelectTargetsReq() } } shouldBe true
            selectTargets(listOf(stackIid))

            val answered = mutableSetOf<Int>()
            val selections = mutableListOf<SelectNReq>()
            repeat(12) {
                val last = messagesSince(start).lastOrNull { !it.hasGameStateMessage() }
                when {
                    last != null && last.hasSelectNReq() && answered.add(last.msgId) -> {
                        selections += last.selectNReq
                        // Every card the prompt names must already be an object in the chooser's state.
                        (last.selectNReq.idsList + last.selectNReq.unfilteredIdsList).forEach { id ->
                            withClue("SelectNReq names unprojected id $id") { accumulator.objects.containsKey(id) shouldBe true }
                        }
                        respondToSelectN(last.selectNReq.idsList.take(last.selectNReq.maxSel))
                    }
                    last != null && last.hasSearchReq() && answered.add(last.msgId) ->
                        respondToSearch(last.searchReq.itemsSoughtList.toList())
                    game().stack.size() == 0 -> return selections
                    else -> passPriority()
                }
            }
            return selections
        }

        session(
            "the hand step offers the same-name cards in the revealed opponent hand",
            responseMode = HeadlessResponseMode.PolicyVisible,
            turns = 3,
            aiScript = listOf(ScriptedAction.CastSpell("Divination")),
            puzzle = puzzle("Divination;Divination;Forest"),
        ) {
            val selections = counterDivinationTakingEverything()
            val handCards = ai.getZone(ZoneType.Hand).cards
            val handStep = selections.single { req -> req.unfilteredIdsCount > req.idsCount }

            assertSoftly {
                handStep.idsList.map { cardByIid(it)?.name } shouldBe listOf("Divination")
                handStep.unfilteredIdsList.map { cardByIid(it)?.name } shouldContainExactlyInAnyOrder listOf("Divination", "Forest")
                handStep.minSel shouldBe 0
                divinations(ZoneType.Hand) shouldBe 0
                divinations(ZoneType.Exile) shouldBe 4
                // Forest stays and the opponent draws one card for the exiled hand copy.
                handCards.size shouldBe 2
            }
        }

        session(
            "the hand step without a same-name card exiles nothing from hand and draws nothing",
            responseMode = HeadlessResponseMode.PolicyVisible,
            turns = 3,
            aiScript = listOf(ScriptedAction.CastSpell("Divination")),
            puzzle = puzzle("Divination;Forest"),
        ) {
            counterDivinationTakingEverything()

            assertSoftly {
                ai.getZone(ZoneType.Hand).cards.map { it.name } shouldBe listOf("Forest")
                divinations(ZoneType.Exile) shouldBe 3
            }
        }
    })
