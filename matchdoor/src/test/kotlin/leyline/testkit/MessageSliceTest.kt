package leyline.testkit

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

class MessageSliceTest :
    FunSpec({

        tags(UnitTag)

        fun gsm(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(GameStateMessage.getDefaultInstance())
                .build()

        fun ctoReq(ctoId: Int = 0): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.CastingTimeOptionsReq_695e)
                .setCastingTimeOptionsReq(
                    CastingTimeOptionsReq.newBuilder().addCastingTimeOptionReq(
                        CastingTimeOptionReq.newBuilder().setCtoId(ctoId),
                    ),
                ).build()

        fun kickerCtoReq(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.CastingTimeOptionsReq_695e)
                .setCastingTimeOptionsReq(
                    CastingTimeOptionsReq
                        .newBuilder()
                        .addCastingTimeOptionReq(
                            CastingTimeOptionReq
                                .newBuilder()
                                .setCtoId(1)
                                .setCastingTimeOptionType(CastingTimeOptionType.Kicker),
                        ).addCastingTimeOptionReq(
                            CastingTimeOptionReq
                                .newBuilder()
                                .setCtoId(0)
                                .setCastingTimeOptionType(CastingTimeOptionType.Done)
                                .setIsRequired(true),
                        ),
                ).build()

        fun selectTargetsReq(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.SelectTargetsReq_695e)
                .setSelectTargetsReq(SelectTargetsReq.getDefaultInstance())
                .build()

        fun payCostsReq(): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.PayCostsReq_695e)
                .setPayCostsReq(PayCostsReq.getDefaultInstance())
                .build()

        test("expectOneCastingTimeOptionsReq returns the proto when exactly one present") {
            val slice = MessageSlice(listOf(gsm(), ctoReq(ctoId = 7), gsm()))
            slice
                .expectOneCastingTimeOptionsReq()
                .castingTimeOptionReqList
                .single()
                .ctoId shouldBe 7
        }

        test("expectOneCastingTimeOptionsReq fails with named prompt + observed types when missing") {
            val slice = MessageSlice(listOf(gsm(), selectTargetsReq()))
            val err = shouldThrow<AssertionError> { slice.expectOneCastingTimeOptionsReq() }
            assertSoftly {
                err.message!! shouldContain "CastingTimeOptionsReq"
                err.message!! shouldContain "found 0"
                err.message!! shouldContain "SelectTargetsReq_695e"
            }
        }

        test("expectOneCastingTimeOptionsReq fails when multiple present") {
            val slice = MessageSlice(listOf(ctoReq(), ctoReq()))
            val err = shouldThrow<AssertionError> { slice.expectOneCastingTimeOptionsReq() }
            err.message!! shouldContain "found 2"
        }

        test("expectNoSelectTargetsReq passes when absent") {
            val slice = MessageSlice(listOf(gsm(), ctoReq()))
            shouldNotThrowAny { slice.expectNoSelectTargetsReq() }
        }

        test("expectNoSelectTargetsReq fails with named prompt + observed types when present") {
            val slice = MessageSlice(listOf(ctoReq(), selectTargetsReq()))
            val err = shouldThrow<AssertionError> { slice.expectNoSelectTargetsReq() }
            assertSoftly {
                err.message!! shouldContain "SelectTargetsReq"
                err.message!! shouldContain "found 1"
                err.message!! shouldContain "CastingTimeOptionsReq_695e"
            }
        }

        test("expectOnePayCostsReq + expectNoPayCostsReq cover PayCostsReq family") {
            val present = MessageSlice(listOf(payCostsReq()))
            present.expectOnePayCostsReq()
            shouldThrow<AssertionError> { present.expectNoPayCostsReq() }

            val absent = MessageSlice(listOf(gsm()))
            absent.expectNoPayCostsReq()
            shouldThrow<AssertionError> { absent.expectOnePayCostsReq() }
        }

        test("messages stays accessible as a raw escape hatch") {
            val raw = listOf(gsm(), ctoReq(), selectTargetsReq())
            MessageSlice(raw).messages shouldBe raw
        }

        test("observed-types diagnostic excludes GameStateMessage noise") {
            val slice = MessageSlice(listOf(gsm(), gsm(), selectTargetsReq()))
            val err = shouldThrow<AssertionError> { slice.expectOneCastingTimeOptionsReq() }
            err.message!! shouldContain "SelectTargetsReq_695e"
            // GSM dominates every slice; surfacing it in diagnostics is pure noise.
            err.message!! shouldNotContain "GameStateMessage_695e"
        }

        // --- expectCastingTimeOptionsReq { } block form ---

        test("expectCastingTimeOptionsReq block — passes when option + done match") {
            val slice = MessageSlice(listOf(gsm(), kickerCtoReq()))
            shouldNotThrowAny {
                slice.expectCastingTimeOptionsReq {
                    option(CastingTimeOptionType.Kicker, ctoId = 1)
                    done(ctoId = 0, required = true)
                }
            }
        }

        test("expectCastingTimeOptionsReq block — fails when option type missing, lists actuals") {
            val slice = MessageSlice(listOf(kickerCtoReq()))
            val err =
                shouldThrow<AssertionError> {
                    slice.expectCastingTimeOptionsReq {
                        option(CastingTimeOptionType.Multikicker, ctoId = 1)
                    }
                }
            assertSoftly {
                err.message!! shouldContain "Multikicker"
                err.message!! shouldContain "Kicker/ctoId=1"
                err.message!! shouldContain "Done/ctoId=0"
            }
        }

        test("expectCastingTimeOptionsReq block — fails when ctoId mismatches") {
            val slice = MessageSlice(listOf(kickerCtoReq()))
            val err =
                shouldThrow<AssertionError> {
                    slice.expectCastingTimeOptionsReq {
                        option(CastingTimeOptionType.Kicker, ctoId = 99)
                    }
                }
            err.message!! shouldContain "ctoId=99"
            err.message!! shouldContain "Kicker/ctoId=1"
        }

        test("expectCastingTimeOptionsReq block — done(required=...) catches isRequired drift") {
            val slice = MessageSlice(listOf(kickerCtoReq()))
            val err =
                shouldThrow<AssertionError> {
                    slice.expectCastingTimeOptionsReq {
                        done(ctoId = 0, required = false)
                    }
                }
            err.message!! shouldContain "isRequired=false"
            err.message!! shouldContain "got isRequired=true"
        }
    })
