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
) : OrderInteractionRuntime {
    private data class Window(
        val published: PublishedOrderInteraction,
        val value: OrderWindowValue,
        override val cut: PendingPromptCut<OrderWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OrderInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<OrderInteractionResult, PendingPromptCut<OrderWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<OrderWindowValue>, OrderInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<OrderWindowValue>, OrderInteractionResult>(
            owner,
            windows,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
        )

    internal var beforeInstall: (() -> Unit)?
        get() = kernel.beforeInstall
        set(value) {
            kernel.beforeInstall = value
        }
    internal var afterInstall: (() -> Unit)?
        get() = kernel.afterInstall
        set(value) {
            kernel.afterInstall = value
        }
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

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

    fun current(): PublishedOrderInteraction? = windows.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        orderedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            if (orderedInstanceIds.size != pending.value.candidates.size) return false
            if (orderedInstanceIds.size != orderedInstanceIds.distinct().size) return false
            val options = orderedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            if (options.toSet() != pending.handlesByOption.keys) return false
            val result = OrderInteractionResult(options, options.map(pending.handlesByOption::getValue))
            windows.completeLocked(pending, result)
        }

    fun terminate(cause: Throwable) = windows.terminate(cause)

    fun reset() = windows.reset()

    internal fun pendingCutLocked(): PendingPromptCut<OrderWindowValue>? =
        windows.pendingCutLocked().also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: OrderWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "An Order interaction is already pending",
            prepare = { interactionId, feed, game ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val prepared =
                    try {
                        feed.builder.prepareOrderWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            owner.counter,
                            initial.value,
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
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
                SinglePromptPublication(
                    created,
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): OrderInteractionResult = kernel.await(pending, timeoutMs, ::OrderInteractionTimeoutException, beforeTimeoutClaim)
}
