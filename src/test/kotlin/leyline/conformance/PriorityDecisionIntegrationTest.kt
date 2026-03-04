package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.BundleBuilder
import leyline.game.mapper.ShouldStopEvaluator
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq

class PriorityDecisionIntegrationTest :
    FunSpec({

        tags(ConformanceTag)

        test("shouldAutoPass=true when only Pass+ActivateMana") {
            BundleBuilder.shouldAutoPass(actionsReq(ActionType.Pass, ActionType.ActivateMana)) shouldBe true
        }

        test("shouldAutoPass=false when Cast present") {
            BundleBuilder.shouldAutoPass(actionsReq(ActionType.Cast, ActionType.Pass)) shouldBe false
        }

        test("shouldAutoPass=true with only Pass") {
            BundleBuilder.shouldAutoPass(actionsReq(ActionType.Pass)) shouldBe true
        }

        test("shouldAutoPass=false with Play_add3") {
            BundleBuilder.shouldAutoPass(actionsReq(ActionType.Play_add3, ActionType.Pass)) shouldBe false
        }

        test("shouldAutoPass=false with Activate_add3") {
            BundleBuilder.shouldAutoPass(actionsReq(ActionType.Activate_add3, ActionType.Pass)) shouldBe false
        }

        test("shouldAutoPass aligns with ShouldStopEvaluator for all action types") {
            // For every ActionType: shouldStop=true means it should NOT auto-pass
            for (type in ActionType.values()) {
                if (type == ActionType.UNRECOGNIZED) continue
                val actions = ActionsAvailableReq.newBuilder()
                    .addActions(Action.newBuilder().setActionType(type))
                    .build()
                val autoPass = BundleBuilder.shouldAutoPass(actions)
                val shouldStop = ShouldStopEvaluator.shouldStop(type)
                // If shouldStop is true, autoPass must be false (and vice versa)
                autoPass shouldBe !shouldStop
            }
        }

        test("shouldAutoPass=true with FloatMana+Pass mix") {
            BundleBuilder.shouldAutoPass(
                actionsReq(ActionType.Pass, ActionType.FloatMana, ActionType.ActivateMana),
            ) shouldBe true
        }
    })
