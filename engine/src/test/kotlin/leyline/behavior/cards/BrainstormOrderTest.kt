package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.GameLoopPoller
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.OrderingContext
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class BrainstormOrderTest :
    SessionTest({
        session("Brainstorm emits OrderReq for chosen top-library cards", puzzleFile = "puzzles/brainstorm-order.pzl") {
            val selectReq = castSpellUntilSelectNReq("Brainstorm")
            val selectMsg = allMessages.last { it.hasSelectNReq() }
            val selectedIids = selectReq.idsList.take(2)
            val selectedNames = selectedIids.map(::cardName)

            assertSoftly {
                selectMsg.prompt.promptId shouldBe PromptIds.SELECT_N_LIBRARY_PUTBACK
                selectReq.unfilteredIdsCount shouldBe 0
                selectedIids.map { cardByIid(it)?.zone }.toSet() shouldBe setOf(ZoneType.Hand.name)
                observe().latestPromptMsgId shouldBe selectMsg.msgId
            }

            respondToSelectN(selectedIids)
            val orderMsg = allMessages.last { it.hasOrderReq() }
            val orderReq = orderMsg.orderReq
            val orderedIids = orderReq.idsList.reversed()
            val orderNames = orderReq.idsList.map(::cardName)
            val orderedNames = orderedIids.map(::cardName)
            val orderGsm = allMessages.last { it.hasGameStateMessage() && it.gameStateId == orderMsg.gameStateId }.gameStateMessage

            assertSoftly {
                orderMsg.gameStateId shouldBeGreaterThan selectMsg.gameStateId
                orderMsg.type shouldBe GREMessageType.OrderReq_695e
                orderMsg.prompt.promptId shouldBe PromptIds.ORDER_LIBRARY_TOP
                cardName(
                    orderMsg.prompt.parametersList
                        .single()
                        .numberValue,
                ) shouldBe "Brainstorm"
                orderReq.orderingContext shouldBe OrderingContext.None_a89f
                orderNames shouldBe selectedNames
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
            human.library.cards
                .take(orderedNames.size)
                .map { it.name } shouldBe orderedNames
        }

        session("Brainstorm defaults without a second order prompt", puzzleFile = "puzzles/brainstorm-order.pzl", promptTimeoutMs = 100L) {
            val selectReq = castSpellUntilSelectNReq("Brainstorm")
            val defaultName = cardName(selectReq.idsList.first())
            val checkpoint = checkpoint()
            GameLoopPoller.awaitCondition(timeoutMs = 20_000, pollIntervalMs = 20) {
                drainSink()
                human.library.cards
                    .firstOrNull()
                    ?.name == defaultName
            }
            val later = messagesSince(checkpoint)
            assertSoftly {
                later.filter { it.hasOrderReq() }.shouldBeEmpty()
                human.library.cards
                    .firstOrNull()
                    .shouldNotBeNull()
                    .name shouldBe defaultName
                selectReq.idsList.shouldNotBeEmpty()
            }
        }
    })
