package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.IntegrationTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.conformance.BoardTestBase
import leyline.conformance.MatchFlowHarness
import leyline.conformance.haveManaCost
import leyline.conformance.humanPlayer
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.ActionType

private val PUZZLE =
    """
    [metadata]
    Name:Flashback Think Twice — Full Lifecycle
    Goal:Cast from hand, then flashback from GY. Drawn creature is win condition.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=2

    humanhand=Think Twice
    humanbattlefield=Island;Island;Island;Island;Island;Island
    humanlibrary=Coral Merfolk;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class FlashbackTest :
    FunSpec({

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("ActionMapper offers Cast for flashback card in GY").config(tags = setOf(BoardTag)) {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Island", human, ZoneType.Battlefield)
                    base.addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer

            val gyCards = human.getZone(ZoneType.Graveyard).cards
            val thinkTwice = gyCards.firstOrNull { it.name == "Think Twice" }
            thinkTwice shouldNotBe null
            val thinkTwiceIid = b.getOrAllocInstanceId(ForgeCardId(thinkTwice!!.id)).value
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            val castActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == thinkTwiceIid
                }
            castActions.shouldNotBeEmpty()
            val flashbackOffer = castActions.firstOrNull { it.abilityGrpId == flashbackAbilityGrpId }
            flashbackOffer shouldNotBe null
            assertSoftly {
                flashbackOffer!!.grpId shouldBe thinkTwiceGrpId
                flashbackOffer.facetId shouldBe thinkTwiceIid
                flashbackOffer should haveManaCost(generic = 2, blue = 1)
            }
        }

        test("full lifecycle: hand cast → GY → flashback → exile").config(tags = setOf(IntegrationTag)) {
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(PUZZLE)

                val player = h.bridge.getPlayer(SeatId(1))!!

                // --- Phase 1: cast Think Twice from hand ---
                val handBefore = player.getZone(ZoneType.Hand).size()
                h.castSpellByName("Think Twice").shouldBeTrue()
                h.passPriority() // resolve

                // Drew Coral Merfolk (net hand size: -1 cast + 1 draw = 0 change)
                player.getZone(ZoneType.Hand).size() shouldBe handBefore
                player
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()

                // Think Twice went to GY (normal instant resolution)
                player
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                player
                    .getZone(ZoneType.Exile)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()

                // --- Phase 2: cast Think Twice from GY via flashback ---
                val handBefore2 = player.getZone(ZoneType.Hand).size()
                h.castFromGraveyard("Think Twice").shouldBeTrue()
                h.passPriority() // resolve

                // Drew another card (Plains)
                player.getZone(ZoneType.Hand).size() shouldBe handBefore2 + 1

                // Think Twice now in EXILE (flashback replacement effect), not GY
                player
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Think Twice" }
                    .shouldBeTrue()
                player
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .none { it.name == "Think Twice" }
                    .shouldBeTrue()

                // Hand should contain both drawn cards: Coral Merfolk + Plains
                val hand = player.getZone(ZoneType.Hand).cards.map { it.name }
                hand.any { it == "Coral Merfolk" }.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }
    })
