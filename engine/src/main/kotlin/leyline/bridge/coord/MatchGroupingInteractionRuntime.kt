package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GroupingArrangementValue
import leyline.bridge.handoff.GroupingInteractionResult
import leyline.bridge.handoff.GroupingInteractionRuntime
import leyline.bridge.handoff.GroupingWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedGroupingInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact Scry and Surveil lifecycle beneath [MatchCutCoordinator]. */
internal class MatchGroupingInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : GroupingInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.Grouping

    private data class Window(
        val published: PublishedGroupingInteraction,
        val value: GroupingWindowValue,
        override val cut: PendingPromptCut<GroupingWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        val instanceIdsByCardId: Map<ForgeCardId, InstanceId>,
        override val future: CompletableFuture<GroupingInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<GroupingInteractionResult, PendingPromptCut<GroupingWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<GroupingWindowValue>, GroupingInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<GroupingWindowValue>, GroupingInteractionResult>(
            owner,
            windows,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
        )

    private data class Finalization(
        val interactionId: String,
        val context: wotc.mtgo.gre.external.messaging.Messages.GroupingContext,
        val candidateIds: Set<ForgeCardId>,
        val instanceIdsByCardId: Map<ForgeCardId, InstanceId>,
    )

    private var finalization: Finalization? = null
    private val arrangements = ArrayDeque<GroupingArrangementValue>()

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
    internal var beforeMaterialize: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitGrouping(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): GroupingInteractionResult {
        val initial =
            try {
                GroupingWindowCapture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    override fun current(): PublishedGroupingInteraction? = windows.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        topInstanceIds: List<Int>,
        awayInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            val allIds = topInstanceIds + awayInstanceIds
            if (allIds.size != pending.value.candidates.size || allIds.distinct().size != allIds.size) return false
            val allOptions = allIds.map { pending.optionByInstanceId[it] ?: return false }
            if (allOptions.toSet() != pending.handlesByOption.keys) return false
            val topOptions = topInstanceIds.map(pending.optionByInstanceId::getValue)
            val awayOptions = awayInstanceIds.map(pending.optionByInstanceId::getValue)
            completeLocked(pending, topOptions, awayOptions, timedOut = false)
        }

    override fun finalizeArrangement(
        result: GroupingInteractionResult,
        finalTopHandles: List<Card>,
        awayHandles: List<Card>,
    ) {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending =
                finalization?.takeIf { it.interactionId == result.interactionId }
                    ?: error("Grouping result is not awaiting finalization")
            val topIds = finalTopHandles.map { ForgeCardId(it.id) }
            val awayIds = awayHandles.map { ForgeCardId(it.id) }
            val all = topIds + awayIds
            check(all.size == all.distinct().size && all.toSet() == pending.candidateIds) {
                "Final Grouping partition must contain every exact candidate once"
            }
            check(awayIds == result.awayHandles.map { ForgeCardId(it.id) }) {
                "Only the kept Grouping cards may be reordered"
            }
            arrangements +=
                GroupingArrangementValue(
                    owner.humanSeat,
                    pending.context,
                    topIds.map { pending.instanceIdsByCardId.getValue(it).value },
                    awayIds.map { pending.instanceIdsByCardId.getValue(it).value },
                )
            finalization = null
        }
    }

    fun pollArrangement(
        seatId: SeatId,
        context: wotc.mtgo.gre.external.messaging.Messages.GroupingContext,
    ): GroupingArrangementValue? =
        synchronized(owner.feedLock) {
            val index = arrangements.indexOfFirst { it.seatId == seatId && it.context == context }
            if (index < 0) null else arrangements.removeAt(index)
        }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            windows.terminate(cause)
            finalization = null
            arrangements.clear()
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            windows.reset()
            finalization = null
            arrangements.clear()
        }
    }

    override fun claimTerminalCutLocked(): PendingPromptCut<GroupingWindowValue>? =
        windows
            .pendingCutLocked()
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: GroupingWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A Grouping interaction is already pending",
            ensureEmptyLocked = { check(finalization == null) { "A Grouping interaction is already active" } },
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val prepared =
                    try {
                        beforeMaterialize?.invoke()
                        feed.builder.prepareGroupingWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial.value,
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val published =
                    PublishedGroupingInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.value.context,
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
                            projection.identities.forgeIdToInstanceId[candidate.forgeCardId]
                                ?: owner.failPrompt(IllegalStateException("Grouping candidate was not projected"), exact)
                        Triple(instanceId.value, candidate.originalOptionIndex, candidate.forgeCardId to instanceId)
                    }
                val optionsByInstanceId = entries.associate { it.first to it.second }
                if (optionsByInstanceId.size != entries.size) {
                    owner.failPrompt(IllegalStateException("Grouping candidates have ambiguous identities"), exact)
                }
                val created =
                    Window(
                        published,
                        initial.value,
                        exact,
                        initial.handlesByOption,
                        optionsByInstanceId,
                        entries.associate { it.third },
                    )
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
    ): GroupingInteractionResult =
        kernel.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("Grouping timeout should complete with a default") },
            beforeTimeoutClaim = beforeTimeoutClaim,
            beforeTimeoutCompleteLocked = {
                val away =
                    if (pending.value.singleCardChoice) {
                        if (pending.value.defaultOptionIndex == 1) listOf(0) else emptyList()
                    } else {
                        listOf(pending.value.defaultOptionIndex).filter(pending.handlesByOption::containsKey)
                    }
                completeLocked(
                    pending,
                    pending.handlesByOption.keys.filterNot(away::contains),
                    away,
                    timedOut = true,
                )
            },
        )

    private fun completeLocked(
        pending: Window,
        topOptions: List<Int>,
        awayOptions: List<Int>,
        timedOut: Boolean,
    ): Boolean {
        val result =
            GroupingInteractionResult(
                pending.published.interactionId,
                pending.value.context,
                topOptions.map(pending.handlesByOption::getValue),
                awayOptions.map(pending.handlesByOption::getValue),
                timedOut,
                this,
            )
        val completed = windows.completeLocked(pending, result)
        if (completed) {
            finalization =
                Finalization(
                    pending.published.interactionId,
                    pending.value.context,
                    pending.instanceIdsByCardId.keys,
                    pending.instanceIdsByCardId,
                )
        }
        return completed
    }
}
