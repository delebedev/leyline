package leyline.bridge.coord

import leyline.bridge.handoff.DistributionInteractionResult
import leyline.bridge.handoff.DistributionInteractionRuntime
import leyline.bridge.handoff.DistributionInteractionTimeoutException
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.PublishedDistributionInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact fixed-total allocation lifecycle beneath [MatchCutCoordinator]. */
internal class MatchDistributionInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : DistributionInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority: PromptTerminalPriority = PromptTerminalPriority.Distribution

    private data class Window(
        val published: PublishedDistributionInteraction,
        val value: DistributionWindowValue,
        override val cut: PendingPromptCut<DistributionWindowValue>,
        val targetByWireId: Map<Int, DistributionTargetRef>,
        override val future: CompletableFuture<DistributionInteractionResult> = CompletableFuture(),
    ) : SinglePromptWindow<DistributionInteractionResult, PendingPromptCut<DistributionWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val windows = SinglePromptWindowState<Window, PendingPromptCut<DistributionWindowValue>, DistributionInteractionResult>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<DistributionWindowValue>, DistributionInteractionResult>(
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

    override fun awaitDistribution(
        window: DistributionWindowValue,
        timeoutMs: Long?,
    ): DistributionInteractionResult = await(publish(window), timeoutMs)

    override fun current(): PublishedDistributionInteraction? = windows.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        rows: List<Pair<DistributionTargetRef, Int>>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            submitLocked(pending, rows)
        }

    fun submitWire(
        interactionId: String,
        gameStateId: Int,
        rows: List<Pair<Int, Int>>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            submitLocked(
                pending,
                rows.map { (wireId, amount) ->
                    val target = pending.targetByWireId[wireId] ?: return false
                    target to amount
                },
            )
        }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
            windows.completeLocked(
                pending,
                DistributionInteractionResult(
                    pending.value.targets.associateWith { 0 },
                    cancelled = true,
                ),
            )
        }

    override fun terminate(cause: Throwable) = windows.terminate(cause)

    override fun reset() = windows.reset()

    override fun claimTerminalCutLocked(): PendingPromptCut<DistributionWindowValue>? = windows.pendingCutLocked()

    private fun publish(initial: DistributionWindowValue): Window =
        kernel.publish(
            duplicateMessage = "A Distribution interaction is already pending",
            prepare = { interactionId, feed, game ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val prepared =
                    try {
                        feed.builder.prepareDistributionWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            owner.counter,
                            initial,
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val published =
                    PublishedDistributionInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId), initial.kind)
                val exact =
                    PendingPromptCut(interactionId, published.gameStateId, initial, prepared.bundle.messages, prepared.transition)
                val targetByWireId =
                    initial.targets.associateBy { target ->
                        when (target) {
                            is DistributionTargetRef.Card ->
                                prepared.transition.nextState.identities.forgeIdToInstanceId[target.id]
                                    ?.value
                                    ?: owner.fail(IllegalStateException("Distribution target has no projected instance id"))
                            is DistributionTargetRef.Player -> target.id.value
                        }
                    }
                if (targetByWireId.size != initial.targets.size) {
                    owner.fail(IllegalStateException("Distribution targets have colliding wire ids"))
                }
                SinglePromptPublication(
                    Window(published, initial, exact, targetByWireId),
                    prepared.bundle.messages,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                )
            },
        )

    private fun submitLocked(
        pending: Window,
        rows: List<Pair<DistributionTargetRef, Int>>,
    ): Boolean {
        val expected = pending.value.targets.toSet()
        if (rows.size != expected.size || rows.map { it.first }.toSet() != expected) return false
        if (rows.any { it.second < pending.value.minPerTarget }) return false
        if (rows.sumOf { it.second } != pending.value.amount) return false
        return windows.completeLocked(pending, DistributionInteractionResult(rows.toMap()))
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): DistributionInteractionResult =
        try {
            kernel.await(pending, timeoutMs, ::DistributionInteractionTimeoutException, beforeTimeoutClaim)
        } catch (_: DistributionInteractionTimeoutException) {
            pending.value.fallback()
        }
}
