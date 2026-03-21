package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.mapper.ShouldStopEvaluator
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Validates [ShouldStopEvaluator] against expected shouldStop values
 * observed in real Arena ActionsAvailableReq messages.
 *
 * Source: Arena proxy recording 2026-02-28, documented in #142
 */
class ShouldStopConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        // ActionType -> expected shouldStop from real Arena server
        val expectedShouldStop = mapOf(
            // shouldStop = true
            ActionType.Cast to true,
            ActionType.CastLeftRoom to true,
            ActionType.CastRightRoom to true,
            ActionType.Activate_add3 to true,
            ActionType.Play_add3 to true,
            // shouldStop = false
            ActionType.ActivateMana to false,
            ActionType.Pass to false,
            ActionType.FloatMana to false,
        )

        for ((actionType, expected) in expectedShouldStop) {
            test("shouldStop(${actionType.name}) = $expected") {
                ShouldStopEvaluator.shouldStop(actionType) shouldBe expected
            }
        }
    })
