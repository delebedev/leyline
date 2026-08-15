package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.copilot.ExpectedCastVariant
import leyline.copilot.ForgeAiPolicy
import leyline.copilot.SimDecision
import leyline.copilot.allowedStaticColorIds
import leyline.copilot.chooseCastActionByVariant
import leyline.copilot.colorSetFromStaticIds
import leyline.copilot.effectCostSelectionIds
import leyline.copilot.sacrificeCostSelectionIds
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
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
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection
import wotc.mtgo.gre.external.messaging.Messages.Target as ProtoTarget

@Suppress("MissingAssertSoftly")
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

        fun selectTargetsPrompt(
            min: Int = 1,
            max: Int = 1,
            legalAction: SelectAction = SelectAction.Select_a1ad,
            sourceId: Int = 0,
            abilityGrpId: Int = 0,
            targetIds: List<Int> = listOf(2),
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setMsgId(8)
                .setGameStateId(18)
                .setType(GREMessageType.SelectTargetsReq_695e)
                .setSelectTargetsReq(
                    SelectTargetsReq
                        .newBuilder()
                        .setSourceId(sourceId)
                        .setAbilityGrpId(abilityGrpId)
                        .addTargets(
                            TargetSelection
                                .newBuilder()
                                .setMinTargets(min)
                                .setMaxTargets(max)
                                .addAllTargets(
                                    targetIds.map { targetId ->
                                        ProtoTarget
                                            .newBuilder()
                                            .setTargetInstanceId(targetId)
                                            .setLegalAction(legalAction)
                                            .build()
                                    },
                                ),
                        ).build(),
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

        test("forge-ai SelectTargets adapter admits sane bounded target prompts") {
            val policy = ForgeAiPolicy({ MatchFlowHarness().bridge }, SeatId(1))

            policy.canChooseSelectTargets(selectTargetsPrompt()) shouldBe true
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 0, max = 1)) shouldBe true
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 0, max = 2, targetIds = listOf(2, 3))) shouldBe true
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 1, max = 2, targetIds = listOf(2, 3))) shouldBe true
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 2, max = 2, targetIds = listOf(2, 3))) shouldBe true
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 2, max = 2)) shouldBe false
            policy.canChooseSelectTargets(selectTargetsPrompt(min = 2, max = 1, targetIds = listOf(2, 3))) shouldBe false
            policy.canChooseSelectTargets(selectTargetsPrompt(legalAction = SelectAction.Unselect)) shouldBe true
        }

        test("forge-ai cast adapter requires exact alternative action") {
            val base =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(10)
                    .setGrpId(20)
                    .build()
            val overload = base.toBuilder().setAlternativeGrpId(19573).build()
            val cleave = base.toBuilder().setAlternativeGrpId(11111).build()
            val candidates = listOf(cleave, base, overload)

            chooseCastActionByVariant(candidates, ExpectedCastVariant.Base) shouldBe base
            chooseCastActionByVariant(candidates, ExpectedCastVariant.Alternative(19573)) shouldBe overload
            chooseCastActionByVariant(candidates, ExpectedCastVariant.Alternative(99999)) shouldBe null
            chooseCastActionByVariant(candidates, ExpectedCastVariant.UnresolvedAlternative) shouldBe null
        }

        test("forge-ai static color adapter constrains choices to prompt colors") {
            val req =
                SelectNReq
                    .newBuilder()
                    .setStaticList(StaticList.Colors)
                    .setMinSel(1)
                    .setMaxSel(1)
                    .addAllIds(listOf(4, 5))
                    .build()

            allowedStaticColorIds(req, promptStaticOptionIds = listOf(1, 2, 3)) shouldBe listOf(4, 5)
            colorSetFromStaticIds(allowedStaticColorIds(req, emptyList())).hasRed() shouldBe true
            colorSetFromStaticIds(allowedStaticColorIds(req, emptyList())).hasGreen() shouldBe true
            colorSetFromStaticIds(allowedStaticColorIds(req, emptyList())).hasWhite() shouldBe false
        }

        test("forge-ai CTO adapter only consults simple modal choices") {
            val policy = ForgeAiPolicy({ MatchFlowHarness().bridge }, SeatId(1))

            policy.canChooseCastingTimeOptions(modalCtoPrompt()) shouldBe true
            policy.canChooseCastingTimeOptions(ctoPrompt()) shouldBe false
        }

        fun payCostsPrompt(
            minSel: Int = 1,
            maxSel: Int = 1,
            ids: List<Int> = listOf(201, 202),
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
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
                                        .setMinSel(minSel)
                                        .setMaxSel(maxSel)
                                        .addAllIds(ids),
                                ),
                        ),
                ).build()

        test("forge-ai PayCosts adapter fails closed without a pending sacrifice cost prompt") {
            val policy = ForgeAiPolicy({ MatchFlowHarness().bridge }, SeatId(1))

            policy.canChooseSacrificeCostPayment(payCostsPrompt(ids = emptyList())) shouldBe false
            policy.canChooseSacrificeCostPayment(ctoPrompt()) shouldBe false
            // Well-shaped cost selection but no live game or pending prompt —
            // both the consult gate and the decision must fail closed.
            policy.canChooseSacrificeCostPayment(payCostsPrompt()) shouldBe false
            policy.chooseSacrificeCostPayment(payCostsPrompt()) shouldBe null
        }

        test("forge-ai PayCosts adapter validates AI sacrifice ids against the selection") {
            val selection = payCostsPrompt().payCostsReq.effectCostReq.costSelection

            sacrificeCostSelectionIds(listOf(202), selection) shouldBe listOf(202)
            sacrificeCostSelectionIds(listOf(999), selection) shouldBe null
            sacrificeCostSelectionIds(listOf(201, 202), selection) shouldBe null
            sacrificeCostSelectionIds(listOf(202, 202), selection) shouldBe null
            sacrificeCostSelectionIds(emptyList(), selection) shouldBe null
            sacrificeCostSelectionIds(listOf(0), selection) shouldBe null
        }

        test("effect-cost selection accepts one offered Station creature") {
            val selection = payCostsPrompt(ids = listOf(301, 302)).payCostsReq.effectCostReq.costSelection

            effectCostSelectionIds(listOf(302), selection) shouldBe listOf(302)
        }

        test("effect-cost selection refuses Station when Forge finds no payable creature") {
            val selection = payCostsPrompt(ids = listOf(301, 302)).payCostsReq.effectCostReq.costSelection

            effectCostSelectionIds(emptyList(), selection) shouldBe null
            effectCostSelectionIds(listOf(999), selection) shouldBe null
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

        test("greedy SelectN policy accepts Suspect choice prompts") {
            val harness = MatchFlowHarness()
            val msg =
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(6)
                    .setGameStateId(16)
                    .setType(GREMessageType.SelectNreq)
                    .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES))
                    .setSelectNReq(
                        SelectNReq
                            .newBuilder()
                            .setMinSel(0)
                            .setMaxSel(1)
                            .addAllIds(listOf(501, 502)),
                    ).build()
            harness.allMessages += msg

            val prompt = SimPromptLedger(harness).activePrompt()!!
            val response = GreedyPromptPolicy(harness).respondToPrompt(prompt, ActionAttemptLedger { 1 })

            response.decision shouldBe SimDecision.SelectN(listOf(501))
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

        test("simclient findings flag repeated target choices as replay loop suspects") {
            val key = "source=forge-ai|abilityGrpId=59671|from=object:Card:grp:59671:Ancestral Recall|to=playerOrMissing:1"

            val findings =
                detectReplayLoopFindings(
                    targetChoiceCounts = mapOf(key to 25, "source=greedy|abilityGrpId=1|from=x|to=y" to 3),
                    targetChoiceSamples = mapOf(key to "sourceId=42;targetIds=1"),
                )

            findings.size shouldBe 1
            findings.single().kind shouldBe "replay-loop-suspect"
            findings.single().key shouldBe key
            findings.single().count shouldBe 25
            findings.single().sample shouldBe "sourceId=42;targetIds=1"
        }

        test("prompt progress telemetry records submitted target response shape") {
            val harness = MatchFlowHarness()
            harness.accumulator.objects[42] =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(42)
                    .setGrpId(59671)
                    .setZoneId(ZoneIds.STACK)
                    .setControllerSeatId(1)
                    .build()
            val msg = selectTargetsPrompt(sourceId = 42, abilityGrpId = 204314)
            harness.allMessages += msg
            val prompt = SimPromptLedger(harness).activePrompt()!!
            val recorder = PromptProgressRecorder(harness)

            recorder.record(
                prompt = prompt,
                decision = SimDecision.SelectTargets(listOf(2)),
                submitResult = SimSubmitResult.Submitted,
                beforeMessages = 1,
                beforeLast = msg,
                sourceBefore = recorder.sourceSnapshot(prompt),
            )

            val sample = recorder.snapshot().single()
            sample.promptType shouldBe GREMessageType.SelectTargetsReq_695e.name
            sample.decisionKind shouldBe "select-targets"
            sample.submitResult shouldBe SimSubmitResult.Submitted.name
            sample.promptMsgId shouldBe 8
            sample.beforeGameStateId shouldBe 18
            sample.afterGameStateId shouldBe 18
            sample.sourceInstanceId shouldBe 42
            sample.sourceGrpId shouldBe 59671
            sample.abilityGrpId shouldBe 204314
            sample.targetIds shouldBe listOf(2)
            sample.sourceBefore shouldBe "id=42;grp=59671;zone=STACK;ctrl=1;type=None_a4aa"
        }
    })
