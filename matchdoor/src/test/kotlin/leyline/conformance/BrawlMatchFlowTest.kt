package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId

/**
 * Brawl match init — verifies starting life, hand draw, and commander in command zone.
 */
class BrawlMatchFlowTest :
    SubsystemTest({

        test("brawl game starts with correct life, hand, and commander in command zone") {
            val brawlDeck =
                """
                [Commander]
                1 Isamaru, Hound of Konda
                [Deck]
                25 Plains
                33 Savannah Lions
                """.trimIndent()

            val (b, _, _) = startGameAtMain1(seed = 42L, deckList = brawlDeck, variant = "brawl")

            val human = humanPlayer(b)
            human.life shouldBe 25
            human.getZone(ZoneType.Hand).size() shouldBe 7

            val commandCards = human.getZone(ZoneType.Command).cards.filter { it.name == "Isamaru, Hound of Konda" }
            commandCards.size shouldBe 1

            b.getHandGrpIds(SeatId(1)).shouldNotBeEmpty()
        }
    })
