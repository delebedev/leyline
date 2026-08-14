package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedRevealChoiceInteraction
import leyline.bridge.handoff.RevealChoiceInteractionResult
import leyline.bridge.handoff.RevealChoiceInteractionRuntime
import leyline.bridge.handoff.RevealChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.PendingRevealChoiceCut
import leyline.game.RevealChoiceMaterializationDiagnostic
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact reveal-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchRevealChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : RevealChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedRevealChoiceInteraction,
        val value: RevealChoiceWindowValue,
        val revealEntry: PromptJournal.RevealEntry,
        val cut: PendingRevealChoiceCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        val future: CompletableFuture<RevealChoiceInteractionResult> = CompletableFuture(),
    )

    private var window: Window? = null

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        revealEntry: PromptJournal.RevealEntry,
        recordExiledUnderSource: Boolean,
        timeoutMs: Long?,
    ): RevealChoiceInteractionResult {
        val initial =
            try {
                RevealChoiceWindowCapture.initial(
                    request,
                    candidateHandles,
                    revealEntry,
                    owner.humanSeat,
                    recordExiledUnderSource,
                )
            } catch (ex: Exception) {
                clearReveal(revealEntry, owner.humanSeat)
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedRevealChoiceInteraction? = synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            if (selectedInstanceIds.size !in pending.value.min..pending.value.max) return false
            if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
            val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            completeLocked(pending, options, timedOut = false)
        }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.let { pending ->
                clearReveal(pending.revealEntry, pending.value.journalSeatId)
                pending.future.completeExceptionally(cause)
            }
            window = null
        }
    }

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            val pending = window?.takeUnless { it.future.isDone }
            afterDeliveryCutLookup?.invoke()
            if (pending != null) {
                clearReveal(pending.revealEntry, pending.value.journalSeatId)
                owner.failRevealChoice(cause, pending.cut)
            }
            owner.fail(cause)
        }

    fun reset() {
        synchronized(owner.feedLock) {
            window?.let { clearReveal(it.revealEntry, it.value.journalSeatId) }
            window = null
        }
    }

    internal fun pendingCutLocked(): PendingRevealChoiceCut? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: RevealChoiceWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A RevealChoice interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: failInitial(IllegalStateException("Game unavailable"), initial)
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = RevealChoiceMaterializationDiagnostic(interactionId, initial.value)
                        val prepared =
                            try {
                                feed.builder.prepareRevealChoiceWindow(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                failInitial(ex, initial, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedRevealChoiceInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                        val exact =
                            PendingRevealChoiceCut(
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
                                        ?: failInitial(
                                            IllegalStateException("RevealChoice candidate was not projected"),
                                            initial,
                                            exact,
                                        )
                                instanceId to candidate.originalOptionIndex
                            }
                        val optionByInstanceId = entries.toMap()
                        if (optionByInstanceId.size != entries.size) {
                            failInitial(IllegalStateException("RevealChoice candidates have ambiguous identities"), initial, exact)
                        }
                        val created =
                            Window(
                                published,
                                initial.value,
                                initial.revealEntry,
                                exact,
                                initial.handlesByOption,
                                optionByInstanceId,
                            )
                        publishPrepared(feed, prepared, initial, exact)
                        window = created
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: leyline.game.bundle.RevealChoiceWindowMaterializer.Prepared,
        initial: RevealChoiceWindowCapture.Initial,
        exact: PendingRevealChoiceCut,
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
            failInitial(ex, initial, exact)
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): RevealChoiceInteractionResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending && !pending.future.isDone) {
                    val fallback = listOf(pending.value.defaultOptionIndex).filter(pending.handlesByOption::containsKey)
                    completeLocked(pending, fallback, timedOut = true)
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completeLocked(
        pending: Window,
        options: List<Int>,
        timedOut: Boolean,
    ): Boolean {
        val handles = options.map(pending.handlesByOption::getValue)
        pending.value.exileUnderSourceForgeCardId?.let { source ->
            handles.forEach { card ->
                journal(pending.value.journalSeatId).record(
                    PromptSideEffect.ExiledUnderSource(ForgeCardId(card.id), source),
                )
            }
        }
        clearReveal(pending.revealEntry, pending.value.journalSeatId)
        window = null
        return pending.future.complete(RevealChoiceInteractionResult(options, handles, timedOut))
    }

    private fun completedValue(pending: Window): RevealChoiceInteractionResult =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun failInitial(
        cause: Throwable,
        initial: RevealChoiceWindowCapture.Initial,
        pending: PendingRevealChoiceCut? = null,
        diagnostic: RevealChoiceMaterializationDiagnostic? = null,
    ): Nothing {
        clearReveal(initial.revealEntry, initial.value.journalSeatId)
        owner.failRevealChoice(cause, pending, diagnostic)
    }

    private fun clearReveal(
        entry: PromptJournal.RevealEntry,
        seatId: leyline.bridge.types.SeatId,
    ) {
        journal(seatId).clearActiveReveal(entry)
    }

    private fun journal(seatId: leyline.bridge.types.SeatId): PromptJournal =
        owner.bridge
            .seat(seatId)
            .prompt.journal

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
