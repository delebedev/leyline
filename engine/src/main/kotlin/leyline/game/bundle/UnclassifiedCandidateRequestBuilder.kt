package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.HighlightType
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection

/** Legacy wire materialization for candidate-backed Generic prompts. */
internal object UnclassifiedCandidateRequestBuilder {
    fun initial(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        chooserSeatId: Int,
    ): SelectTargetsReq = build(prompt, bridge, chooserSeatId, emptySet())

    fun rePrompt(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        chooserSeatId: Int,
        selectedInstanceIds: Set<Int>,
    ): SelectTargetsReq = build(prompt, bridge, chooserSeatId, selectedInstanceIds)

    private fun build(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        chooserSeatId: Int,
        selectedInstanceIds: Set<Int>,
    ): SelectTargetsReq {
        val request = prompt.request
        val sourceId = request.sourceEntityId?.let(::ForgeCardId)
        val sourceInstanceId = sourceId?.let(bridge::getOrAllocInstanceId)?.value ?: 0
        val sourceGrpId = sourceId?.let(bridge::findCard)?.let { bridge.resolveGrpId(it, sourceInstanceId) } ?: 0
        val selection =
            TargetSelection
                .newBuilder()
                .setTargetIdx(request.targetIndex)
                .setTargetingPlayer(chooserSeatId)
                .setMinTargets(request.min)
                .setMaxTargets(request.max)
                .setSelectedTargets(selectedInstanceIds.size)
        if (sourceInstanceId != 0) {
            selection.prompt = promptWithCardId(request.targetPromptId ?: PromptIds.SELECT_TARGETS, sourceInstanceId)
        }
        val targetingAbilityGrpId = prompt.abilityIdentity?.abilityGrpId ?: firstAbilityGrpId(sourceGrpId, bridge)
        if (targetingAbilityGrpId != 0) selection.targetingAbilityGrpId = targetingAbilityGrpId
        val sourceZoneId = targetSourceZoneId(request.candidateRefs, bridge, chooserSeatId)
        if (sourceZoneId != 0) selection.targetSourceZoneId = sourceZoneId

        val opponentSeatId = if (chooserSeatId == 1) 2 else 1
        val slotsRemain = selectedInstanceIds.size < request.max
        request.candidateRefs.forEach { ref ->
            val (instanceId, highlight) = resolve(ref, bridge, opponentSeatId) ?: return@forEach
            when {
                instanceId in selectedInstanceIds ->
                    selection.addTargets(Target.newBuilder().setTargetInstanceId(instanceId).setLegalAction(SelectAction.Unselect))
                slotsRemain ->
                    selection.addTargets(
                        Target
                            .newBuilder()
                            .setTargetInstanceId(instanceId)
                            .setLegalAction(SelectAction.Select_a1ad)
                            .setHighlight(highlight),
                    )
            }
        }
        return SelectTargetsReq
            .newBuilder()
            .addTargets(selection)
            .apply {
                if (sourceInstanceId != 0) this.sourceId = sourceInstanceId
                if (sourceGrpId != 0) abilityGrpId = sourceGrpId
            }.build()
    }

    private fun firstAbilityGrpId(
        sourceGrpId: Int,
        bridge: GameBridge,
    ): Int {
        val abilities =
            sourceGrpId
                .takeIf { it != 0 }
                ?.let(bridge.cardRepository::findByGrpId)
                ?.abilityIds
                .orEmpty()
        return abilities
            .firstOrNull { (abilityGrpId, _) -> bridge.cardRepository.findAbilityInfo(abilityGrpId)?.category == 4 }
            ?.first
            ?: abilities.firstOrNull()?.first
            ?: 0
    }

    private fun resolve(
        ref: PromptCandidateRefDto,
        bridge: GameBridge,
        opponentSeatId: Int,
    ): Pair<Int, HighlightType>? {
        if (ref.isPlayer()) {
            val seatId = listOf(1, 2).firstOrNull { bridge.getPlayer(SeatId(it))?.id == ref.entityId } ?: return null
            return seatId to if (seatId == opponentSeatId) HighlightType.Hot else HighlightType.Cold
        }
        return bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value to HighlightType.Tepid
    }

    private fun targetSourceZoneId(
        refs: List<PromptCandidateRefDto>,
        bridge: GameBridge,
        chooserSeatId: Int,
    ): Int {
        val ref = refs.firstOrNull { it.isCard() && it.zone != null } ?: return 0
        val ownerSeat =
            bridge.findCard(ForgeCardId(ref.entityId))?.owner?.let { owner ->
                if (owner == bridge.getPlayer(SeatId(1))) SeatId(1) else SeatId(2)
            } ?: SeatId(chooserSeatId)
        return when (ref.zone) {
            "Battlefield" -> ZoneIds.BATTLEFIELD
            "Exile" -> ZoneIds.EXILE
            "Stack" -> ZoneIds.STACK
            "Graveyard" -> ZoneIds.graveyardOf(ownerSeat)
            "Hand" -> ZoneIds.handOf(ownerSeat)
            "Library" -> ZoneIds.libraryOf(ownerSeat)
            else -> 0
        }
    }
}
