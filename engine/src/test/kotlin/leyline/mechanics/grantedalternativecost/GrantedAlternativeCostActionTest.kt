package leyline.mechanics.grantedalternativecost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.lostPlays
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Dream Halls grants every spell an alternative cost: discard a card that
 * shares a color with it. The hand cast offer carries Dream Halls' static
 * ability row as `alternativeGrpId`.
 */
class GrantedAlternativeCostActionTest :
    BoardTest({
        fun Board.casts(name: String): List<Action> {
            val grpId = bridge.cardRepository.findGrpIdByName(name)
            return actions().actionsList.filter { it.actionType == ActionType.Cast && it.grpId == grpId }
        }

        fun Board.dreamHallsRows(): List<Int> {
            val dreamHalls = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Dream Halls" }
            val data = bridge.cardRepository.findByGrpId(bridge.resolveGrpId(dreamHalls)) ?: return emptyList()
            return data.abilityIds.map { it.first }
        }

        test("a spell in hand is offered Dream Halls' cost when no mana is available") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Dream Halls", human, ZoneType.Battlefield)
                    addCard("Wind Drake", human, ZoneType.Hand)
                    addCard("Raging Goblin", human, ZoneType.Hand)
                    addCard("Opt", human, ZoneType.Hand)
                    addCard("Shock", human, ZoneType.Hand)
                }
            board.offerDiff().lostPlays() shouldBe emptyList()
            val drake = board.casts("Wind Drake")
            val goblin = board.casts("Raging Goblin")
            assertSoftly {
                drake.map { it.alternativeGrpId } shouldContainExactly listOf(drake.single().alternativeGrpId)
                drake.single().alternativeGrpId shouldNotBe 0
                board.dreamHallsRows() shouldContain drake.single().alternativeGrpId
                drake.single().manaCostList shouldBe emptyList()
                goblin.map { it.alternativeGrpId } shouldContainExactly listOf(drake.single().alternativeGrpId)
            }
        }

        test("a spell in hand keeps its mana cast beside Dream Halls' cost") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Dream Halls", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Wind Drake", human, ZoneType.Hand)
                    addCard("Opt", human, ZoneType.Hand)
                }
            board.offerDiff().lostPlays() shouldBe emptyList()
            val drake = board.casts("Wind Drake")
            assertSoftly {
                drake.map { it.alternativeGrpId }.sorted() shouldBe listOf(0, drake.maxOf { it.alternativeGrpId })
                drake.single { it.alternativeGrpId == 0 }.manaCostList.sumOf { it.count } shouldBe 3
            }
        }

        test("a commander keeps its mana cast beside Dream Halls' cost") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Dream Halls Commander
                    Goal:Survive
                    Turns:1
                    Difficulty:Easy

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=40
                    AILife=40
                    humanbattlefield=Dream Halls;Island;Island;Island
                    humanhand=Opt
                    humancommand=Wind Drake|IsCommander
                    humanlibrary=Island
                    ailibrary=Forest
                    """.trimIndent(),
                )
            board.offerDiff().lostPlays() shouldBe emptyList()
            val drake = board.casts("Wind Drake")
            assertSoftly {
                drake.map { it.alternativeGrpId }.sorted() shouldBe listOf(0, drake.maxOf { it.alternativeGrpId })
                board.dreamHallsRows() shouldContain drake.maxOf { it.alternativeGrpId }
                drake.single { it.alternativeGrpId == 0 }.manaCostList.sumOf { it.count } shouldBe 3
                drake.single { it.alternativeGrpId != 0 }.manaCostList shouldBe emptyList()
            }
        }
    })
