package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.SearchReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class SimClientDriverPolicyTest :
    FunSpec({
        tags(UnitTag)

        fun ctoPrompt(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.CastingTimeOptionsReq_695e)
                .setCastingTimeOptionsReq(
                    CastingTimeOptionsReq
                        .newBuilder()
                        .addCastingTimeOptionReq(CastingTimeOptionReq.newBuilder().setCtoId(7))
                        .addCastingTimeOptionReq(CastingTimeOptionReq.newBuilder().setCtoId(0)),
                ).build()

        test("greedy CTO policy declines optional costs by default") {
            chooseSimClientCastingTimeOptionId(ctoPrompt(), acceptOptionalCosts = false) shouldBe 0
        }

        test("greedy CTO policy can opt into optional costs for focused runs") {
            chooseSimClientCastingTimeOptionId(ctoPrompt(), acceptOptionalCosts = true) shouldBe 7
        }

        test("greedy search policy chooses up to maxFind from sought items") {
            val harness = MatchFlowHarness()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(1)
                    .setGameStateId(11)
                    .setType(GREMessageType.SearchReq_695e)
                    .setSearchReq(
                        SearchReq
                            .newBuilder()
                            .setMaxFind(2)
                            .addAllItemsSought(listOf(101, 102, 103)),
                    ).build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response = GreedyPromptPolicy(harness).respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.Search(listOf(101, 102))
        }

        test("greedy PayCosts policy selects minimum required cost ids") {
            val harness = MatchFlowHarness()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(2)
                    .setGameStateId(12)
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
                                            .setMinSel(2)
                                            .setMaxSel(3)
                                            .addAllIds(listOf(201, 202, 203)),
                                    ),
                            ),
                    ).build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response = GreedyPromptPolicy(harness).respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.EffectCost(listOf(201, 202))
        }
    })
