package leyline.mechanics.cost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest

/**
 * Card-type-count exile cost payment (`withTypesGE` shape) through
 * Nethergoyf's Escape: candidate projection and payment.
 *
 * Characterizes the current validation defect on purpose: the type-count
 * check runs against the candidate list rather than the selected cards, so
 * a two-type selection pays as long as the graveyard offers four types.
 * The defect-fix commit flips the second test to a rejection.
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

        fun SessionTest.graveyardIds(vararg names: String): List<Int> =
            names.map { name ->
                human.graveyard.iid(
                    human
                        .getZone(ZoneType.Graveyard)
                        .cards
                        .first { it.name == name },
                )
            }

        test("four-type selection pays the escape exile cost") {
            startPuzzle(puzzle, name = "TypesGE exile accept", validating = true)

            castFromGraveyard("Nethergoyf").shouldBeTrue()
            val pending =
                harness.bridge
                    .seat(SeatId(1))
                    .prompt
                    .getPendingPrompt()
                    .shouldNotBeNull()

            assertSoftly {
                pending.request.semantic shouldBe PromptSemantic.Generic
                pending.request.min shouldBe 1
                pending.request.max shouldBe 4
                pending.request.candidateRefs shouldHaveSize 4
            }

            respondToEffectCost(graveyardIds("Duress", "Shock", "Grizzly Bears", "Portable Hole"))
            harness.bridge.awaitPriority()
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Nethergoyf" } shouldBe 1
                human.getZone(ZoneType.Exile).cards shouldHaveSize 4
            }
        }

        test("two-type selection currently pays because the list, not the selection, is validated") {
            startPuzzle(puzzle, name = "TypesGE exile list-validation", validating = true)

            castFromGraveyard("Nethergoyf").shouldBeTrue()
            harness.bridge
                .seat(SeatId(1))
                .prompt
                .getPendingPrompt()
                .shouldNotBeNull()

            respondToEffectCost(graveyardIds("Duress", "Shock"))
            harness.bridge.awaitPriority()
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Nethergoyf" } shouldBe 1
                human.getZone(ZoneType.Exile).cards shouldHaveSize 2
            }
        }
    })
