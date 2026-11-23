package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import leyline.testkit.selectNResp
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

private val THRIVING_ISLE_PUZZLE =
    """
    [metadata]
    Name:Thriving Isle Color Choice
    Goal:Survive
    Turns:4
    Difficulty:Easy
    Description:Thriving Isle enters and asks for a color other than blue.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    humanbattlefield=Island
    humanhand=Thriving Isle
    humanlibrary=Island;Island;Island;Island
    ailibrary=Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

/**
 * A settled prompt answered with an option it does not offer stays open: the host
 * rejects the answer with `InvalidOptionSelection` and sends the same request again
 * under a fresh msgId, which the next answer must echo.
 */
class SettledPromptRetryTest :
    SessionTest({
        session("each off-list color answer is rejected and the color choice is asked again", puzzle = THRIVING_ISLE_PUZZLE) {
            playLand("Thriving Isle")
            val offered = allMessages.last { it.hasSelectNReq() }
            val blue = StaticChoiceIds.colorIdForName("Blue")!!
            val black = StaticChoiceIds.colorIdForName("Black")!!
            offered.selectNReq.idsList shouldNotContain blue

            var previous = offered
            repeat(2) {
                val beforeRejection = messageSnapshot()
                respondToSelectN(listOf(blue))
                val afterRejection = messagesSince(beforeRejection)
                val rejection = afterRejection.single { it.type == GREMessageType.IllegalRequest }
                val retry = afterRejection.single { it.hasSelectNReq() }
                assertSoftly {
                    rejection.illegalRequestMessage.reason shouldBe FailureReason.InvalidOptionSelection
                    rejection.illegalRequestMessage.invalidMessage.respId shouldBe previous.msgId
                    afterRejection.indexOf(retry) shouldBeGreaterThan afterRejection.indexOf(rejection)
                    retry.msgId shouldBeGreaterThan rejection.msgId
                    retry.gameStateId shouldBe offered.gameStateId
                    retry.selectNReq shouldBe offered.selectNReq
                    retry.prompt shouldBe offered.prompt
                }
                previous = retry
            }

            respondToSelectN(listOf(black))
            passUntilTurn(3, maxPasses = 60)
            assertSoftly {
                human.battlefield
                    .card("Thriving Isle")
                    .chosenColors
                    .toList() shouldBe listOf("black")
                allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 2
            }
        }

        session("an answer to the superseded request is stale after the color choice is asked again", puzzle = THRIVING_ISLE_PUZZLE) {
            playLand("Thriving Isle")
            val offered = allMessages.last { it.hasSelectNReq() }
            respondToSelectN(listOf(StaticChoiceIds.colorIdForName("Blue")!!))
            val retry = allMessages.last { it.hasSelectNReq() }

            val beforeStale = messageSnapshot()
            send(
                selectNResp(listOf(StaticChoiceIds.colorIdForName("Black")!!))
                    .toBuilder()
                    .setSystemSeatId(1)
                    .setRespId(offered.msgId)
                    .setGameStateId(offered.gameStateId)
                    .build(),
            )
            drainSink()
            val afterStale = messagesSince(beforeStale)
            assertSoftly {
                afterStale.single { it.type == GREMessageType.IllegalRequest }.illegalRequestMessage.reason shouldBe
                    FailureReason.ReqRespMismatch
                afterStale.none(GREToClientMessage::hasSelectNReq) shouldBe true
                latestPromptMsgId() shouldBe retry.msgId
            }

            respondToSelectN(listOf(StaticChoiceIds.colorIdForName("Black")!!))
            passUntilTurn(3, maxPasses = 60)
            human.battlefield
                .card("Thriving Isle")
                .chosenColors
                .toList() shouldBe listOf("black")
        }
    })
