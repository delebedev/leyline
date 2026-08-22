package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.DistributionReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ModalOption
import wotc.mtgo.gre.external.messaging.Messages.ModalReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class DefaultDecisionsTest :
    FunSpec({
        tags(UnitTag)

        fun distributionPrompt(
            total: Int,
            minPerTarget: Int,
            targets: List<Int>,
        ) = GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.DistributionReq_695e)
            .setDistributionReq(
                DistributionReq
                    .newBuilder()
                    .setMinAmount(total)
                    .setMaxAmount(total)
                    .setMinPerTarget(minPerTarget)
                    .addAllTargetIds(targets)
                    .addAllValidSelectedTargetIds(targets),
            ).build()

        test("one selected target receives the full forced distribution") {
            DefaultDecisions.forcedDistribution(distributionPrompt(total = 3, minPerTarget = 1, targets = listOf(1))) shouldBe
                SimDecision.Distribution(mapOf(1 to 3))
        }

        test("selected targets receive their minimum when it exhausts the distribution") {
            DefaultDecisions.forcedDistribution(distributionPrompt(total = 2, minPerTarget = 1, targets = listOf(300, 361))) shouldBe
                SimDecision.Distribution(mapOf(300 to 1, 361 to 1))
        }

        test("strategic distribution remains unmapped") {
            DefaultDecisions.forcedDistribution(distributionPrompt(total = 3, minPerTarget = 1, targets = listOf(300, 361))) shouldBe
                null
        }

        test("modal default retains the option ctoId") {
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.CastingTimeOptionsReq_695e)
                    .setCastingTimeOptionsReq(
                        CastingTimeOptionsReq
                            .newBuilder()
                            .addCastingTimeOptionReq(
                                CastingTimeOptionReq
                                    .newBuilder()
                                    .setCtoId(3)
                                    .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                                    .setModalReq(
                                        ModalReq
                                            .newBuilder()
                                            .setMinSel(1)
                                            .setMaxSel(1)
                                            .addModalOptions(ModalOption.newBuilder().setGrpId(42_001))
                                            .addModalOptions(ModalOption.newBuilder().setGrpId(42_002)),
                                    ),
                            ),
                    ).build()

            DefaultDecisions.castingTimeOptions(prompt) shouldBe
                SimDecision.ModalChoice(ctoId = 3, selectedGrpIds = listOf(42_001))
        }

        test("a required alternate additional cost picks an offered branch, not the decline ctoId") {
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.CastingTimeOptionsReq_695e)
                    .setCastingTimeOptionsReq(
                        CastingTimeOptionsReq
                            .newBuilder()
                            .addCastingTimeOptionReq(
                                CastingTimeOptionReq
                                    .newBuilder()
                                    .setCtoId(2)
                                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                                    .setIsRequired(true)
                                    .setSelectNReq(
                                        SelectNReq
                                            .newBuilder()
                                            .setMinSel(1)
                                            .setMaxSel(1)
                                            .setIdType(IdType.PromptParameterIndex)
                                            .addIds(1)
                                            .addIds(2),
                                    ),
                            ),
                    ).build()

            DefaultDecisions.castingTimeOptions(prompt) shouldBe
                SimDecision.AlternateCost(ctoId = 2, optionIndex = 1)
        }
    })
