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
import leyline.game.GroupingMaterializationDiagnostic
import leyline.game.PendingGroupingCut
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact Scry and Surveil lifecycle beneath [MatchCutCoordinator]. */
internal class MatchGroupingInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : GroupingInteractionRuntime {
    private data class Window(
        val published: PublishedGroupingInteraction,
        val value: GroupingWindowValue,
        val cut: PendingGroupingCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        val instanceIdsByCardId: Map<ForgeCardId, InstanceId>,
        val future: CompletableFuture<GroupingInteractionResult> = CompletableFuture(),
    )

    private data class Finalization(
        val interactionId: String,
        val context: wotc.mtgo.gre.external.messaging.Messages.GroupingContext,
        val candidateIds: Set<ForgeCardId>,
        val instanceIdsByCardId: Map<ForgeCardId, InstanceId>,
    )

    private var window: Window? = null
    private var finalization: Finalization? = null
    private val arrangements = ArrayDeque<GroupingArrangementValue>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
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

    fun current(): PublishedGroupingInteraction? = synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        topInstanceIds: List<Int>,
        awayInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
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

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
            finalization = null
            arrangements.clear()
        }
    }

    fun reset() {
        synchronized(owner.feedLock) {
            window = null
            finalization = null
            arrangements.clear()
        }
    }

    internal fun pendingCutLocked(): PendingGroupingCut? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: GroupingWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null && finalization == null) { "A Grouping interaction is already active" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = GroupingMaterializationDiagnostic(interactionId, initial.value)
                        val prepared =
                            try {
                                beforeMaterialize?.invoke()
                                feed.builder.prepareGroupingWindow(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                owner.failGrouping(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedGroupingInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                initial.value.context,
                            )
                        val exact =
                            PendingGroupingCut(
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
                                        ?: owner.failGrouping(IllegalStateException("Grouping candidate was not projected"), exact)
                                Triple(instanceId.value, candidate.originalOptionIndex, candidate.forgeCardId to instanceId)
                            }
                        val optionsByInstanceId = entries.associate { it.first to it.second }
                        if (optionsByInstanceId.size != entries.size) {
                            owner.failGrouping(IllegalStateException("Grouping candidates have ambiguous identities"), exact)
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
                            owner.failGrouping(ex, exact)
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
    ): GroupingInteractionResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending && !pending.future.isDone) {
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
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

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
        window = null
        finalization =
            Finalization(
                pending.published.interactionId,
                pending.value.context,
                pending.instanceIdsByCardId.keys,
                pending.instanceIdsByCardId,
            )
        return pending.future.complete(result)
    }

    private fun completedValue(pending: Window): GroupingInteractionResult =
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
