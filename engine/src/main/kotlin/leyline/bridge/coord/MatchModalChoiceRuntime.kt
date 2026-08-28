package leyline.bridge.coord

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.ModalChoiceAiContext
import leyline.bridge.handoff.ModalChoiceCleanupToken
import leyline.bridge.handoff.ModalChoiceInteractionResult
import leyline.bridge.handoff.ModalChoiceInteractionRuntime
import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedModalChoiceInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.ModalChoiceWindowMaterializer
import java.util.concurrent.CompletableFuture

/** Exact Forge-handle modal lifecycle beneath [MatchCutCoordinator]. */
internal class MatchModalChoiceRuntime(
    private val owner: MatchCutCoordinator,
) : ModalChoiceInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.ModalChoice

    private data class Window(
        val published: PublishedModalChoiceInteraction,
        val value: ModalChoiceWindowValue,
        override val cut: PendingPromptCut<ModalChoiceWindowValue>,
        val handlesByOptionIndex: Map<Int, AbilitySub>,
        val optionIndexByGrpId: Map<Int, Int>,
        val aiContext: ModalChoiceAiContext,
        override val future: CompletableFuture<ModalChoiceInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<ModalChoiceInteractionResult, PendingPromptCut<ModalChoiceWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private data class CleanupReceipt(
        val token: ModalChoiceCleanupToken,
        val sourceInstanceId: Int,
        val triggered: Boolean,
        val cut: PendingPromptCut<ModalChoiceWindowValue>,
    )

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<ModalChoiceWindowValue>, ModalChoiceInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<ModalChoiceWindowValue>, ModalChoiceInteractionResult>(
            owner,
            windows,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
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

    override fun current(): PublishedModalChoiceInteraction? = windows.current()?.published

    /** Read-only Forge context for the harness policy; no prompt/session lookup. */
    internal fun aiContext(): ModalChoiceAiContext? =
        synchronized(owner.feedLock) {
            windows.current()?.aiContext
        }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedGrpIds: List<Int>,
    ): Boolean = submitAndClaim(interactionId, gameStateId, selectedGrpIds) != null

    internal fun submitAndClaim(
        interactionId: String,
        gameStateId: Int,
        selectedGrpIds: List<Int>,
    ): ModalChoiceCleanupToken? =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return null
            if (selectedGrpIds.size !in pending.value.min..pending.value.max) return null
            if (!pending.value.allowRepeat && selectedGrpIds.size != selectedGrpIds.distinct().size) return null
            val optionIndices = selectedGrpIds.map { pending.optionIndexByGrpId[it] ?: return null }
            detachAndCompleteLocked(pending, optionIndices, selectedGrpIds, timedOut = false).token
        }

    /** Correlated client cancellation; empty selection unwinds the Forge ability. */
    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean = cancelAndClaim(interactionId, gameStateId) != null

    internal fun cancelAndClaim(
        interactionId: String,
        gameStateId: Int,
    ): ModalChoiceCleanupToken? =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return null
            detachAndCompleteLocked(pending, emptyList(), emptyList(), timedOut = false).token
        }

    /** Release Forge after its response was accepted, then remove synthetic trigger state. */
    fun releaseAfterEngineResume(token: ModalChoiceCleanupToken): Boolean =
        synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                val receipt = cleanupReceipts.remove(token.interactionId) ?: return false
                queueCleanupLocked(receipt)
                true
            }
        }

    fun releaseAfterEngineResume(interactionId: String): Boolean =
        releaseAfterEngineResume(
            ModalChoiceCleanupToken(interactionId),
        )

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            windows.terminate(cause)
            cleanupReceipts.clear()
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            windows.reset()
            cleanupReceipts.clear()
        }
    }

    override fun claimTerminalCutLocked(): PendingPromptCut<ModalChoiceWindowValue>? =
        windows
            .pendingCutLocked()
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: ModalChoiceWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A ModalChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareModalChoiceWindow(
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
                    PublishedModalChoiceInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        prepared.sourceInstanceId,
                    )
                val exact =
                    PendingPromptCut(
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
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
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
                synchronized(owner.bridge.projectionBuildLock) {
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
                ModalChoiceCleanupToken(pending.published.interactionId),
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
        val prior = owner.bridge.projectionStateSnapshot()
        val planner = LogicalSequencePlanner(prior.sequence)
        val cleanup = ModalChoiceWindowMaterializer(owner.humanSeat.value).cleanup(planner, receipt.sourceInstanceId)
        try {
            owner.cutInstaller.install(
                feed,
                PreparedCut.prepare(prior, planner, listOf(cleanup), projection = null, closesPlaybackFrame = false),
                onFailure = { ex -> owner.failPrompt(ex, pending = receipt.cut) },
            )
        } catch (ex: Exception) {
            owner.failPrompt(ex, pending = receipt.cut)
        }
    }
}
