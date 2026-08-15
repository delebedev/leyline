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
import leyline.game.CardSelectMaterializationDiagnostic
import leyline.game.PendingCardSelectCut
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Exact card-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchCardSelectInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : CardSelectInteractionRuntime {
    private data class Window(
        val published: PublishedCardSelectInteraction,
        val value: CardSelectWindowValue,
        val cut: PendingCardSelectCut,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<CardSelectInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<CardSelectInteractionResult> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingCardSelectCut, CardSelectInteractionResult>(owner, Window::cut)

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
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

    fun current(): PublishedCardSelectInteraction? = windows.current()?.published

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

    fun terminate(cause: Throwable) = windows.terminate(cause)

    fun reset() = windows.reset()

    internal fun pendingCutLocked(): PendingCardSelectCut? = windows.pendingCutLocked()

    private fun publish(initial: CardSelectWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        windows.ensureEmptyLocked("A CardSelect interaction is already pending")
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = CardSelectMaterializationDiagnostic(interactionId, initial.value)
                        val prepared =
                            try {
                                feed.builder.prepareCardSelectWindow(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                owner.failCardSelect(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedCardSelectInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                initial.value.kind,
                            )
                        val exact =
                            PendingCardSelectCut(
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
                                        ?: owner.failCardSelect(
                                            IllegalStateException("CardSelect candidate was not projected"),
                                            exact,
                                        )
                                instanceId to candidate.originalOptionIndex
                            }
                        val optionByInstanceId = entries.toMap()
                        if (optionByInstanceId.size != entries.size) {
                            owner.failCardSelect(IllegalStateException("CardSelect candidates have ambiguous identities"), exact)
                        }
                        val created = Window(published, initial.value, exact, initial.handlesByOption, optionByInstanceId)
                        publishPrepared(feed, prepared, exact)
                        windows.installLocked(created)
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: leyline.game.bundle.CardSelectWindowMaterializer.Prepared,
        exact: PendingCardSelectCut,
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
            owner.failCardSelect(ex, exact)
        }
    }

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
    ): CardSelectInteractionResult = windows.await(pending, timeoutMs, ::CardSelectInteractionTimeoutException, beforeTimeoutClaim)
}
