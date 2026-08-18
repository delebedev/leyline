package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.mapping.ShouldStopEvaluator
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Pins [ShouldStopEvaluator] to the shouldStop value the client expects on each
 * ActionType in an ActionsAvailableReq.
 */
class ShouldStopConformanceTest :
    FunSpec({

        tags(BoardTag)

        // ActionType -> the shouldStop the client expects
        val expectedShouldStop =
            mapOf(
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
