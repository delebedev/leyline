package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GatherCountersPayment
import leyline.bridge.handoff.GatherCountersResult
import leyline.bridge.handoff.GatherCountersSelection
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.GatherCountersWindowValue
import leyline.bridge.handoff.OneShotPayCostsResult
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OneShotPayCostsTimeoutException
import leyline.bridge.handoff.OneShotPayCostsWindow
import leyline.bridge.handoff.OneShotPayCostsWindowKind
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.handoff.firstFitGatherCounters
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.PendingOneShotPayCostsCut
import java.util.concurrent.CompletableFuture

/** Match-scoped lifecycle for Select and the bounded GatherCounters PayCosts windows. */
internal class MatchOneShotPayCostsRuntime(
    private val owner: MatchCutCoordinator,
) : OneShotPayCostsRuntime {
    private data class SelectWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: OneShotPayCostsWindowValue,
        override val cut: PendingOneShotPayCostsCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OneShotPayCostsResult> = CompletableFuture(),
    ) : SinglePromptWindow<OneShotPayCostsResult, PendingOneShotPayCostsCut> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private data class GatherWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: GatherCountersWindowValue,
        override val cut: PendingOneShotPayCostsCut,
        val handlesBySourceId: Map<Int, Card>,
        val sourceByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<GatherCountersResult> = CompletableFuture(),
    ) : SinglePromptWindow<GatherCountersResult, PendingOneShotPayCostsCut> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId

        fun firstFit(timedOut: Boolean): GatherCountersResult =
            firstFitGatherCounters(value.sources, value.amountToGather, handlesBySourceId, timedOut)
    }

    private val selectWindows = SinglePromptWindowState<SelectWindow, PendingOneShotPayCostsCut, OneShotPayCostsResult>(owner)
    private val gatherWindows = SinglePromptWindowState<GatherWindow, PendingOneShotPayCostsCut, GatherCountersResult>(owner)
    private val selectKernel =
        SinglePromptRuntimeKernel<SelectWindow, PendingOneShotPayCostsCut, OneShotPayCostsResult>(
            owner,
            selectWindows,
            publicationFailure = { cause, failed -> owner.failOneShotPayCosts(cause, failed.cut) },
        )
    private val gatherKernel =
        SinglePromptRuntimeKernel<GatherWindow, PendingOneShotPayCostsCut, GatherCountersResult>(
            owner,
            gatherWindows,
            publicationFailure = { cause, failed -> owner.failOneShotPayCosts(cause, failed.cut) },
        )
    private val capture = OneShotPayCostsWindowCapture(owner)
    private val gatherCapture = GatherCountersWindowCapture(capture)

    /** Both windows share one slot; each publication guards the other kind as well. */
    internal var beforeInstall: (() -> Unit)?
        get() = selectKernel.beforeInstall
        set(value) {
            selectKernel.beforeInstall = value
            gatherKernel.beforeInstall = value
        }
    internal var afterInstall: (() -> Unit)?
        get() = selectKernel.afterInstall
        set(value) {
            selectKernel.afterInstall = value
            gatherKernel.afterInstall = value
        }
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
        return selectKernel.await(publishSelect(initial), timeoutMs, ::OneShotPayCostsTimeoutException, beforeTimeoutClaim)
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
            selectWindows.current()?.published ?: gatherWindows.current()?.published
        }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = selectWindows.matchingLocked(interactionId, gameStateId) ?: return false
            if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
            val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            if (options.size !in pending.value.minSelections..pending.value.maxSelections) return false
            val selected = pending.value.candidates.filter { it.originalOptionIndex in options }
            if (pending.value.minimumWeight?.let { minimum -> selected.sumOf { it.weight } < minimum } == true) return false
            val handles = options.map { pending.handlesByOption.getValue(it) }
            selectWindows.completeLocked(pending, OneShotPayCostsResult(options, handles))
        }

    fun submitGatherCounters(
        interactionId: String,
        gameStateId: Int,
        selections: List<GatherCountersSelection>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = gatherWindows.matchingLocked(interactionId, gameStateId) ?: return false
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
                    GatherCountersPayment(
                        pending.handlesBySourceId.getValue(amountsBySource.getValue(selection.instanceId)),
                        selection.amount,
                    )
                }
            gatherWindows.completeLocked(pending, GatherCountersResult(payments))
        }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            selectWindows.matchingLocked(interactionId, gameStateId)?.let { pending ->
                return selectWindows.completeLocked(pending, OneShotPayCostsResult(emptyList(), emptyList()))
            }
            gatherWindows.matchingLocked(interactionId, gameStateId)?.let { pending ->
                return gatherWindows.completeLocked(pending, GatherCountersResult.EMPTY)
            }
            false
        }

    fun terminate(cause: Throwable) =
        synchronized(owner.feedLock) {
            selectWindows.terminate(cause)
            gatherWindows.terminate(cause)
        }

    fun reset() =
        synchronized(owner.feedLock) {
            selectWindows.reset()
            gatherWindows.reset()
        }

    internal fun pendingCutLocked(): PendingOneShotPayCostsCut? =
        (selectWindows.pendingCutLocked() ?: gatherWindows.pendingCutLocked())
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publishSelect(initial: OneShotPayCostsWindowCapture.Initial): SelectWindow =
        selectKernel.publish(
            duplicateMessage = DUPLICATE_MESSAGE,
            prepare = { interactionId, feed, game ->
                val resolved = game ?: owner.fail(IllegalStateException("Game unavailable"))
                val window = OneShotPayCostsWindow.Select(initial.value)
                val diagnostic = OneShotPayCostsMaterializationDiagnostic(interactionId, window)
                if (initial.value.kind == PayCostsRouteKind.CollectEvidence) {
                    owner.bridge.promptBridge(owner.humanSeat).journal.record(
                        PromptSideEffect.CollectEvidenceCost(
                            checkNotNull(initial.value.sourceForgeCardId),
                            checkNotNull(initial.value.minimumWeight),
                        ),
                    )
                }
                val prepared =
                    try {
                        feed.builder.prepareOneShotPayCosts(resolved, owner.counter, initial.value)
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
                        window,
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
                SinglePromptPublication(
                    SelectWindow(published, initial.value, exact, initial.handlesByOption, optionByInstanceId),
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
            ensureEmptyLocked = { gatherWindows.ensureEmptyLocked(DUPLICATE_MESSAGE) },
        )

    private fun publishGather(initial: GatherCountersWindowCapture.Initial): GatherWindow =
        gatherKernel.publish(
            duplicateMessage = DUPLICATE_MESSAGE,
            prepare = { interactionId, feed, game ->
                val resolved = game ?: owner.fail(IllegalStateException("Game unavailable"))
                val value = initial.value
                val window = OneShotPayCostsWindow.GatherCounters(value)
                val diagnostic = OneShotPayCostsMaterializationDiagnostic(interactionId, window)
                val prepared =
                    try {
                        feed.builder.prepareGatherCounters(resolved, owner.counter, value)
                    } catch (ex: Exception) {
                        owner.failOneShotPayCosts(ex, diagnostic = diagnostic)
                    }
                val published =
                    PublishedOneShotPayCostsInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        windowKind = OneShotPayCostsWindowKind.GatherCounters,
                    )
                val exact =
                    PendingOneShotPayCostsCut(
                        interactionId,
                        published.gameStateId,
                        window,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition.nextState
                val sourceEntries =
                    value.sources.map { source ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[source.forgeCardId]?.value
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
                SinglePromptPublication(
                    GatherWindow(published, value, exact, initial.handlesBySourceId, sourceByInstanceId),
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
            ensureEmptyLocked = { selectWindows.ensureEmptyLocked(DUPLICATE_MESSAGE) },
        )

    private fun awaitGather(
        pending: GatherWindow,
        timeoutMs: Long?,
    ): GatherCountersResult =
        gatherKernel.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("GatherCounters timeout should complete with a default") },
            beforeTimeoutClaim = beforeTimeoutClaim,
            beforeTimeoutCompleteLocked = {
                gatherWindows.completeLocked(pending, pending.firstFit(timedOut = true))
            },
        )

    private companion object {
        const val DUPLICATE_MESSAGE = "A one-shot PayCosts interaction is already pending"
    }
}
