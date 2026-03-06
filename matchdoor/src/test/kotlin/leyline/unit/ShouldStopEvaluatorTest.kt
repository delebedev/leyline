package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapper.ShouldStopEvaluator
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ShouldStopEvaluatorTest :
    FunSpec({

        tags(UnitTag)

        test("Cast variants → shouldStop=true") {
            val castTypes = listOf(
                ActionType.Cast,
                ActionType.CastLeft,
                ActionType.CastRight,
                ActionType.CastAdventure,
                ActionType.CastMdfc,
                ActionType.CastPrototype,
                ActionType.CastLeftRoom,
                ActionType.CastRightRoom,
                ActionType.CastOmen,
            )
            for (type in castTypes) {
                ShouldStopEvaluator.shouldStop(type) shouldBe true
            }
        }

        test("Play variants → shouldStop=true") {
            ShouldStopEvaluator.shouldStop(ActionType.Play_add3) shouldBe true
            ShouldStopEvaluator.shouldStop(ActionType.PlayMdfc) shouldBe true
        }

        test("Activate → shouldStop=true") {
            ShouldStopEvaluator.shouldStop(ActionType.Activate_add3) shouldBe true
        }

        test("Pass → shouldStop=false") {
            ShouldStopEvaluator.shouldStop(ActionType.Pass) shouldBe false
        }

        test("FloatMana → shouldStop=false") {
            ShouldStopEvaluator.shouldStop(ActionType.FloatMana) shouldBe false
        }

        test("ActivateMana → shouldStop=false") {
            ShouldStopEvaluator.shouldStop(ActionType.ActivateMana) shouldBe false
        }
    })
