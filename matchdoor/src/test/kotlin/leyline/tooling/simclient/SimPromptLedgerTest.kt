package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class SimPromptLedgerTest :
    FunSpec({
        tags(UnitTag)

        fun aar(
            msgId: Int,
            instanceId: Int,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setMsgId(msgId)
                .setGameStateId(msgId + 10)
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setActionsAvailableReq(
                    ActionsAvailableReq
                        .newBuilder()
                        .addActions(
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Cast)
                                .setInstanceId(instanceId)
                                .setGrpId(1234),
                        ),
                ).build()

        fun declareAttackers(msgId: Int): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setMsgId(msgId)
                .setGameStateId(msgId + 10)
                .setType(GREMessageType.DeclareAttackersReq_695e)
                .setDeclareAttackersReq(DeclareAttackersReq.getDefaultInstance())
                .build()

        test("active prompt is newest unhandled prompt with prompt-bound AAR payload") {
            val harness = MatchFlowHarness()
            harness.allMessages += aar(msgId = 1, instanceId = 100)
            harness.allMessages += aar(msgId = 2, instanceId = 200)

            val active = SimPromptLedger(harness).activePrompt()!!

            active.msgId shouldBe 2
            active.gsId shouldBe 12
            val payload = active.payload as PromptPayload.ActionsAvailable
            payload.req.actionsList.map { it.instanceId } shouldContainExactly listOf(200)
        }

        test("retired prompt is skipped and counted by reason") {
            val harness = MatchFlowHarness()
            harness.allMessages += aar(msgId = 1, instanceId = 100)
            val ledger = SimPromptLedger(harness)

            ledger.retire(ledger.activePrompt()!!, "no-pending")

            ledger.activePrompt() shouldBe null
            ledger.stats().retiredByReason shouldBe mapOf("no-pending" to 1)
        }

        test("newer action-bridge prompt supersedes older action-bridge prompts") {
            val harness = MatchFlowHarness()
            harness.allMessages += aar(msgId = 1, instanceId = 100)
            harness.allMessages += aar(msgId = 2, instanceId = 200)
            val ledger = SimPromptLedger(harness)

            ledger.activePrompt()!!.msgId shouldBe 2

            ledger.retire(ledger.activePrompt()!!, "no-pending")
            ledger.activePrompt() shouldBe null
            ledger.stats().retiredByReason shouldBe mapOf("superseded" to 1, "no-pending" to 1)
        }

        test("mark all handled only marks prompts through answered message") {
            val harness = MatchFlowHarness()
            harness.allMessages += declareAttackers(msgId = 1)
            harness.allMessages += declareAttackers(msgId = 2)
            harness.allMessages += declareAttackers(msgId = 3)
            val ledger = SimPromptLedger(harness)

            ledger.markAllHandled(GREMessageType.DeclareAttackersReq_695e, throughMsgId = 2)

            ledger.activePrompt()!!.msgId shouldBe 3
        }

        test("PayCostsReq is active prompt and fingerprints cost selection") {
            val harness = MatchFlowHarness()
            harness.allMessages +=
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(3)
                    .setGameStateId(13)
                    .setType(GREMessageType.PayCostsReq_695e)
                    .setPayCostsReq(
                        PayCostsReq
                            .newBuilder()
                            .setEffectCostReq(
                                EffectCostReq
                                    .newBuilder()
                                    .setEffectCostType(EffectCostType.Select_a59c)
                                    .setCostSelection(
                                        SelectNReq
                                            .newBuilder()
                                            .setMinSel(1)
                                            .setMaxSel(2)
                                            .addAllIds(listOf(301, 302)),
                                    ),
                            ),
                    ).build()

            val prompt = SimPromptLedger(harness).activePrompt()!!

            prompt.type shouldBe GREMessageType.PayCostsReq_695e
            prompt.fingerprint shouldBe "PayCosts:1:2:301,302"
        }
    })
