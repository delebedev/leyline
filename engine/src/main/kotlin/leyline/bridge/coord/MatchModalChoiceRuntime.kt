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
import java.util.concurrent.CompletableFuture

/** Exact Forge-handle modal lifecycle beneath [MatchCutCoordinator]. */
internal class MatchModalChoiceRuntime(
    private val owner: MatchCutCoordinator,
) : ModalChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedModalChoiceInteraction,
        val value: ModalChoiceWindowValue,
        override val cut: PendingModalChoiceCut,
        val handlesByOptionIndex: Map<Int, AbilitySub>,
        val optionIndexByGrpId: Map<Int, Int>,
        val aiContext: ModalChoiceAiContext,
        override val future: CompletableFuture<ModalChoiceInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<ModalChoiceInteractionResult, PendingModalChoiceCut> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private data class CleanupReceipt(
        val interactionId: String,
        val sourceInstanceId: Int,
        val triggered: Boolean,
        val cut: PendingModalChoiceCut,
    )

    private val windows = SinglePromptWindowState<Window, PendingModalChoiceCut, ModalChoiceInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingModalChoiceCut, ModalChoiceInteractionResult>(
            owner,
            windows,
            publicationFailure = { cause, failed -> owner.failModalChoice(cause, failed.cut) },
        )
    private val cleanupReceipts = mutableMapOf<String, CleanupReceipt>()

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

    fun current(): PublishedModalChoiceInteraction? = windows.current()?.published

    /** Read-only Forge context for the harness policy; no prompt/session lookup. */
    internal fun aiContext(): ModalChoiceAiContext? =
        synchronized(owner.feedLock) {
            windows.current()?.aiContext
        }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedGrpIds: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
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
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
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
            windows.terminate(cause)
            cleanupReceipts.clear()
        }
    }

    fun reset() {
        synchronized(owner.feedLock) {
            windows.reset()
            cleanupReceipts.clear()
        }
    }

    internal fun pendingCutLocked(): PendingModalChoiceCut? =
        windows
            .pendingCutLocked()
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: ModalChoiceWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A ModalChoice interaction is already pending",
            prepare = { interactionId, feed, game ->
                val diagnostic = ModalChoiceMaterializationDiagnostic(interactionId, initial.value)
                val prepared =
                    try {
                        feed.builder.prepareModalChoiceWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            owner.counter,
                            initial.value,
                        )
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
    ): ModalChoiceInteractionResult =
        kernel.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("ModalChoice timeout should complete with a default") },
            beforeTimeoutClaim = beforeTimeoutClaim,
            timeoutClaim = { claim ->
                synchronized(owner.counter) {
                    synchronized(owner.feedLock) { claim() }
                }
            },
            beforeTimeoutCompleteLocked = {
                val fallback = listOf(pending.value.defaultOptionIndex)
                val grpIds = fallback.map { pending.value.possible[it].grpId }
                val receipt = detachAndCompleteLocked(pending, fallback, grpIds, timedOut = true)
                queueCleanupLocked(receipt)
                cleanupReceipts.remove(pending.published.interactionId)
            },
        )

    private fun detachAndCompleteLocked(
        pending: Window,
        optionIndices: List<Int>,
        selectedGrpIds: List<Int>,
        timedOut: Boolean,
    ): CleanupReceipt {
        check(windows.matchingLocked(pending.interactionId, pending.gameStateId) === pending) {
            "ModalChoice window changed during completion"
        }
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
        windows.completeLocked(
            pending,
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
}
