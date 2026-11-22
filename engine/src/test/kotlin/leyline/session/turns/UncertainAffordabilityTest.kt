package leyline.session.turns

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.ActionAvailability
import leyline.bridge.Affordability
import leyline.bridge.ManaAffordabilityBound
import leyline.bridge.PriorityActionCandidates
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.beInZoneOf
import leyline.testkit.beMissingFrom
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class UncertainAffordabilityTest :
    SessionTest({
        session(
            "a paid any-color filter keeps a white spell offered after automatic passing",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Dispatch;Ornithopter
                humanbattlefield=Giant's Boulder;Swamp
                humanlibrary=Plains;Plains;Plains
                aibattlefield=Grizzly Bears
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            check(resolveSpell("Ornithopter"))
            assertSoftly {
                game().phaseHandler.phase shouldBe PhaseType.MAIN1
                game().phaseHandler.playerTurn shouldBe human
                offersCast("Dispatch") shouldBe true
            }
            check(castSpellByName("Dispatch"))
            selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
            passUntilResolved()
            assertSoftly {
                human.graveyard.card("Dispatch")
                ai.battlefield.card("Grizzly Bears").isTapped shouldBe true
                human.battlefield.card("Giant's Boulder").isTapped shouldBe true
            }
        }

        session(
            "floating mana keeps a Convoke spell offered and castable",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Omni-Changeling;Air Elemental;Tormod's Crypt
                humanbattlefield=Island;Snow-Covered Island;Swamp;Mountain;Forest;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            for (land in listOf("Island", "Snow-Covered Island", "Swamp", "Mountain", "Forest")) {
                activateMana(land) shouldBe true
            }
            human.manaPool.totalMana() shouldBe 5
            offersCast("Omni-Changeling") shouldBe true
            check(castSpellByName("Omni-Changeling"))
            passPayment()
            passUntilResolved()
            // Omni-Changeling enters as a copy of the untapped Grizzly Bears.
            assertSoftly {
                game().stack.isEmpty shouldBe true
                "Omni-Changeling" should beMissingFrom(ZoneType.Hand, human)
                "Grizzly Bears" should beInZoneOf(ZoneType.Battlefield, human, count = 2)
                human.manaPool.totalMana() shouldBe 0
            }
        }

        session(
            "an unresolved X keeps a Convoke spell offered and castable",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Chord of Calling
                humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest
                humanlibrary=Rumbling Baloth;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            offersCast("Chord of Calling") shouldBe true
            holdNextNumericInput()
            check(castSpellByName("Chord of Calling"))
            respondToNumericInput(4)
            val search = allMessages.last { it.hasSearchReq() }.searchReq
            respondToSearch(listOf(search.itemsSoughtList.single()))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Rumbling Baloth")
                human.graveyard.card("Chord of Calling")
            }
        }

        session(
            "the bound counts a paid filter's gross output toward colour needs",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Swans of Bryn Argoll
                humanbattlefield=Azorius Signet;Wastes;Wastes;Wastes
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val swans = human.hand.card("Swans of Bryn Argoll")
            ManaAffordabilityBound.allows(swans.firstSpellAbility, human) shouldBe true
        }

        session(
            "an unsure offer whose payment fails returns to the same window unchanged",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Savannah Lions
                humanbattlefield=Exotic Orchard
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val lions = human.hand.card("Savannah Lions")
            assertSoftly {
                ActionAvailability.affordability(lions.firstSpellAbility, human) shouldBe Affordability.Unsure
                PriorityActionCandidates.hasLegalNonManaAction(game(), human) shouldBe true
                offersCast("Savannah Lions") shouldBe true
            }
            val turn = game().phaseHandler.turn
            val unsureOffers = bridge.affordabilityDiagnostics.snapshot().offeredWhileUnsureByCard
            check(castSpellByName("Savannah Lions"))
            assertSoftly {
                game().phaseHandler.turn shouldBe turn
                game().phaseHandler.phase shouldBe PhaseType.MAIN1
                game().stack.isEmpty shouldBe true
                human.hand.card("Savannah Lions")
                human.battlefield.card("Exotic Orchard").isTapped shouldBe false
                human.manaPool.totalMana() shouldBe 0
                offersCast("Savannah Lions") shouldBe true
                bridge.affordabilityDiagnostics.snapshot().offeredThenPaymentFailedByCard shouldBe
                    mapOf("Savannah Lions (unsure)" to 1)
                unsureOffers.keys shouldBe setOf("Savannah Lions")
                bridge.affordabilityDiagnostics
                    .snapshot()
                    .offeredWhileUnsureByCard
                    .getValue("Savannah Lions") shouldBeGreaterThan
                    unsureOffers.getValue("Savannah Lions")
            }
        }
    })

private fun MatchFlowHarness.offersCast(cardName: String): Boolean {
    val iid = human.hand.iid(cardName)
    return allMessages
        .last { it.hasActionsAvailableReq() }
        .actionsAvailableReq
        .actionsList
        .any { it.actionType == ActionType.Cast && it.instanceId == iid }
}

/** Finish a Convoke payment without tapping creatures. */
private fun MatchFlowHarness.passPayment() {
    send(
        submitWithGsId(
            performAction { actionType = ActionType.Pass }
                .toBuilder()
                .setGameStateId(latestPromptGsId())
                .build(),
        ),
    )
    drainSink()
}
