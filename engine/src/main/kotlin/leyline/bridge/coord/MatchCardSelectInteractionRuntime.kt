package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedCardSelectInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact card-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchCardSelectInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : CardSelectInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.CardSelect

    private data class Window(
        val published: PublishedCardSelectInteraction,
        val value: CardSelectWindowValue,
        override val cut: PendingPromptCut<CardSelectWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<CardSelectInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<CardSelectInteractionResult, PendingPromptCut<CardSelectWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<CardSelectWindowValue>, CardSelectInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<CardSelectWindowValue>, CardSelectInteractionResult>(
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

    override fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): CardSelectInteractionResult {
        val initial =
            try {
                CardSelectWindowCapture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    override fun current(): PublishedCardSelectInteraction? = windows.current()?.published

    fun submitSelectN(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            completeSelection(pending, selectedInstanceIds)
        }

    fun submitEffectCost(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            when (pending.value.kind) {
                CardSelectKind.LegendRule,
                CardSelectKind.LibraryPutback,
                CardSelectKind.ManifestDread,
                CardSelectKind.Resolution,
                CardSelectKind.ResolutionMapped,
                CardSelectKind.Learn,
                -> return false
                CardSelectKind.Discard,
                CardSelectKind.SacrificeEffect,
                CardSelectKind.Suspect,
                CardSelectKind.MutateTopBottom,
                -> Unit
            }
            completeSelection(pending, selectedInstanceIds)
        }

    override fun terminate(cause: Throwable) = windows.terminate(cause)

    override fun reset() = windows.reset()

    override fun claimTerminalCutLocked(): PendingPromptCut<CardSelectWindowValue>? = windows.pendingCutLocked()

    private fun publish(initial: CardSelectWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A CardSelect interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareCardSelectWindow(
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
                    PublishedCardSelectInteraction(
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
                                ?: owner.failPrompt(IllegalStateException("CardSelect candidate was not projected"), exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionByInstanceId = entries.toMap()
                if (optionByInstanceId.size != entries.size) {
                    owner.failPrompt(IllegalStateException("CardSelect candidates have ambiguous identities"), exact)
                }
                val created = Window(published, initial.value, exact, initial.handlesByOption, optionByInstanceId)
                SinglePromptPublication(
                    created,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                )
            },
        )

    private fun recordChoiceResults(
        pending: Window,
        selectedInstanceIds: List<Int>,
    ) {
        val source = pending.value.sourceForgeCardId ?: return
        val sentiment = pending.value.choiceResultSentiment ?: return
        selectedInstanceIds.forEach { instanceId ->
            owner.bridge
                .seat(owner.humanSeat)
                .prompt.journal
                .record(PromptSideEffect.ChoiceResult(source, owner.humanSeat, instanceId, sentiment = sentiment))
        }
    }

    private fun completeSelection(
        pending: Window,
        selectedInstanceIds: List<Int>,
    ): Boolean {
        if (selectedInstanceIds.size !in pending.value.min..pending.value.max) return false
        if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
        val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
        recordChoiceResults(pending, selectedInstanceIds)
        val result = CardSelectInteractionResult(options, options.map(pending.handlesByOption::getValue))
        return windows.completeLocked(pending, result)
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): CardSelectInteractionResult = kernel.await(pending, timeoutMs, ::CardSelectInteractionTimeoutException, beforeTimeoutClaim)
}
