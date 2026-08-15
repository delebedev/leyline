package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.OrderInteractionTimeoutException
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedOrderInteraction
import leyline.game.OrderMaterializationDiagnostic
import leyline.game.PendingOrderCut
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Exact ordered-card lifecycle beneath [MatchCutCoordinator]. */
internal class MatchOrderInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : OrderInteractionRuntime {
    private data class Window(
        val published: PublishedOrderInteraction,
        val value: OrderWindowValue,
        val cut: PendingOrderCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OrderInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<OrderInteractionResult> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingOrderCut, OrderInteractionResult>(owner, Window::cut)

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
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

    internal fun pendingCutLocked(): PendingOrderCut? = windows.pendingCutLocked().also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: OrderWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        windows.ensureEmptyLocked("An Order interaction is already pending")
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = OrderMaterializationDiagnostic(interactionId, initial.value)
                        val prepared =
                            try {
                                feed.builder.prepareOrderWindow(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                owner.failOrder(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedOrderInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                initial.value.kind,
                            )
                        val exact =
                            PendingOrderCut(
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
                                        ?: owner.failOrder(IllegalStateException("Order candidate was not projected"), exact)
                                instanceId to candidate.originalOptionIndex
                            }
                        val optionsByInstanceId = entries.toMap()
                        if (optionsByInstanceId.size != entries.size) {
                            owner.failOrder(IllegalStateException("Order candidates have ambiguous identities"), exact)
                        }
                        val created = Window(published, initial.value, exact, initial.handlesByOption, optionsByInstanceId)
                        val batch = prepared.bundle.messages
                        var enqueued = false
                        var installed = false
                        try {
                            feed.beforeBatchEnqueue?.invoke(0, batch)
                            feed.queue.add(batch)
                            enqueued = true
                            beforeInstall?.invoke()
                            owner.bridge.commitProjection(prepared.transition) { installed = true }
                            afterInstall?.invoke()
                            if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(owner.humanSeat)
                        } catch (ex: Exception) {
                            if (!installed && enqueued) owner.removeOwnedBatch(feed, batch)
                            owner.failOrder(ex, exact)
                        }
                        windows.installLocked(created)
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): OrderInteractionResult = windows.await(pending, timeoutMs, ::OrderInteractionTimeoutException, beforeTimeoutClaim)
}
