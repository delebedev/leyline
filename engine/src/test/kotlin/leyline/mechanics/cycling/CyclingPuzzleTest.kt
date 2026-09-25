package leyline.mechanics.cycling

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.bundle.StateFrameInputCapture
import leyline.game.event.FrameEventLog
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.hand
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private val CYCLE_MISCALCULATION_PUZZLE =
    """
    [metadata]
    Name:Cycle Miscalculation
    Goal:Cycle and draw
    Turns:1
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Miscalculation
    humanbattlefield=Island;Island
    humanlibrary=Lightning Bolt;Island
    ailibrary=Mountain
    """.trimIndent()

/**
 * Integration test for Cycling (hand-zone activated ability with discard-as-cost).
 *
 * Miscalculation — `K:Cycling:2`. Pay {2}, discard from hand → draw a card.
 * Validates that the existing hand-activated-ability rail (which makes Channel
 * work) also surfaces Cycling: Activate_add3 offered for the hand card,
 * Discard<1/CARDNAME> cost component fires the Hand→Graveyard ZoneTransfer
 * with category=Discard, and the resolve effect (Draw) lands.
 */
@Suppress("MissingAssertSoftly") // intentional fail-fast — passUntil depends on activation succeeding first
class CyclingPuzzleTest :
    SessionTest({
        session(
            "Miscalculation cycle from hand draws + discards",
            puzzle = CYCLE_MISCALCULATION_PUZZLE,
        ) {
            // Pre-cycle invariants
            human
                .getZone(ZoneType.Hand)
                .cards
                .any { it.name == "Miscalculation" }
                .shouldBeTrue()
            val handBefore = human.getZone(ZoneType.Hand).size()
            val gyBefore = human.getZone(ZoneType.Graveyard).size()
            val cardIid = human.hand.iid("Miscalculation")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == cardIid }
            offer.abilityGrpId shouldBeGreaterThan 0
            val activationStart = messageSnapshot()

            // Cycle Miscalculation — same path as Channel.
            activateAbilityFromHand("Miscalculation").shouldBeTrue()
            // Wait for the Cycling AB to resolve (Discard is part of the cost,
            // Draw is the resolve effect — we need both to land before asserting).
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Miscalculation" } &&
                    human.getZone(ZoneType.Hand).cards.any { it.name == "Lightning Bolt" }
            }.shouldBeTrue()

            val activation =
                messagesSince(activationStart)
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .single { it.detailInt("actionType") == ActionType.Activate_add3.number }

            assertSoftly {
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Miscalculation" }
                    .shouldBeTrue()
                // Hand size: -1 (cycled) +1 (drew Lightning Bolt) = same
                human.getZone(ZoneType.Hand).size() shouldBe handBefore
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Lightning Bolt" }
                    .shouldBeTrue()
                human.getZone(ZoneType.Graveyard).size() shouldBe gyBefore + 1
                activation.detailInt("abilityGrpId") shouldBe offer.abilityGrpId
            }
        }

        session(
            "Remote Isle cycling retains identity without an action projection",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Remote Isle
                humanbattlefield=Island;Island
                humanlibrary=Lightning Bolt;Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val cardIid = human.hand.iid("Remote Isle")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == cardIid }
            bridge.clearAbilityRegistryCacheForTesting()
            StateFrameInputCapture(bridge, "cycling-identity", 1).captureNeutral(
                game = game(),
                gameStateId = 99,
                revealForSeat = null,
                events = StateFrameInputCapture.Events.Supplied(FrameEventLog(emptyList())),
            )
            val start = messageSnapshot()

            submitAction(offer)
            passUntilResolved()

            val action =
                messagesSince(start)
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .single { it.detailInt("actionType") == ActionType.Activate_add3.number }
            val abilityGrpIds =
                messagesSince(start)
                    .allGameObjects()
                    .filter { it.type == GameObjectType.Ability && it.parentId == cardIid }
                    .map { it.grpId }
                    .distinct()
            assertSoftly {
                offer.abilityGrpId shouldBeGreaterThan 0
                action.detailInt("abilityGrpId") shouldBe offer.abilityGrpId
                abilityGrpIds shouldBe listOf(offer.abilityGrpId)
            }
        }
    })
