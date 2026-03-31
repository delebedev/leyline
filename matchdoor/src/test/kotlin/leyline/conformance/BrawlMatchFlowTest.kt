package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId

/**
 * Brawl match init — verifies hand draw, starting life, and commander zone
 * for commander-variant games through the full MatchFlowHarness.
 */
class BrawlMatchFlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("brawl game starts with correct life, hand, and commander in command zone") {
            val brawlDeck = """
            [Commander]
            1 Isamaru, Hound of Konda
            [Deck]
            25 Plains
            33 Savannah Lions
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, deckList = brawlDeck, variant = "brawl")
            harness = h
            h.connectAndKeep()

            val human = h.bridge.getPlayer(SeatId(1))!!

            // Starting life should be 25 (Brawl)
            human.life shouldBe 25

            // Hand should have 7-8 cards (7 at mulligan + possible draw step)
            human.getZone(ZoneType.Hand).size() shouldBeInRange 7..8

            // Commander should be in command zone (plus Commander Effect synthetic)
            val commandCards = human.getZone(ZoneType.Command).cards.filter { it.name == "Isamaru, Hound of Konda" }
            commandCards.size shouldBe 1

            // Hand grpIds should resolve
            h.bridge.getHandGrpIds(1).shouldNotBeEmpty()

            // Game should reach Main1
            h.phase() shouldBe "MAIN1"
        }
    })
