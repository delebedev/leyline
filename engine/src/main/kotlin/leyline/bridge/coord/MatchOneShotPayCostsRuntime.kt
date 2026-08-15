package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GatherCountersResult
import leyline.bridge.handoff.GatherCountersSelection
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.GatherCountersWindowValue
import leyline.bridge.handoff.OneShotPayCostsResult
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OneShotPayCostsTimeoutException
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.handoff.firstFitGatherCounters
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.PendingOneShotPayCostsCut
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Match-scoped lifecycle for Select and the bounded GatherCounters PayCosts windows. */
internal class MatchOneShotPayCostsRuntime(
    private val owner: MatchCutCoordinator,
) : OneShotPayCostsRuntime {
    private data class SelectWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: OneShotPayCostsWindowValue,
        val cut: PendingOneShotPayCostsCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        val future: CompletableFuture<OneShotPayCostsResult> = CompletableFuture(),
    )

    private data class GatherWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: GatherCountersWindowValue,
        val cut: PendingOneShotPayCostsCut,
        val handlesBySourceId: Map<Int, Card>,
        val sourceByInstanceId: Map<Int, Int>,
        val future: CompletableFuture<GatherCountersResult> = CompletableFuture(),
    ) {
        fun firstFit(timedOut: Boolean): GatherCountersResult =
            firstFitGatherCounters(value.sources, value.amountToGather, handlesBySourceId, timedOut)
    }

    private var selectWindow: SelectWindow? = null
    private var gatherWindow: GatherWindow? = null
    private val capture = OneShotPayCostsWindowCapture(owner)
    private val gatherCapture = GatherCountersWindowCapture(capture)

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
        return awaitSelect(publishSelect(initial), timeoutMs)
    }

    override fun awaitGatherCounters(
        window: GatherCountersWindowInput,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): GatherCountersResult {
        val initial =
            try {
                gatherCapture.initial(window, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return awaitGather(publishGather(initial), timeoutMs)
    }

    fun current(): PublishedOneShotPayCostsInteraction? =
        synchronized(owner.feedLock) {
            selectWindow?.takeUnless { it.future.isDone }?.published
                ?: gatherWindow?.takeUnless { it.future.isDone }?.published
        }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matchingSelect(interactionId, gameStateId) ?: return false
            if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
            val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            if (options.size !in pending.value.minSelections..pending.value.maxSelections) return false
            val selected = pending.value.candidates.filter { it.originalOptionIndex in options }
            if (pending.value.minimumWeight?.let { minimum -> selected.sumOf { it.weight } < minimum } == true) return false
            val handles = options.map { pending.handlesByOption.getValue(it) }
            selectWindow = null
            pending.future.complete(OneShotPayCostsResult(options, handles))
        }

    fun submitGatherCounters(
        interactionId: String,
        gameStateId: Int,
        selections: List<GatherCountersSelection>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matchingGather(interactionId, gameStateId) ?: return false
            if (selections.size != selections.distinctBy { it.instanceId }.size) return false
            if (selections.any { it.amount <= 0 }) return false
            val amountsBySource = pending.sourceByInstanceId
            if (selections.any { it.instanceId !in amountsBySource }) return false
            val capacities =
                pending.value.sources.associate { source ->
                    pending.sourceByInstanceId.entries
                        .first { it.value == source.forgeCardId.value }
                        .key to source.maxAmount
                }
            if (selections.any { it.amount > capacities.getValue(it.instanceId) }) return false
            if (selections.sumOf { it.amount } != pending.value.amountToGather) return false
            val payments =
                selections.map { selection ->
                    leyline.bridge.handoff.GatherCountersPayment(
                        pending.handlesBySourceId.getValue(amountsBySource.getValue(selection.instanceId)),
                        selection.amount,
                    )
                }
            gatherWindow = null
            pending.future.complete(GatherCountersResult(payments))
        }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            matchingSelect(interactionId, gameStateId)?.let { pending ->
                selectWindow = null
                pending.future.complete(OneShotPayCostsResult(emptyList(), emptyList()))
                return true
            }
            matchingGather(interactionId, gameStateId)?.let { pending ->
                gatherWindow = null
                pending.future.complete(GatherCountersResult.EMPTY)
                return true
            }
            false
        }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            selectWindow?.future?.completeExceptionally(cause)
            gatherWindow?.future?.completeExceptionally(cause)
            selectWindow = null
            gatherWindow = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) {
            selectWindow = null
            gatherWindow = null
        }
    }

    internal fun pendingCutLocked(): PendingOneShotPayCostsCut? =
        (
            selectWindow?.takeUnless { it.future.isDone }?.cut
                ?: gatherWindow?.takeUnless { it.future.isDone }?.cut
        ).also { afterDeliveryCutLookup?.invoke() }

    private fun publishSelect(initial: OneShotPayCostsWindowCapture.Initial): SelectWindow {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        ensureNoWindow()
                        owner.ensureOpen()
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic =
                            OneShotPayCostsMaterializationDiagnostic(
                                interactionId,
                                leyline.bridge.handoff.OneShotPayCostsWindow
                                    .Select(initial.value),
                            )
                        if (initial.value.kind == leyline.bridge.handoff.PayCostsRouteKind.CollectEvidence) {
                            owner.bridge.promptBridge(owner.humanSeat).journal.record(
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
                                selectKind = initial.value.kind,
                            )
                        val exact =
                            PendingOneShotPayCostsCut(
                                interactionId,
                                published.gameStateId,
                                leyline.bridge.handoff.OneShotPayCostsWindow
                                    .Select(initial.value),
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val projection = prepared.transition.nextState
                        val optionEntries =
                            initial.value.candidates.map { candidate ->
                                val instanceId =
                                    projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                        ?: owner.failOneShotPayCosts(IllegalStateException("PayCosts candidate was not projected"), exact)
                                instanceId to candidate.originalOptionIndex
                            }
                        val optionByInstanceId = optionEntries.toMap()
                        if (optionByInstanceId.size != optionEntries.size) {
                            owner.failOneShotPayCosts(IllegalStateException("PayCosts candidates have ambiguous identities"), exact)
                        }
                        val created = SelectWindow(published, initial.value, exact, initial.handlesByOption, optionByInstanceId)
                        install(feed, prepared, exact)
                        selectWindow = created
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun publishGather(initial: GatherCountersWindowCapture.Initial): GatherWindow {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        ensureNoWindow()
                        owner.ensureOpen()
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val value = initial.value
                        val diagnostic =
                            OneShotPayCostsMaterializationDiagnostic(
                                interactionId,
                                leyline.bridge.handoff.OneShotPayCostsWindow
                                    .GatherCounters(value),
                            )
                        val prepared =
                            try {
                                feed.builder.prepareGatherCounters(game, owner.counter, value)
                            } catch (ex: Exception) {
                                owner.failOneShotPayCosts(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedOneShotPayCostsInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                windowKind = leyline.bridge.handoff.OneShotPayCostsWindowKind.GatherCounters,
                            )
                        val exact =
                            PendingOneShotPayCostsCut(
                                interactionId,
                                published.gameStateId,
                                leyline.bridge.handoff.OneShotPayCostsWindow
                                    .GatherCounters(value),
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val sourceEntries =
                            value.sources.map { source ->
                                val instanceId =
                                    prepared.transition.nextState.identities.forgeIdToInstanceId[source.forgeCardId]
                                        ?.value
                                        ?: owner.failOneShotPayCosts(
                                            IllegalStateException("GatherCounters source was not projected"),
                                            exact,
                                        )
                                instanceId to source.forgeCardId.value
                            }
                        val sourceByInstanceId = sourceEntries.toMap()
                        if (sourceByInstanceId.size != sourceEntries.size) {
                            owner.failOneShotPayCosts(IllegalStateException("GatherCounters sources have ambiguous identities"), exact)
                        }
                        val created = GatherWindow(published, value, exact, initial.handlesBySourceId, sourceByInstanceId)
                        install(feed, prepared, exact)
                        gatherWindow = created
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun install(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: leyline.game.bundle.PreparedPayCostsCut,
        exact: PendingOneShotPayCostsCut,
    ) {
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
    }

    private fun ensureNoWindow() {
        check(selectWindow == null && gatherWindow == null) { "A one-shot PayCosts interaction is already pending" }
    }

    private fun awaitSelect(
        pending: SelectWindow,
        timeoutMs: Long?,
    ): OneShotPayCostsResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            claimTimeout(pending)
            completedSelect(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun awaitGather(
        pending: GatherWindow,
        timeoutMs: Long?,
    ): GatherCountersResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            claimTimeout(pending)
            completedGather(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun claimTimeout(pending: SelectWindow) {
        beforeTimeoutClaim?.invoke()
        synchronized(owner.feedLock) {
            if (selectWindow === pending && !pending.future.isDone) {
                selectWindow = null
                pending.future.completeExceptionally(OneShotPayCostsTimeoutException())
            }
        }
    }

    private fun claimTimeout(pending: GatherWindow) {
        beforeTimeoutClaim?.invoke()
        synchronized(owner.feedLock) {
            if (gatherWindow === pending && !pending.future.isDone) {
                gatherWindow = null
                pending.future.complete(pending.firstFit(timedOut = true))
            }
        }
    }

    private fun completedSelect(pending: SelectWindow): OneShotPayCostsResult =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completedGather(pending: GatherWindow): GatherCountersResult =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun matchingSelect(
        interactionId: String,
        gameStateId: Int,
    ): SelectWindow? =
        selectWindow?.takeUnless { it.future.isDone }?.takeIf {
            it.published.interactionId == interactionId && it.published.gameStateId == gameStateId
        }

    private fun matchingGather(
        interactionId: String,
        gameStateId: Int,
    ): GatherWindow? =
        gatherWindow?.takeUnless { it.future.isDone }?.takeIf {
            it.published.interactionId == interactionId && it.published.gameStateId == gameStateId
        }
}
