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
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Aggregate-total exile cost payment (total-mana-symbols GE shape):
 * candidate projection, threshold rejection, and successful payment.
 *
 * Baron Helmut Zemo's Boast ability costs "exile any number of black cards
 * from your graveyard with fifteen or more black mana symbols among their
 * mana costs" — an exile-only cost, so once Boast unlocks the ability the
 * selection prompt is the only cost interaction.
 *
 * These tests pin the threshold comparison and the plain min/max Generic
 * projection; the aggregate hint/goal passed to the controller hook is a
 * desktop display concern and is not observable on this path.
 */
class ExileAggregateCostLifecycleTest :
    SessionTest({
        val duressLine = (1..16).joinToString(";") { "Duress" }
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            removesummoningsickness=true

            humanbattlefield=Baron Helmut Zemo
            humangraveyard=$duressLine
            humanlibrary=Swamp;Swamp
            ailibrary=Mountain;Mountain
            """.trimIndent()

        test("mana-symbol threshold rejection leaves the graveyard untouched") {
            startPuzzle(puzzle, name = "Aggregate exile reject", turns = 3, validating = true)

            val zemoIid = human.battlefield.iid("Baron Helmut Zemo")
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(zemoIid))
            passUntil(maxPasses = 12) { boastOfferAvailable(zemoIid) }

            activateAbility("Baron Helmut Zemo").shouldBeTrue()
            val pending =
                harness.bridge
                    .seat(SeatId(1))
                    .prompt
                    .getPendingPrompt()
                    .shouldNotBeNull()

            assertSoftly {
                pending.request.semantic shouldBe PromptSemantic.Generic
                pending.request.min shouldBe 1
                pending.request.max shouldBe 16
                pending.request.candidateRefs shouldHaveSize 16
                pending.request.costSelectionWeights shouldBe emptyList<Int>()
            }

            // Fourteen Duress carry 14 black pips < 15: payment must fail.
            val fourteen =
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .take(14)
                    .map { human.graveyard.iid(it) }
            respondToEffectCost(fourteen)
            harness.bridge.awaitPriority()

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards shouldHaveSize 16
                human.getZone(ZoneType.Exile).cards shouldHaveSize 0
            }
        }

        test("mana-symbol threshold met exiles the selection and pays the cost") {
            startPuzzle(puzzle, name = "Aggregate exile accept", turns = 3, validating = true)

            val zemoIid = human.battlefield.iid("Baron Helmut Zemo")
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(zemoIid))
            passUntil(maxPasses = 12) { boastOfferAvailable(zemoIid) }

            activateAbility("Baron Helmut Zemo").shouldBeTrue()
            harness.bridge
                .seat(SeatId(1))
                .prompt
                .getPendingPrompt()
                .shouldNotBeNull()

            // Fifteen Duress carry 15 black pips >= 15: payment succeeds.
            val fifteen =
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .take(15)
                    .map { human.graveyard.iid(it) }
            respondToEffectCost(fifteen)
            harness.bridge.awaitPriority()

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards shouldHaveSize 1
                human.getZone(ZoneType.Exile).cards shouldHaveSize 15
            }
        }
    })

private fun SessionTest.boastOfferAvailable(iid: Int): Boolean {
    val actions =
        allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList ?: return false
    return actions.any { it.actionType == ActionType.Activate_add3 && it.instanceId == iid }
}
