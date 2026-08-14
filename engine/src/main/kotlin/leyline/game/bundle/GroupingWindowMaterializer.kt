package leyline.game.bundle

import leyline.bridge.handoff.GroupingWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.GroupSpecification
import wotc.mtgo.gre.external.messaging.Messages.GroupType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Value-only GRE preparation for coordinator-owned Scry and Surveil windows. */
internal class GroupingWindowMaterializer(
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
        window: GroupingWindowValue,
    ): Prepared {
        val candidateIds = window.candidates.map { projection.requireInstanceId(it.forgeCardId) }
        val hostSourceId = window.source?.hostCardId?.let { projection.requireInstanceId(it) } ?: 0
        val sourceId =
            window.source
                ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                ?.let { projection.requireInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId)) }
                ?: hostSourceId
        val request = buildRequest(window.context, candidateIds, sourceId)
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.GroupReq_695e, gameStateId, counter.nextMsgId()) {
                    it.groupReq = request
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(promptId(window.context))
                            .addParameters(cardIdPromptParameter())
                            .build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, true)
    }

    private fun buildRequest(
        context: GroupingContext,
        candidateIds: List<Int>,
        sourceId: Int,
    ): GroupReq {
        val awayZone = if (context == GroupingContext.Surveil) ZoneType.Graveyard else ZoneType.Library
        val awaySubZone = if (context == GroupingContext.Surveil) SubZoneType.None_a455 else SubZoneType.Bottom
        return GroupReq
            .newBuilder()
            .addAllInstanceIds(candidateIds)
            .addGroupSpecs(group(candidateIds.size, ZoneType.Library, SubZoneType.Top))
            .addGroupSpecs(group(candidateIds.size, awayZone, awaySubZone))
            .setGroupType(GroupType.Ordered)
            .setContext(context)
            .setSourceId(sourceId)
            .build()
    }

    private fun group(
        count: Int,
        zone: ZoneType,
        subZone: SubZoneType,
    ): GroupSpecification =
        GroupSpecification
            .newBuilder()
            .setLowerBound(0)
            .setUpperBound(count)
            .setZoneType(zone)
            .setSubZoneType(subZone)
            .build()

    private fun promptId(context: GroupingContext): Int =
        when (context) {
            GroupingContext.Scry_a0f6 -> PromptIds.GROUP_SCRY
            GroupingContext.Surveil -> PromptIds.GROUP_SURVEIL
            GroupingContext.None_a0f6,
            GroupingContext.LondonMulligan,
            GroupingContext.UNRECOGNIZED,
            -> error("Unsupported coordinator Grouping context $context")
        }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("Grouping card ${cardId.value} has no projected instance id")

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
