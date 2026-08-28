package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

class SettledPromptMaterializationTest :
    FunSpec({
        tags(UnitTag)

        test("message stamps the settled game-state identity and advances sequence") {
            val context = materializationContext()

            val message = context.message(GREMessageType.SelectNreq) {}
            val prepared = context.prepared(listOf(message), awaitedRequest = message)
            context.sequence.nextGameStateLink()
            val nextGameState = context.atCurrentGameState()

            assertSoftly {
                message.type shouldBe GREMessageType.SelectNreq
                message.gameStateId shouldBe 17
                message.msgId shouldBe 42
                message.systemSeatIdsList shouldBe listOf(2)
                context.sequence.currentMsgId() shouldBe 42
                prepared.bundle.actionGameStateId shouldBe 17
                prepared.bundle.messages shouldBe listOf(message)
                prepared.transition.nextState.revision shouldBe 0
                prepared.closesPlaybackFrame shouldBe true
                prepared.correlation shouldBe SettledPromptCorrelation(gameStateId = 17, requestMsgId = 42)
                nextGameState.gameStateId shouldBe 18
            }
        }

        test("requiredInstanceId resolves only identities in the settled projection") {
            val context = materializationContext(ForgeCardId(7) to InstanceId(103))

            context.requiredInstanceId(ForgeCardId(7), "Choice card") shouldBe 103
            shouldThrow<IllegalStateException> {
                context.requiredInstanceId(ForgeCardId(8), "Choice card")
            }.message shouldBe "Choice card 8 has no projected instance id"
        }
    })

private fun materializationContext(identity: Pair<ForgeCardId, InstanceId>? = null): SettledPromptMaterializationContext {
    val initial = ProjectionState.initial()
    val projection =
        identity
            ?.let { (forgeId, instanceId) ->
                initial.editor().apply { identities.bind(forgeId, instanceId) }.freeze()
            } ?: initial
    return SettledPromptMaterializationContext(
        gameState = GameStateMessage.getDefaultInstance(),
        gameStateId = 17,
        sequence = LogicalSequencePlanner(initialGsId = 17, initialMsgId = 41),
        projection = projection,
        transition = ProjectionTransition(projection.revision, projection),
        seatId = 2,
    )
}
