package leyline.copilot

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class SnapshotFidelityTest :
    FunSpec({
        tags(UnitTag)

        test("host-only alternate cast offer makes a strategic consult unavailable") {
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ActionsAvailableReq_695e)
                    .setActionsAvailableReq(
                        ActionsAvailableReq
                            .newBuilder()
                            .addActions(
                                Action
                                    .newBuilder()
                                    .setActionType(ActionType.Cast)
                                    .setInstanceId(288)
                                    .setAbilityGrpId(328)
                                    .setAlternativeGrpId(149),
                            ).addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                    ).build()

            val scoped = SnapshotFidelityReport("ungraded", emptyList()).forPrompt(prompt)

            assertSoftly {
                scoped.grade shouldBe "degraded"
                scoped.delivery shouldBe "unavailable"
                scoped.unavailableReasons shouldContain "offered_action_state:missing"
            }
        }

        test("object mismatch only blocks a constrained prompt that references it") {
            val report =
                SnapshotFidelityReport(
                    grade = "ungraded",
                    features =
                        listOf(
                            SnapshotFidelityFeature(
                                feature = "unresolved_cards",
                                status = "missing",
                                count = 1,
                                instanceIds = listOf(288),
                            ),
                        ),
                )

            fun selectN(instanceId: Int): GREToClientMessage =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SelectNreq)
                    .setSelectNReq(
                        SelectNReq
                            .newBuilder()
                            .setMinSel(1)
                            .setMaxSel(1)
                            .addIds(instanceId),
                    ).build()

            report.forPrompt(selectN(288)).delivery shouldBe "unavailable"
            report.forPrompt(selectN(999)).delivery shouldBe "valid"
        }
    })
