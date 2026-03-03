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
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .addActions(Action.newBuilder().setActionType(ActionType.ActivateMana))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe true
        }

        test("shouldAutoPass=false when Cast present") {
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Cast))
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe false
        }

        test("shouldAutoPass=true with only Pass") {
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe true
        }

        test("shouldAutoPass=false with Play_add3") {
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Play_add3))
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe false
        }

        test("shouldAutoPass=false with Activate_add3") {
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Activate_add3))
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe false
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
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .addActions(Action.newBuilder().setActionType(ActionType.FloatMana))
                .addActions(Action.newBuilder().setActionType(ActionType.ActivateMana))
                .build()
            BundleBuilder.shouldAutoPass(actions) shouldBe true
        }
    })
