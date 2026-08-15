package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.GatherCountersWindowValue

/** Engine-thread capture for the bounded GatherCounters row. */
internal class GatherCountersWindowCapture(
    private val payCostsCapture: OneShotPayCostsWindowCapture,
) {
    data class Initial(
        val value: GatherCountersWindowValue,
        val handlesBySourceId: Map<Int, Card>,
    )

    fun initial(
        window: GatherCountersWindowInput,
        candidateHandles: List<Card>,
    ): Initial {
        val handlesById = candidateHandles.associateBy { it.id }
        require(handlesById.size == candidateHandles.size) {
            "GatherCounters candidates require unique Forge ids"
        }
        val frozenSource =
            payCostsCapture.freezePromptSource(window.promptSource, null)
                ?: error("GatherCounters requires an exact stack ability source")
        require(frozenSource is leyline.bridge.handoff.PayCostsPromptSourceValue.StackAbility)
        require(frozenSource.forgeAbilityId == window.promptSource.forgeAbilityId)
        val handlesBySourceId =
            window.sources.associate { source ->
                source.forgeCardId.value to
                    (
                        handlesById[source.forgeCardId.value]
                            ?: error("Missing GatherCounters source ${source.forgeCardId.value}")
                    )
            }
        return Initial(
            GatherCountersWindowValue(
                promptSource = frozenSource,
                sources = window.sources,
                amountToGather = window.amountToGather,
                counterType = window.counterType,
            ),
            handlesBySourceId,
        )
    }
}
