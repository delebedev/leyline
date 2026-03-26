package leyline.match

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.MatchFlowHarness
import leyline.conformance.humanPlayer
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

private val PUZZLE = """
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

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("ActionMapper offers Cast for flashback card in GY").config(tags = setOf(ConformanceTag)) {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Think Twice", human, ZoneType.Graveyard)
            }
            val human = game.humanPlayer

            val gyCards = human.getZone(ZoneType.Graveyard).cards
            gyCards.any { it.name == "Think Twice" }.shouldBeTrue()

            val actions = ActionMapper.buildActionList(
                player = human,
                seatId = 1,
                checkLegality = true,
                idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cards) },
                cardDataLookup = { grpId -> b.cards.findByGrpId(grpId) },
            )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            castActions.shouldNotBeEmpty()
            castActions.first().manaCostCount.shouldBeGreaterThan(0)
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
                player.getZone(ZoneType.Hand).cards.any { it.name == "Coral Merfolk" }.shouldBeTrue()

                // Think Twice went to GY (normal instant resolution)
                player.getZone(ZoneType.Graveyard).cards.any { it.name == "Think Twice" }.shouldBeTrue()
                player.getZone(ZoneType.Exile).cards.none { it.name == "Think Twice" }.shouldBeTrue()

                // --- Phase 2: cast Think Twice from GY via flashback ---
                val handBefore2 = player.getZone(ZoneType.Hand).size()
                h.castSpellByName("Think Twice").shouldBeTrue()
                h.passPriority() // resolve

                // Drew another card (Plains)
                player.getZone(ZoneType.Hand).size() shouldBe handBefore2 + 1

                // Think Twice now in EXILE (flashback replacement effect), not GY
                player.getZone(ZoneType.Exile).cards.any { it.name == "Think Twice" }.shouldBeTrue()
                player.getZone(ZoneType.Graveyard).cards.none { it.name == "Think Twice" }.shouldBeTrue()

                // Hand should contain both drawn cards: Coral Merfolk + Plains
                val hand = player.getZone(ZoneType.Hand).cards.map { it.name }
                hand.any { it == "Coral Merfolk" }.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }
    })
