package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.OrderingContext
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class BrainstormOrderTest :
    SessionTest({
        test("Brainstorm emits OrderReq for chosen top-library cards") {
            startPuzzleFile("puzzles/brainstorm-order.pzl", validating = true)

            val selectReq = castSpellUntilSelectNReq("Brainstorm")
            val selectMsg = allMessages.last { it.hasSelectNReq() }
            val selectedIids = selectReq.idsList.take(2)
            val selectedNames = selectedIids.map { iid -> cardByIid(iid)?.name ?: error("iid $iid did not resolve") }

            assertSoftly {
                selectMsg.prompt.promptId shouldBe PromptIds.SELECT_N_LIBRARY_PUTBACK
                selectReq.unfilteredIdsCount shouldBe 0
                selectReq.idsList
                    .map { iid -> cardByIid(iid)?.zone?.zoneType }
                    .toSet() shouldBe setOf(ZoneType.Hand)
            }

            respondToSelectN(selectedIids)
            val orderMsg = allMessages.last { it.hasOrderReq() }
            val orderReq = orderMsg.orderReq
            val orderedIids = orderReq.idsList.reversed()
            val orderedNames = orderedIids.map { iid -> cardByIid(iid)?.name ?: error("iid $iid did not resolve") }
            val orderNames = orderReq.idsList.map { iid -> cardByIid(iid)?.name ?: error("iid $iid did not resolve") }
            val orderGsm = allMessages.last { it.hasGameStateMessage() && it.gameStateId == orderMsg.gameStateId }.gameStateMessage

            assertSoftly {
                orderMsg.type shouldBe GREMessageType.OrderReq_695e
                orderMsg.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_TOP
                cardByIid(
                    orderMsg.prompt.parametersList
                        .single()
                        .numberValue,
                )?.name shouldBe "Brainstorm"
                orderReq.orderingContext shouldBe OrderingContext.None_a89f
                orderNames shouldContainExactlyInAnyOrder selectedNames
                orderReq.idsList.forEach { iid ->
                    val obj = orderGsm.gameObjectsList.first { it.instanceId == iid }
                    obj.zoneId shouldBe ZoneIds.P1_LIBRARY
                    obj.visibility shouldBe Visibility.Private
                    obj.viewersList shouldContain 1
                }
                orderGsm.zonesList.first { it.zoneId == ZoneIds.P1_LIBRARY }.visibility shouldBe Visibility.Hidden
                orderGsm.annotationsList.flatMap { it.typeList } shouldContain AnnotationType.ObjectIdChanged
                orderGsm.annotationsList.flatMap { it.typeList } shouldContain AnnotationType.ZoneTransfer_af5a
            }

            respondToOrder(orderedIids)
            passUntilResolved()

            human
                .getZone(ZoneType.Library)
                .cards
                .take(orderedNames.size)
                .map { it.name } shouldBe orderedNames
        }
    })
