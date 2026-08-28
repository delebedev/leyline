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
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact reveal-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchRevealChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : RevealChoiceInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.RevealChoice

    private data class Window(
        val published: PublishedRevealChoiceInteraction,
        val value: RevealChoiceWindowValue,
        val revealEntry: PromptJournal.RevealEntry,
        override val cut: PendingPromptCut<RevealChoiceWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<RevealChoiceInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<RevealChoiceInteractionResult, PendingPromptCut<RevealChoiceWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<RevealChoiceWindowValue>, RevealChoiceInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<RevealChoiceWindowValue>, RevealChoiceInteractionResult>(
            owner,
            windows,
            publicationFailure = { cause, failed ->
                clearReveal(failed.revealEntry, failed.value.journalSeatId)
                owner.failPrompt(cause, failed.cut)
            },
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

    override fun current(): PublishedRevealChoiceInteraction? = windows.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            if (selectedInstanceIds.size !in pending.value.min..pending.value.max) return false
            if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
            val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
            completeLocked(pending, options, timedOut = false)
        }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            windows.current()?.let { pending ->
                clearReveal(pending.revealEntry, pending.value.journalSeatId)
            }
            windows.terminate(cause)
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            windows.current()?.let { clearReveal(it.revealEntry, it.value.journalSeatId) }
            windows.reset()
        }
    }

    override fun claimTerminalCutLocked(): PendingPromptCut<RevealChoiceWindowValue>? {
        val pending = windows.current()
        afterDeliveryCutLookup?.invoke()
        pending?.let { clearReveal(it.revealEntry, it.value.journalSeatId) }
        return pending?.cut
    }

    private fun publish(initial: RevealChoiceWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A RevealChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareRevealChoiceWindow(
                            game ?: failInitial(IllegalStateException("Game unavailable"), initial),
                            planner,
                            initial.value,
                            owner.viewerRoutes(),
                        )
                    } catch (ex: Exception) {
                        failInitial(ex, initial, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published = PublishedRevealChoiceInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
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
                                ?: failInitial(IllegalStateException("RevealChoice candidate was not projected"), initial, exact)
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
                SinglePromptPublication(
                    created,
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): RevealChoiceInteractionResult =
        kernel.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("RevealChoice timeout should complete with a default") },
            beforeTimeoutClaim = beforeTimeoutClaim,
            beforeTimeoutCompleteLocked = {
                val fallback = listOf(pending.value.defaultOptionIndex).filter(pending.handlesByOption::containsKey)
                completeLocked(pending, fallback, timedOut = true)
            },
        )

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
        return windows.completeLocked(pending, RevealChoiceInteractionResult(options, handles, timedOut))
    }

    private fun failInitial(
        cause: Throwable,
        initial: RevealChoiceWindowCapture.Initial,
        pending: PendingPromptCut<RevealChoiceWindowValue>? = null,
        diagnostic: PromptMaterializationDiagnostic<RevealChoiceWindowValue>? = null,
    ): Nothing {
        clearReveal(initial.revealEntry, initial.value.journalSeatId)
        owner.failPrompt(cause, pending, diagnostic)
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
}
