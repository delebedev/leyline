package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

object PromptResponseMapper {
    fun selectNIdsToPromptIndices(
        selectedIds: List<Int>,
        request: PromptRequest,
        resolveForgeCardId: (Int) -> ForgeCardId?,
    ): List<Int> = cardInstanceIdsToPromptIndices(selectedIds, request, resolveForgeCardId)

    fun cardInstanceIdsToPromptIndices(
        instanceIds: List<Int>,
        request: PromptRequest,
        resolveForgeCardId: (Int) -> ForgeCardId?,
    ): List<Int> =
        instanceIds
            .mapNotNull { instanceId ->
                val cardId = resolveForgeCardId(instanceId) ?: return@mapNotNull null
                request.candidateRefs.firstOrNull { it.isCard() && it.entityId == cardId.value }?.index
            }.filter { it >= 0 }

    fun targetIdsToPromptIndices(
        instanceIds: List<Int>,
        request: PromptRequest,
        resolveForgeCardId: (Int) -> ForgeCardId?,
        resolvePlayerEntityId: (Int) -> Int?,
    ): List<Int> =
        instanceIds
            .mapNotNull { instanceId ->
                playerIndex(instanceId, request, resolvePlayerEntityId)
                    ?: cardIndex(instanceId, request, resolveForgeCardId)
            }.filter { it >= 0 }

    private fun playerIndex(
        instanceId: Int,
        request: PromptRequest,
        resolvePlayerEntityId: (Int) -> Int?,
    ): Int? {
        val playerId = resolvePlayerEntityId(instanceId) ?: return null
        return request.candidateRefs
            .firstOrNull { ref ->
                ref.isPlayer() && (ref.entityId == playerId || ref.entityId == instanceId)
            }?.index
    }

    private fun cardIndex(
        instanceId: Int,
        request: PromptRequest,
        resolveForgeCardId: (Int) -> ForgeCardId?,
    ): Int? {
        val cardId = resolveForgeCardId(instanceId) ?: return null
        return request.candidateRefs.firstOrNull { it.isCard() && it.entityId == cardId.value }?.index
    }
}
