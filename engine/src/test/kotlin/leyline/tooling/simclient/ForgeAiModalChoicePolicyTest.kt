package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.IntegrationTag
import leyline.bridge.types.SeatId
import leyline.copilot.ForgeAiPolicy
import leyline.copilot.SimDecision
import leyline.testkit.MatchFlowHarness
import leyline.tooling.headless.HeadlessResponseMode

// The cast, modal response, and cancellation traverse MatchConnection; the tier rule cannot see through those harness helpers.
@Suppress("TierPlacementCheck")
class ForgeAiModalChoicePolicyTest :
    FunSpec({
        tags(IntegrationTag)

        test("Forge AI modal policy uses the active runtime Forge context") {
            val harness = MatchFlowHarness(responseMode = HeadlessResponseMode.PolicyVisible)
            harness.connectAndKeepPuzzle("puzzles/modal-etb.pzl")
            harness.castSpellUntilCastingTimeOptionsReq("Trufflesnout", advanceAfterCast = {})
            val msg = harness.allMessages.last { it.hasCastingTimeOptionsReq() }
            val policy = ForgeAiPolicy({ harness.bridge }, SeatId(1))
            val modalGrpIds =
                msg
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
                    .modalOptionsList
                    .map { it.grpId }

            val decision = policy.chooseCastingTimeOptions(msg).shouldBeInstanceOf<SimDecision.ModalChoice>()
            decision.selectedGrpIds.size shouldBe 1
            modalGrpIds shouldContain decision.selectedGrpIds.single()
        }

        test("session CancelActionReq retires the correlated modal and sends cleanup") {
            val harness = MatchFlowHarness(responseMode = HeadlessResponseMode.PolicyVisible)
            harness.connectAndKeepPuzzle("puzzles/modal-etb.pzl")
            val before = harness.allMessages.size
            harness.castSpellUntilCastingTimeOptionsReq("Trufflesnout", advanceAfterCast = {})
            val modalReq =
                harness
                    .allMessages
                    .last { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
            val syntheticAbilityIid = modalReq.affectedId
            harness.cancelAction()

            harness
                .bridge
                .cutCoordinator
                .modalChoices
                .current() shouldBe null
            val cleanup =
                harness
                    .allMessages
                    .drop(before)
                    .single { message ->
                        message.hasGameStateMessage() && message.gameStateMessage.diffDeletedInstanceIdsCount > 0
                    }
            cleanup.gameStateMessage.diffDeletedInstanceIdsList shouldBe listOf(syntheticAbilityIid)
        }
    })
