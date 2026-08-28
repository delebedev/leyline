package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionTimeoutException
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CompletableFuture

/** Exact static enum SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchStaticChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : StaticChoiceInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.StaticChoice

    private data class Window(
        val published: PublishedStaticChoiceInteraction,
        val value: StaticChoiceWindowValue,
        override val cut: PendingPromptCut<StaticChoiceWindowValue>,
        val optionByValue: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SinglePromptWindow<List<Int>, PendingPromptCut<StaticChoiceWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<StaticChoiceWindowValue>, List<Int>>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<StaticChoiceWindowValue>, List<Int>>(
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
    internal var beforeResponseComplete: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        val initial =
            try {
                StaticChoiceWindowCapture.initial(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    override fun current(): PublishedStaticChoiceInteraction? = windows.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedValues: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            if (selectedValues.size !in pending.value.min..pending.value.max) return false
            if (selectedValues.size != selectedValues.distinct().size) return false
            val options = selectedValues.map { pending.optionByValue[it] ?: return false }
            recordChoiceResults(pending, selectedValues)
            beforeResponseComplete?.invoke()
            windows.completeLocked(pending, options)
        }

    override fun terminate(cause: Throwable) = windows.terminate(cause)

    override fun reset() = windows.reset()

    override fun claimTerminalCutLocked(): PendingPromptCut<StaticChoiceWindowValue>? =
        windows.pendingCutLocked().also {
            afterDeliveryCutLookup?.invoke()
        }

    private fun publish(initial: StaticChoiceWindowValue): Window =
        kernel.publish(
            duplicateMessage = "A StaticChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val prepared =
                    try {
                        feed.builder.prepareStaticChoiceWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial,
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val published =
                    PublishedStaticChoiceInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.kind,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val optionByValue = initial.options.associate { it.protocolValue to it.originalOptionIndex }
                val created = Window(published, initial, exact, optionByValue)
                SinglePromptPublication(
                    created,
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
        )

    private fun recordChoiceResults(
        pending: Window,
        selectedValues: List<Int>,
    ) {
        val source = pending.value.sourceForgeCardId ?: return
        selectedValues.forEach { value ->
            owner.bridge
                .seat(owner.humanSeat)
                .prompt.journal
                .record(
                    PromptSideEffect.ChoiceResult(
                        sourceForgeCardId = source,
                        chooserSeatId = owner.humanSeat,
                        choiceValue = value,
                        choiceDomain = pending.value.kind.choiceDomain(),
                        sentiment = 2,
                    ),
                )
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): List<Int> = kernel.await(pending, timeoutMs, ::StaticChoiceInteractionTimeoutException, beforeTimeoutClaim)

    private fun StaticChoiceKind.choiceDomain(): Int =
        when (this) {
            StaticChoiceKind.Color -> StaticList.Colors.number
            StaticChoiceKind.Subtype -> StaticList.SubTypes.number
            StaticChoiceKind.Parity -> StaticList.Parities.number
        }
}
