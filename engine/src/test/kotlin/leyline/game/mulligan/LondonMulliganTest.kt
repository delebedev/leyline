package leyline.game.mulligan

import forge.game.mulligan.LondonMulligan
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest

class LondonMulliganTest :
    BoardTest({
        listOf(false, true).forEach { firstFree ->
            test("London redraw keeps seven visible until keep, free first=$firstFree") {
                val (bridge, game, _) =
                    startWithBoard { _, human, _ ->
                        repeat(7) { addCard("Forest", human, ZoneType.Hand) }
                        repeat(20) { addCard("Island", human, ZoneType.Library) }
                    }
                val player = game.players.first()
                val mulligan = LondonMulligan(player, firstFree)
                mulligan.canMulligan() shouldBe true
                mulligan.mulligan()
                player.getZone(ZoneType.Hand).size() shouldBe 7
                mulligan.tuckCardsDuringMulligan() shouldBe if (firstFree) 0 else 1
                if (firstFree) {
                    mulligan.keep()
                } else {
                    val keeper =
                        Thread { mulligan.keep() }.apply {
                            isDaemon = true
                            start()
                        }
                    bridge.awaitTuckReady()
                    bridge.submitTuck(
                        SeatId(1),
                        player
                            .getZone(ZoneType.Hand)
                            .cards
                            .toList()
                            .take(1),
                    ) shouldBe true
                    keeper.join(1_000)
                    keeper.isAlive shouldBe false
                }
                assertSoftly {
                    player.getZone(ZoneType.Hand).size() shouldBe if (firstFree) 7 else 6
                    mulligan.hasKept() shouldBe true
                    mulligan.canMulligan() shouldBe false
                }
            }
        }
        listOf(false, true).forEach { firstFree ->
            test("London cannot redraw beyond an empty final hand, free first=$firstFree") {
                val (bridge, game, _) =
                    startWithBoard { _, human, _ ->
                        repeat(7) { addCard("Forest", human, ZoneType.Hand) }
                        repeat(20) { addCard("Island", human, ZoneType.Library) }
                    }
                val player = game.players.first()
                val mulligan = LondonMulligan(player, firstFree)
                repeat(if (firstFree) 8 else 7) {
                    mulligan.canMulligan() shouldBe true
                    mulligan.mulligan()
                    player.getZone(ZoneType.Hand).size() shouldBe 7
                }
                mulligan.canMulligan() shouldBe false
                mulligan.tuckCardsDuringMulligan() shouldBe 7
                val keeper =
                    Thread { mulligan.keep() }.apply {
                        isDaemon = true
                        start()
                    }
                bridge.awaitTuckReady()
                bridge.submitTuck(SeatId(1), player.getZone(ZoneType.Hand).cards.toList()) shouldBe true
                keeper.join(1_000)
                keeper.isAlive shouldBe false
                player.getZone(ZoneType.Hand).size() shouldBe 0
            }
        }
    })
