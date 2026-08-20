package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ModalOption
import wotc.mtgo.gre.external.messaging.Messages.ModalReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class DefaultDecisionsTest :
    FunSpec({
        tags(UnitTag)

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
