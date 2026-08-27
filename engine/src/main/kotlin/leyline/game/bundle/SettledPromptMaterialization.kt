package leyline.game.bundle

import leyline.bridge.types.ForgeCardId
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/** Shared construction mechanics for one settled prompt projection. */
internal class SettledPromptMaterializationContext(
    val gameState: GameStateMessage,
    val gameStateId: Int,
    val sequence: LogicalSequencePlanner,
    private val projection: ProjectionState,
    private val transition: ProjectionTransition,
    val seatId: Int,
) {
    fun requiredInstanceId(
        cardId: ForgeCardId,
        subject: String,
    ): Int = projection.identities.forgeIdToInstanceId[cardId]?.value ?: error("$subject ${cardId.value} has no projected instance id")

    fun message(
        type: GREMessageType,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(sequence.nextMsgId())
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()

    fun atCurrentGameState(): SettledPromptMaterializationContext =
        SettledPromptMaterializationContext(
            gameState,
            sequence.currentGsId(),
            sequence,
            projection,
            transition,
            seatId,
        )

    fun prepared(
        messages: List<GREToClientMessage>,
        closesPlaybackFrame: Boolean = true,
    ): SettledPromptMaterialization =
        SettledPromptMaterialization(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame,
        )
}

internal data class SettledPromptMaterialization(
    val bundle: BundleBuilder.BundleResult,
    val transition: ProjectionTransition,
    val closesPlaybackFrame: Boolean,
)
