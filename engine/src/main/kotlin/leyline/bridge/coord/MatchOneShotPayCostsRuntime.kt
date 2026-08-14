package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OneShotPayCostsResult
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OneShotPayCostsTimeoutException
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.PendingOneShotPayCostsCut
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact lifecycle for the seven non-iterative PayCosts routes. */
internal class MatchOneShotPayCostsRuntime(
    private val owner: MatchCutCoordinator,
) : OneShotPayCostsRuntime {
    private data class Window(
        val published: PublishedOneShotPayCostsInteraction,
        val value: OneShotPayCostsWindowValue,
        val cut: PendingOneShotPayCostsCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        val future: CompletableFuture<OneShotPayCostsResult> = CompletableFuture(),
    )

    private var window: Window? = null
    private val capture = OneShotPayCostsWindowCapture(owner)

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): OneShotPayCostsResult {
        val initial =
            try {
                capture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedOneShotPayCostsInteraction? =
        synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
            val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            if (options.size !in pending.value.minSelections..pending.value.maxSelections) return false
            val selected = pending.value.candidates.filter { it.originalOptionIndex in options }
            if (pending.value.minimumWeight?.let { minimum -> selected.sumOf { it.weight } < minimum } == true) return false
            val handles = options.map { pending.handlesByOption.getValue(it) }
            window = null
            pending.future.complete(OneShotPayCostsResult(options, handles))
        }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            window = null
            pending.future.complete(OneShotPayCostsResult(emptyList(), emptyList()))
        }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    internal fun pendingCutLocked(): PendingOneShotPayCostsCut? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: OneShotPayCostsWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A one-shot PayCosts interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = OneShotPayCostsMaterializationDiagnostic(interactionId, initial.value)
                        if (initial.value.kind == leyline.bridge.handoff.PayCostsRouteKind.CollectEvidence) {
                            owner.bridge
                                .promptBridge(owner.humanSeat)
                                .journal
                                .record(
                                    PromptSideEffect.CollectEvidenceCost(
                                        checkNotNull(initial.value.sourceForgeCardId),
                                        checkNotNull(initial.value.minimumWeight),
                                    ),
                                )
                        }
                        val prepared =
                            try {
                                feed.builder.prepareOneShotPayCosts(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                owner.failOneShotPayCosts(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedOneShotPayCostsInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                initial.value.kind,
                            )
                        val exact =
                            PendingOneShotPayCostsCut(
                                interactionId,
                                published.gameStateId,
                                initial.value,
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val projection = prepared.transition.nextState
                        val optionEntries =
                            initial.value.candidates.map { candidate ->
                                val instanceId =
                                    projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                        ?: owner.failOneShotPayCosts(
                                            IllegalStateException("PayCosts candidate was not projected"),
                                            exact,
                                        )
                                instanceId to candidate.originalOptionIndex
                            }
                        val optionByInstanceId = optionEntries.toMap()
                        if (optionByInstanceId.size != optionEntries.size) {
                            owner.failOneShotPayCosts(IllegalStateException("PayCosts candidates have ambiguous identities"), exact)
                        }
                        val created = Window(published, initial.value, exact, initial.handlesByOption, optionByInstanceId)
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
                            owner.failOneShotPayCosts(ex, exact)
                        }
                        window = created
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
    ): OneShotPayCostsResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending && !pending.future.isDone) {
                    window = null
                    pending.future.completeExceptionally(OneShotPayCostsTimeoutException())
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completedValue(pending: Window): OneShotPayCostsResult =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.future.isDone) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        return pending
    }
}
