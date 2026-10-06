package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beOnBattlefieldOf

class ManaSourcePaymentTest :
    SessionTest({
        listOf("Swamp;Cabal Stronghold;Swamp", "Swamp;Swamp;Swamp").forEach { lands ->
            session(
                "AI casts Arena and resolves its upkeep with $lands",
                puzzle = """
                    ActivePlayer=AI
                    ActivePhase=Main2
                    HumanLife=40
                    AILife=40
                    humanbattlefield=Island
                    humanhand=Opt
                    humanlibrary=Island;Island;Island;Island;Island
                    aibattlefield=$lands
                    aihand=Phyrexian Arena
                    ailibrary=Swamp;Swamp;Swamp;Swamp;Swamp
                    """,
                turns = 5,
            ) {
                val castTurn = game().phaseHandler.turn
                passUntil(maxPasses = 30) {
                    beOnBattlefieldOf(ai).test("Phyrexian Arena").passed()
                }.shouldBeTrue()
                assertSoftly {
                    game().phaseHandler.turn shouldBe castTurn
                    "Phyrexian Arena" should beOnBattlefieldOf(ai)
                    game().getCardsIn(ZoneType.Stack).size shouldBe 0
                }
                val libraryBefore = ai.getZone(ZoneType.Library).size()
                val handBefore = ai.getZone(ZoneType.Hand).size()
                passUntil(maxPasses = 30) { ai.life < 40 }.shouldBeTrue()
                assertSoftly {
                    ai.life shouldBe 39
                    ai.getZone(ZoneType.Library).size() shouldBe libraryBefore - 1
                    ai.getZone(ZoneType.Hand).size() shouldBe handBefore + 1
                    game().getCardsIn(ZoneType.Stack).size shouldBe 0
                }
            }
        }
    })
