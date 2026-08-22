package leyline.board.formats

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beInCommandOf
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Brawl match init — verifies starting life, hand draw, and commander in command zone.
 */
class BrawlMatchFlowTest :
    BoardTest({

        test("brawl game starts with correct life, hand, and commander in command zone") {
            val brawlDeck =
                """
                [Commander]
                1 Isamaru, Hound of Konda
                [Deck]
                25 Plains
                33 Savannah Lions
                """.trimIndent()

            val board = startGameAtMain1(seed = 42L, deckList = brawlDeck, variant = "brawl")

            val human = board.human
            assertSoftly {
                board.game.phaseHandler.turn shouldBe 2
                human.life shouldBe 25
                human.getZone(ZoneType.Hand).size() shouldBe 8
                "Isamaru, Hound of Konda" should beInCommandOf(human, count = 1)
                board.bridge.getHandGrpIds(SeatId(1)).shouldNotBeEmpty()
                board.bridge.getCommanderGrpIds(SeatId(1)) shouldBe listOf(72175, 72175)
            }
        }

        test("commander recast offer includes commander tax") {
            val brawlDeck =
                """
                [Commander]
                1 Isamaru, Hound of Konda
                [Deck]
                25 Plains
                33 Savannah Lions
                """.trimIndent()

            val board = startGameAtMain1(seed = 42L, deckList = brawlDeck, variant = "brawl")
            val activeSeat = if (board.game.phaseHandler.isPlayerTurn(board.bridge.getPlayer(SeatId(1)))) SeatId(1) else SeatId(2)
            val activePlayer = board.bridge.getPlayer(activeSeat)!!
            val commander = activePlayer.getZone(ZoneType.Command).cards.first { it.name == "Isamaru, Hound of Konda" }
            repeat(3) { addCard("Plains", activePlayer, ZoneType.Battlefield) }
            activePlayer.incCommanderCast(commander)

            val commanderIid = board.bridge.instanceId(commander)
            val actions =
                ActionMapper.buildFromSnapshot(
                    activeSeat.value,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                )
            val recastOffer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast && it.instanceId == commanderIid
                }
                    ?: error("missing commander recast offer for active seat ${activeSeat.value}")

            recastOffer should haveManaCost(generic = 2, white = 1)
        }
    })
