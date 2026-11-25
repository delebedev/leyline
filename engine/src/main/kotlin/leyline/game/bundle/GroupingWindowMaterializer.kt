package leyline.game.bundle

import leyline.bridge.handoff.GroupingWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.GroupSpecification
import wotc.mtgo.gre.external.messaging.Messages.GroupType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Value-only GRE preparation for coordinator-owned Scry, Surveil and two-pile windows. */
internal class GroupingWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: GroupingWindowValue,
    ): SettledPromptMaterialization {
        val candidateIds = window.candidates.map { context.requiredInstanceId(it.forgeCardId, "Grouping card") }
        val hostSourceId = window.source?.hostCardId?.let { context.requiredInstanceId(it, "Grouping card") } ?: 0
        val sourceId =
            window.source
                ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                ?.let { context.requiredInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId), "Grouping card") }
                ?: hostSourceId
        val request = buildRequest(window.context, candidateIds, sourceId)
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.GroupReq_695e) {
                    it.groupReq = request
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(promptId(window.context))
                            .addParameters(cardIdPromptParameter(pileSourceCard(window.context, hostSourceId)))
                            .build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun buildRequest(
        context: GroupingContext,
        candidateIds: List<Int>,
        sourceId: Int,
    ): GroupReq {
        if (context == GroupingContext.None_a0f6) return pilesRequest(candidateIds, sourceId)
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

    /** A pile prompt names the source card; scry and surveil leave the parameter unset. */
    private fun pileSourceCard(
        context: GroupingContext,
        hostSourceId: Int,
    ): Int? = hostSourceId.takeIf { context == GroupingContext.None_a0f6 && it != 0 }

    /** Two piles: no destination, unbounded sizes, and only membership matters. */
    private fun pilesRequest(
        candidateIds: List<Int>,
        sourceId: Int,
    ): GroupReq {
        val pile = GroupSpecification.newBuilder().setUpperBound(Int.MAX_VALUE).build()
        return GroupReq
            .newBuilder()
            .addAllInstanceIds(candidateIds)
            .addGroupSpecs(pile)
            .addGroupSpecs(pile)
            .setGroupType(GroupType.Arbitrary)
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
            GroupingContext.None_a0f6 -> PromptIds.GROUP_PILES
            GroupingContext.LondonMulligan,
            GroupingContext.UNRECOGNIZED,
            -> error("Unsupported coordinator Grouping context $context")
        }
}
