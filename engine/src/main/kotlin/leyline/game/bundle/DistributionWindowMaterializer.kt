package leyline.game.bundle

import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.DistributionReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/** Value-only GRE preparation for coordinator-owned divided allocations. */
internal class DistributionWindowMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: DistributionWindowValue,
    ): Prepared {
        val sourceId =
            projection.requireInstanceId(
                if (window.sourceIsSpell) {
                    leyline.bridge.types.ForgeCardId(window.sourceForgeCardId)
                } else {
                    FrameIdResolver.triggerStackAbilityForgeId(window.sourceForgeAbilityId)
                },
            )
        val targetIds =
            window.targets.map { target ->
                when (target) {
                    is DistributionTargetRef.Card -> projection.requireInstanceId(target.id)
                    is DistributionTargetRef.Player -> target.id.value
                }
            }
        require(targetIds.distinct().size == targetIds.size) { "Distribution targets have colliding wire ids" }
        val request =
            DistributionReq
                .newBuilder()
                .setMinAmount(window.amount)
                .setMaxAmount(window.amount)
                .setMinPerTarget(window.minPerTarget)
                .addAllTargetIds(targetIds)
                .addAllValidSelectedTargetIds(targetIds)
                .setSourceId(sourceId)
                .build()
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val promptId =
            when (window.kind) {
                DistributionRouteKind.Damage -> PromptIds.DISTRIBUTE_DAMAGE
                DistributionRouteKind.Counters -> PromptIds.DISTRIBUTE_COUNTERS
            }
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) { it.gameStateMessage = state },
                makeGRE(GREMessageType.DistributionReq_695e, gameStateId, counter.nextMsgId()) {
                    it.distributionReq = request
                    it.prompt =
                        promptWithCardId(promptId, projection.requireInstanceId(leyline.bridge.types.ForgeCardId(window.sourceForgeCardId)))
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, closesPlaybackFrame = true)
    }

    private fun ProjectionState.requireInstanceId(cardId: leyline.bridge.types.ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("Distribution object ${cardId.value} has no projected instance id")

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()
}
