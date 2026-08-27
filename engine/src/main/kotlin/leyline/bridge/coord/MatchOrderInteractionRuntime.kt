package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.OrderInteractionTimeoutException
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedOrderInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact ordered-card lifecycle beneath [MatchCutCoordinator]. */
internal class MatchOrderInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
) : OrderInteractionRuntime {
    private data class Window(
        val published: PublishedOrderInteraction,
        val value: OrderWindowValue,
        override val cut: PendingPromptCut<OrderWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OrderInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<OrderInteractionResult> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val slot =
        settled.mount<Window, OrderInteractionResult>(
            PromptTerminalPriority.Order,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
        )

    override fun awaitOrder(
        request: PromptRequest,
        candidateHandles: List<Card>,
        move: OrderMoveIntent?,
        timeoutMs: Long?,
    ): OrderInteractionResult {
        val initial =
            try {
                OrderWindowCapture.initial(request, candidateHandles, move)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedOrderInteraction? = slot.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        orderedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = slot.matchingLocked(interactionId, gameStateId) ?: return false
            if (orderedInstanceIds.size != pending.value.candidates.size) return false
            if (orderedInstanceIds.size != orderedInstanceIds.distinct().size) return false
            val options = orderedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            if (options.toSet() != pending.handlesByOption.keys) return false
            val result = OrderInteractionResult(options, options.map(pending.handlesByOption::getValue))
            slot.completeLocked(pending, result)
        }

    private fun publish(initial: OrderWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "An Order interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareOrderWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial.value,
                            owner.viewerRoutes(),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published =
                    PublishedOrderInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.value.kind,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition.nextState
                val entries =
                    initial.value.candidates.map { candidate ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                ?: owner.failPrompt(IllegalStateException("Order candidate was not projected"), exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionsByInstanceId = entries.toMap()
                if (optionsByInstanceId.size != entries.size) {
                    owner.failPrompt(IllegalStateException("Order candidates have ambiguous identities"), exact)
                }
                val created = Window(published, initial.value, exact, initial.handlesByOption, optionsByInstanceId)
                SettledPromptOwner.Publication(
                    created,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): OrderInteractionResult = slot.await(pending, timeoutMs, ::OrderInteractionTimeoutException)
}
