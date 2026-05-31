package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.ModalOption
import wotc.mtgo.gre.external.messaging.Messages.ModalReq
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.Prompt
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

        fun modalCtoPrompt(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.CastingTimeOptionsReq_695e)
                .setCastingTimeOptionsReq(
                    CastingTimeOptionsReq
                        .newBuilder()
                        .addCastingTimeOptionReq(
                            CastingTimeOptionReq
                                .newBuilder()
                                .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                                .setModalReq(
                                    ModalReq
                                        .newBuilder()
                                        .setMinSel(1)
                                        .setMaxSel(1)
                                        .addModalOptions(ModalOption.newBuilder().setGrpId(138314))
                                        .addModalOptions(ModalOption.newBuilder().setGrpId(143736)),
                                ),
                        ),
                ).build()

        fun searchPrompt(ids: List<Int>): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setMsgId(6)
                .setGameStateId(16)
                .setType(GREMessageType.SearchReq_695e)
                .setSearchReq(
                    SearchReq
                        .newBuilder()
                        .setMinFind(0)
                        .setMaxFind(1)
                        .addAllItemsSought(ids),
                ).build()

        fun groupPrompt(
            ids: List<Int>,
            context: GroupingContext = GroupingContext.Scry_a0f6,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setMsgId(7)
                .setGameStateId(17)
                .setType(GREMessageType.GroupReq_695e)
                .setGroupReq(
                    GroupReq
                        .newBuilder()
                        .setContext(context)
                        .addAllInstanceIds(ids),
                ).build()

        fun objectInfo(
            id: Int,
            type: CardType,
            zoneId: Int = ZoneIds.REVEALED_P1,
        ): GameObjectInfo =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(id)
                .setZoneId(zoneId)
                .setControllerSeatId(1)
                .addCardTypes(type)
                .build()

        fun MatchFlowHarness.addBattlefieldLands(count: Int) {
            repeat(count) { idx ->
                val id = 900 + idx
                accumulator.objects[id] = objectInfo(id, CardType.Land_a80b, ZoneIds.BATTLEFIELD)
            }
        }

        test("greedy CTO policy declines optional costs by default") {
            chooseSimClientCastingTimeOptionId(ctoPrompt(), acceptOptionalCosts = false) shouldBe 0
        }

        test("greedy CTO policy can opt into optional costs for focused runs") {
            chooseSimClientCastingTimeOptionId(ctoPrompt(), acceptOptionalCosts = true) shouldBe 7
        }

        test("greedy CTO policy answers required modal choices with grpIds") {
            chooseSimClientModalGrpIds(modalCtoPrompt()) shouldBe listOf(138314)
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

        test("forge-ai search adapter prefers lands before the fourth land") {
            val harness = MatchFlowHarness()
            harness.addBattlefieldLands(3)
            harness.accumulator.objects[501] = objectInfo(501, CardType.Creature)
            harness.accumulator.objects[502] = objectInfo(502, CardType.Land_a80b)

            chooseBoardAwareSearchIds(searchPrompt(listOf(501, 502)), harness) shouldBe listOf(502)
        }

        test("forge-ai search adapter prefers creatures after the fourth land") {
            val harness = MatchFlowHarness()
            harness.addBattlefieldLands(4)
            harness.accumulator.objects[601] = objectInfo(601, CardType.Land_a80b)
            harness.accumulator.objects[602] = objectInfo(602, CardType.Creature)

            chooseBoardAwareSearchIds(searchPrompt(listOf(601, 602)), harness) shouldBe listOf(602)
        }

        test("forge-ai group adapter bottoms extra lands after the fourth land") {
            val harness = MatchFlowHarness()
            harness.addBattlefieldLands(4)
            harness.accumulator.objects[701] = objectInfo(701, CardType.Land_a80b)
            harness.accumulator.objects[702] = objectInfo(702, CardType.Creature)

            chooseBoardAwareGroupAwayIds(groupPrompt(listOf(701, 702)), harness) shouldBe listOf(701)
        }

        test("forge-ai group adapter keeps lands before the fourth land") {
            val harness = MatchFlowHarness()
            harness.addBattlefieldLands(3)
            harness.accumulator.objects[801] = objectInfo(801, CardType.Land_a80b)
            harness.accumulator.objects[802] = objectInfo(802, CardType.Creature)

            chooseBoardAwareGroupAwayIds(groupPrompt(listOf(801, 802), GroupingContext.Surveil), harness) shouldBe emptyList()
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

        test("greedy PayCosts policy selects until minimum weight is met") {
            val harness = MatchFlowHarness()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(4)
                    .setGameStateId(14)
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
                                            .setMinSel(0)
                                            .setMaxSel(3)
                                            .setMinWeight(6)
                                            .addAllIds(listOf(301, 302, 303))
                                            .addAllWeights(listOf(2, 4, 7)),
                                    ),
                            ),
                    ).build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response = GreedyPromptPolicy(harness).respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.EffectCost(listOf(301, 302))
        }

        test("greedy SelectN policy prefers sideboard Lesson candidate when Learn can discard") {
            val harness = MatchFlowHarness()
            harness.accumulator.objects[401] =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(401)
                    .setZoneId(ZoneIds.P1_HAND)
                    .build()
            harness.accumulator.objects[402] =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(402)
                    .setZoneId(ZoneIds.P1_SIDEBOARD)
                    .build()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(5)
                    .setGameStateId(15)
                    .setType(GREMessageType.SelectNreq)
                    .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.LEARN_LESSON_OR_DISCARD))
                    .setSelectNReq(
                        SelectNReq
                            .newBuilder()
                            .setMinSel(0)
                            .setMaxSel(1)
                            .addAllIds(listOf(401, 402)),
                    ).build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response = GreedyPromptPolicy(harness).respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.SelectN(listOf(402))
        }

        test("Forge AI attacker advice can choose no attackers") {
            val harness = MatchFlowHarness()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(3)
                    .setGameStateId(13)
                    .setType(GREMessageType.DeclareAttackersReq_695e)
                    .setDeclareAttackersReq(DeclareAttackersReq.getDefaultInstance())
                    .build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response =
                object : GreedyPromptPolicy(harness) {
                    override fun advisedAttackers(): List<Int>? = emptyList()
                }.respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.DeclareAttackers(emptyList())
        }
    })
