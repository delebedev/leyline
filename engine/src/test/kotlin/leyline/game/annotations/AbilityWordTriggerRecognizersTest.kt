package leyline.game.annotations

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.testkit.Board
import leyline.testkit.BoardTest

class AbilityWordTriggerRecognizersTest :
    BoardTest({
        fun voidBoard(
            includeLand: Boolean = false,
            includeWarpCard: Boolean = false,
            includeDeparture: Boolean = false,
        ): Board {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Insatiable Skittermaw", human)
                    if (includeLand) addCard("Forest", ai)
                    if (includeWarpCard) addCard("Anticausal Vestige", human, ZoneType.Hand)
                    if (includeDeparture) addCard("Grizzly Bears", ai)
                }
            if (includeDeparture) {
                val departing =
                    board.ai.battlefield.card("Grizzly Bears")
                exile(departing, board.game)
            }
            return board
        }

        fun Board.voidEntries(): List<AbilityWordScanner.AbilityWordEntry> =
            AbilityWordScanner
                .scan(
                    battlefieldCards =
                        game.registeredPlayers.flatMap { player ->
                            player.getZone(ZoneType.Battlefield).cards.toList()
                        },
                    instanceIdResolver = { fid: ForgeCardId -> bridge.getOrAllocInstanceId(fid) },
                    registryResolver = { null },
                ).filter { it.abilityWordName == "Void" }

        test("Void is inactive before a qualifying turn event") {
            val board = voidBoard()

            board.voidEntries().shouldBeEmpty()
        }

        test("Void activates for every controller and aggregates each controller's sources after a nonland leaves") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Insatiable Skittermaw", human)
                    addCard("Hylderblade", human)
                    addCard("Insatiable Skittermaw", ai)
                    addCard("Grizzly Bears", ai)
                }
            val departing =
                board.ai.battlefield.card("Grizzly Bears")
            exile(departing, board.game)

            val entries = board.voidEntries().sortedBy { it.affectorId }
            val humanSources =
                listOf("Insatiable Skittermaw", "Hylderblade").map { name ->
                    val card =
                        board.human.battlefield.card(name)
                    board.instanceId(card.id)
                }
            val aiSource =
                board.ai.battlefield.card("Insatiable Skittermaw")

            entries shouldHaveSize 2
            assertSoftly {
                entries[0].affectorId shouldBe 1
                entries[0].affectedIds.toSet() shouldBe humanSources.toSet()
                entries[1].affectorId shouldBe 2
                entries[1].affectedIds shouldBe listOf(board.instanceId(aiSource.id))
                entries.flatMap { listOf(it.value, it.threshold, it.abilityGrpId) }.toSet() shouldBe setOf(null)
            }
        }

        test("Void ignores land-only departures") {
            val board = voidBoard(includeLand = true)
            val land =
                board.ai.battlefield.card("Forest")

            exile(land, board.game)

            board.voidEntries().shouldBeEmpty()
        }

        test("Void activates after a warped spell was cast") {
            val board = voidBoard(includeWarpCard = true)
            val warpCard =
                board.human.hand.card("Anticausal Vestige")
            val warpAbility = warpCard.spells.first()
            warpAbility.setAlternativeCost(AlternativeCost.Warp)
            warpAbility.activatingPlayer = board.human
            board.game.stack.spellsCastThisTurn
                .add(warpAbility)

            val entry = board.voidEntries().single()
            assertSoftly {
                entry.affectorId shouldBe 1
                entry.affectedIds shouldBe
                    listOf(
                        board.instanceId(
                            board.human
                                .getZone(ZoneType.Battlefield)
                                .cards
                                .first()
                                .id,
                        ),
                    )
            }
        }

        test("Void disappears after turn state resets") {
            val board = voidBoard(includeDeparture = true)
            board.voidEntries() shouldHaveSize 1

            board.game.leftBattlefieldThisTurn.clear()
            board.game.stack.spellsCastThisTurn
                .clear()

            board.voidEntries().shouldBeEmpty()
        }
    })
