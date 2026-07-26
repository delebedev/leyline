package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.PROMPT_GRE_TYPES
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

class OwnerProtocolStateTest :
    FunSpec({
        tags(UnitTag)

        test("prompt delivery advances both correlation horizons") {
            withOwner { owner ->
                owner.observeOutbound(
                    listOf(
                        prompt(GREMessageType.ActionsAvailableReq_695e, 5, 11),
                        prompt(GREMessageType.SelectTargetsReq_695e, 7, 13),
                    ),
                )

                assertSoftly {
                    owner.lastPromptGsId() shouldBe 7
                    owner.lastPromptMsgId() shouldBe 13
                }
            }
        }

        test("non-prompt delivery and older ids cannot regress the horizon") {
            withOwner { owner ->
                owner.observeOutbound(
                    listOf(
                        prompt(GREMessageType.DeclareAttackersReq_695e, 9, 15),
                        prompt(GREMessageType.GameStateMessage_695e, 10, 16),
                        prompt(GREMessageType.ActionsAvailableReq_695e, 5, 11),
                    ),
                )

                assertSoftly {
                    owner.lastPromptGsId() shouldBe 9
                    owner.lastPromptMsgId() shouldBe 15
                }
            }
        }

        test("every prompt GRE type advances owner state") {
            withOwner { owner ->
                for ((index, type) in PROMPT_GRE_TYPES.withIndex()) {
                    val gsId = 42 + index
                    val msgId = 84 + index
                    owner.observeOutbound(listOf(prompt(type, gsId, msgId)))
                    owner.lastPromptGsId() shouldBe gsId
                    owner.lastPromptMsgId() shouldBe msgId
                }
            }
        }
    })

private fun withOwner(assertions: (MatchOwner) -> Unit) {
    val owner = MatchOwner("protocol-state")
    try {
        owner.reduce { assertions(owner) }
    } finally {
        owner.close()
        owner.awaitTermination()
    }
}

private fun prompt(
    type: GREMessageType,
    gsId: Int,
    msgId: Int,
): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setType(type)
        .setGameStateId(gsId)
        .setMsgId(msgId)
        .build()
