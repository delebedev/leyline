package leyline.bridge.coord

import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedReplacementInteraction
import leyline.bridge.handoff.ReplacementInteractionResult
import leyline.bridge.handoff.ReplacementInteractionRuntime
import leyline.bridge.handoff.ReplacementInteractionTimeoutException
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect as ReplacementEffectRow

/** Exact competing-replacement lifecycle beneath [MatchCutCoordinator]. */
internal class MatchReplacementInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : ReplacementInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority: PromptTerminalPriority = PromptTerminalPriority.Replacement

    private data class Window(
        val published: PublishedReplacementInteraction,
        val value: ReplacementWindowValue,
        override val cut: PendingPromptCut<ReplacementWindowValue>,
        val handlesByOption: Map<Int, ReplacementEffect>,
        val publishedRows: List<ReplacementEffectRow>,
        override val future: CompletableFuture<ReplacementInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<ReplacementInteractionResult, PendingPromptCut<ReplacementWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<ReplacementWindowValue>, ReplacementInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<ReplacementWindowValue>, ReplacementInteractionResult>(
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

    override fun awaitReplacement(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
        timeoutMs: Long?,
    ): ReplacementInteractionResult? {
        val initial =
            try {
                ReplacementWindowCapture.initial(request, possibleReplacers) ?: return null
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    override fun current(): PublishedReplacementInteraction? = windows.current()?.published

    fun submitWire(
        interactionId: String,
        gameStateId: Int,
        echoed: ReplacementEffectRow,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            val optionIndex = pending.publishedRows.indexOfFirst { it == echoed }
            if (optionIndex < 0) return false
            val handle = pending.handlesByOption[optionIndex] ?: return false
            windows.completeLocked(pending, ReplacementInteractionResult(optionIndex, handle))
        }

    override fun terminate(cause: Throwable) = windows.terminate(cause)

    override fun reset() = windows.reset()

    override fun claimTerminalCutLocked(): PendingPromptCut<ReplacementWindowValue>? = windows.pendingCutLocked()

    private fun publish(initial: ReplacementWindowCapture.Initial): Window =
        kernel.publish(
            duplicateMessage = "A Replacement interaction is already pending",
            prepare = { interactionId, feed, game ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val prepared =
                    try {
                        feed.builder.prepareReplacementWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            owner.counter,
                            initial.value,
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val published =
                    PublishedReplacementInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                SinglePromptPublication(
                    Window(published, initial.value, exact, initial.handlesByOption, prepared.rows),
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): ReplacementInteractionResult = kernel.await(pending, timeoutMs, ::ReplacementInteractionTimeoutException, beforeTimeoutClaim)
}
