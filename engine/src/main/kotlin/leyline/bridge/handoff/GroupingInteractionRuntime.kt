package leyline.bridge.handoff

import forge.game.card.Card
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

data class GroupingInteractionResult(
    val interactionId: String,
    val context: GroupingContext,
    val topHandles: List<Card>,
    val awayHandles: List<Card>,
    val timedOut: Boolean,
    internal val finalizer: GroupingInteractionRuntime? = null,
)

/** Blocking engine-thread shell contract for Scry and Surveil grouping. */
interface GroupingInteractionRuntime {
    fun awaitGrouping(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): GroupingInteractionResult

    fun finalizeArrangement(
        result: GroupingInteractionResult,
        finalTopHandles: List<Card>,
        awayHandles: List<Card>,
    )
}
