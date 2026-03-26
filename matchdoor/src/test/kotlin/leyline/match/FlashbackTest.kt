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
Name:Flashback Think Twice
Goal:Cast Think Twice from graveyard via flashback.
Turns:5
Difficulty:Easy

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=20

humanbattlefield=Island;Island;Island
humangraveyard=Think Twice
humanlibrary=Plains;Plains;Plains;Plains;Plains
aibattlefield=Mountain
ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
""".trimIndent()

class FlashbackTest :
    FunSpec({

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("ActionMapper offers Cast for flashback card in GY (pure board setup)").config(tags = setOf(ConformanceTag)) {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Island", human, ZoneType.Battlefield)
                base.addCard("Think Twice", human, ZoneType.Graveyard)
            }
            val human = game.humanPlayer

            // Verify Think Twice is in GY
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
            if (castActions.isEmpty()) {
                val tt = gyCards.first { it.name == "Think Twice" }
                val sa = leyline.bridge.chooseCastAbility(tt, human)
                val canPay = if (sa != null) {
                    try {
                        forge.ai.ComputerUtilMana.canPayManaCost(sa, human, 0, false)
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                error(
                    "No Cast action. chooseCastAbility=${sa != null} canPay=$canPay " +
                        "keywords=${tt.keywords.map { it.toString() }} " +
                        "phase=${game.phaseHandler.phase} " +
                        "all=${actions.actionsList.map { "${it.actionType}(grp=${it.grpId})" }}",
                )
            }
            castActions.shouldNotBeEmpty()
            val castAction = castActions.first()
            castAction.manaCostCount.shouldBeGreaterThan(0)
        }

        // --- Integration test: full cast+resolve cycle ---

        test("Think Twice flashback: cast resolves, draws card, exiled").config(tags = setOf(IntegrationTag)) {
            val h = MatchFlowHarness(validating = false)
            try {
                h.connectAndKeepPuzzleText(PUZZLE)

                val player = h.bridge.getPlayer(SeatId(1))!!
                val handSizeBefore = player.getZone(ZoneType.Hand).size()

                // Cast Think Twice from GY
                h.castSpellByName("Think Twice").shouldBeTrue()

                // Pass to resolve
                h.passPriority()

                // Should have drawn a card
                player.getZone(ZoneType.Hand).size() shouldBe handSizeBefore + 1

                // Think Twice should be in Exile (flashback replacement effect)
                val exile = player.getZone(ZoneType.Exile).cards
                exile.any { it.name == "Think Twice" }.shouldBeTrue()

                // Should NOT be in GY anymore
                val gy = player.getZone(ZoneType.Graveyard).cards
                gy.none { it.name == "Think Twice" }.shouldBeTrue()
            } finally {
                h.shutdown()
            }
        }
    })
