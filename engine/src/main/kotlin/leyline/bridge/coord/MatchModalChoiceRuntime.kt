package leyline.bridge.coord

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.ModalChoiceAiContext
import leyline.bridge.handoff.ModalChoiceInteractionResult
import leyline.bridge.handoff.ModalChoiceInteractionRuntime
import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedModalChoiceInteraction
import leyline.game.ModalChoiceMaterializationDiagnostic
import leyline.game.PendingModalChoiceCut
import leyline.game.bundle.ModalChoiceWindowMaterializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact Forge-handle modal lifecycle beneath [MatchCutCoordinator]. */
internal class MatchModalChoiceRuntime(
    private val owner: MatchCutCoordinator,
) : ModalChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedModalChoiceInteraction,
        val value: ModalChoiceWindowValue,
        val cut: PendingModalChoiceCut,
        val handlesByOptionIndex: Map<Int, AbilitySub>,
        val optionIndexByGrpId: Map<Int, Int>,
        val aiContext: ModalChoiceAiContext,
        val future: CompletableFuture<ModalChoiceInteractionResult> = CompletableFuture(),
    )

    private data class CleanupReceipt(
        val interactionId: String,
        val sourceInstanceId: Int,
        val triggered: Boolean,
        val cut: PendingModalChoiceCut,
    )

    private var window: Window? = null
    private val cleanupReceipts = mutableMapOf<String, CleanupReceipt>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
        timeoutMs: Long?,
    ): ModalChoiceInteractionResult {
        val initial =
            try {
                ModalChoiceWindowCapture(owner).capture(request, possible, sourceCard, sourceAbility)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedModalChoiceInteraction? {
        val current =
            synchronized(owner.feedLock) {
                window?.takeUnless { it.future.isDone }?.published
            }
        return current
    }

    /** Read-only Forge context for the harness policy; no prompt/session lookup. */
    internal fun aiContext(): ModalChoiceAiContext? =
        synchronized(owner.feedLock) {
            window?.takeUnless { it.future.isDone }?.aiContext
        }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedGrpIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            if (selectedGrpIds.size !in pending.value.min..pending.value.max) return false
            if (!pending.value.allowRepeat && selectedGrpIds.size != selectedGrpIds.distinct().size) return false
            val optionIndices = selectedGrpIds.map { pending.optionIndexByGrpId[it] ?: return false }
            detachAndCompleteLocked(pending, optionIndices, selectedGrpIds, timedOut = false)
            true
        }

    /** Correlated client cancellation; empty selection unwinds the Forge ability. */
    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            detachAndCompleteLocked(pending, emptyList(), emptyList(), timedOut = false)
            true
        }

    /** Release Forge after its response was accepted, then remove synthetic trigger state. */
    fun releaseAfterEngineResume(interactionId: String): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.feedLock) {
                val receipt = cleanupReceipts.remove(interactionId) ?: return false
                queueCleanupLocked(receipt)
                true
            }
        }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
            cleanupReceipts.clear()
        }
    }

    fun reset() {
        synchronized(owner.feedLock) {
            window = null
            cleanupReceipts.clear()
        }
    }

    internal fun pendingCutLocked(): PendingModalChoiceCut? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: ModalChoiceWindowCapture.Initial): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A ModalChoice interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = ModalChoiceMaterializationDiagnostic(interactionId, initial.value)
                        val prepared =
                            try {
                                feed.builder.prepareModalChoiceWindow(game, owner.counter, initial.value)
                            } catch (ex: Exception) {
                                owner.failModalChoice(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedModalChoiceInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                prepared.sourceInstanceId,
                            )
                        val exact =
                            PendingModalChoiceCut(
                                interactionId,
                                published.gameStateId,
                                initial.value,
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val created =
                            Window(
                                published,
                                initial.value,
                                exact,
                                initial.handlesByOptionIndex,
                                initial.value.possible
                                    .mapIndexed { index, option -> option.grpId to index }
                                    .toMap(),
                                initial.aiContext,
                            )
                        publishPrepared(feed, prepared, exact)
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
        prepared: ModalChoiceWindowMaterializer.Prepared,
        exact: PendingModalChoiceCut,
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
            if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(feed.seatId)
        } catch (ex: Exception) {
            if (!installed && enqueued) owner.removeOwnedBatch(feed, batch)
            owner.failModalChoice(ex, exact)
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): ModalChoiceInteractionResult =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.counter) {
                synchronized(owner.feedLock) {
                    if (window === pending && !pending.future.isDone) {
                        val fallback = listOf(pending.value.defaultOptionIndex)
                        val grpIds = fallback.map { pending.value.possible[it].grpId }
                        val receipt = detachAndCompleteLocked(pending, fallback, grpIds, timedOut = true)
                        queueCleanupLocked(receipt)
                        cleanupReceipts.remove(pending.published.interactionId)
                    }
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun detachAndCompleteLocked(
        pending: Window,
        optionIndices: List<Int>,
        selectedGrpIds: List<Int>,
        timedOut: Boolean,
    ): CleanupReceipt {
        check(window === pending) { "ModalChoice window changed during completion" }
        if (selectedGrpIds.singleOrNull() != null) {
            owner.bridge.recordSelectedModalAbilityGrpId(
                pending.value.sourceForgeCardId,
                selectedGrpIds.single(),
            )
        }
        val receipt =
            CleanupReceipt(
                pending.published.interactionId,
                pending.published.sourceInstanceId,
                pending.value.triggered,
                pending.cut,
            )
        cleanupReceipts[pending.published.interactionId] = receipt
        window = null
        pending.future.complete(
            ModalChoiceInteractionResult(
                optionIndices = optionIndices,
                handles = optionIndices.map(pending.handlesByOptionIndex::getValue),
                timedOut = timedOut,
            ),
        )
        return receipt
    }

    private fun queueCleanupLocked(receipt: CleanupReceipt) {
        if (!receipt.triggered) return
        val feed = owner.feed(owner.humanSeat)
        val cleanup = ModalChoiceWindowMaterializer(owner.humanSeat.value).cleanup(owner.counter, receipt.sourceInstanceId)
        try {
            feed.beforeBatchEnqueue?.invoke(0, listOf(cleanup))
            feed.queue.add(listOf(cleanup))
        } catch (ex: Exception) {
            owner.failModalChoice(ex, pending = receipt.cut)
        }
    }

    private fun completedValue(pending: Window): ModalChoiceInteractionResult =
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
