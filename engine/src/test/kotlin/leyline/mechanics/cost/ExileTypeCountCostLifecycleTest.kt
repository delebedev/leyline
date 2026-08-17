package leyline.mechanics.cost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest

/**
 * Card-type-count exile cost payment (`withTypesGE` shape) through
 * Nethergoyf's Escape: candidate projection and payment.
 *
 * The type-count threshold is validated against the selected cards: a
 * selection spanning four card types pays, a two-type selection is
 * rejected even when the candidate list as a whole spans four types.
 */
class ExileTypeCountCostLifecycleTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Swamp;Swamp;Swamp
            humangraveyard=Nethergoyf;Duress;Shock;Grizzly Bears;Portable Hole
            humanlibrary=Swamp;Swamp
            ailibrary=Mountain;Mountain
            """.trimIndent()

        fun MatchFlowHarness.graveyardIds(vararg names: String): List<Int> =
            names.map { name ->
                human.graveyard.iid(
                    human
                        .getZone(ZoneType.Graveyard)
                        .cards
                        .first { it.name == name },
                )
            }

        session("four-type selection pays the escape exile cost", puzzle = puzzle, validating = true) {
            castFromGraveyard("Nethergoyf").shouldBeTrue()
            selectTargets(graveyardIds("Duress", "Shock", "Grizzly Bears", "Portable Hole"))
            bridge.awaitPriority()
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Nethergoyf" } shouldBe 1
                human.getZone(ZoneType.Exile).cards shouldHaveSize 4
            }
        }

        session("two-type selection is rejected even when the candidate list spans four types", puzzle = puzzle, validating = true) {
            castFromGraveyard("Nethergoyf").shouldBeTrue()
            selectTargets(graveyardIds("Duress", "Shock"))
            bridge.awaitPriority()
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Nethergoyf" } shouldBe 0
                human.getZone(ZoneType.Graveyard).cards shouldHaveSize 5
                human.getZone(ZoneType.Exile).cards shouldHaveSize 0
            }
        }
    })
