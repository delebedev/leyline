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
    settled: SettledPromptOwner,
) : DistributionInteractionRuntime {
    private data class Window(
        val published: PublishedDistributionInteraction,
        val value: DistributionWindowValue,
        override val cut: PendingPromptCut<DistributionWindowValue>,
        val targetByWireId: Map<Int, DistributionTargetRef>,
        override val future: CompletableFuture<DistributionInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<DistributionInteractionResult> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val slot =
        settled.mount<Window, DistributionInteractionResult>(
            PromptTerminalPriority.Distribution,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
        )

    override fun awaitDistribution(
        window: DistributionWindowValue,
        timeoutMs: Long?,
    ): DistributionInteractionResult = await(publish(window), timeoutMs)

    fun current(): PublishedDistributionInteraction? = slot.current()?.published

    fun submit(
        interactionId: String,
        gameStateId: Int,
        rows: List<Pair<DistributionTargetRef, Int>>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = slot.matchingLocked(interactionId, gameStateId) ?: return false
            submitLocked(pending, rows)
        }

    fun submitWire(
        interactionId: String,
        gameStateId: Int,
        rows: List<Pair<Int, Int>>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = slot.matchingLocked(interactionId, gameStateId) ?: return false
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
            val pending = slot.matchingLocked(interactionId, gameStateId) ?: return false
            slot.completeLocked(
                pending,
                DistributionInteractionResult(
                    pending.value.targets.associateWith { 0 },
                    cancelled = true,
                ),
            )
        }

    private fun publish(initial: DistributionWindowValue): Window =
        slot.publish(
            duplicateMessage = "A Distribution interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val preparedViewers =
                    try {
                        feed.builder.prepareDistributionWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial,
                            owner.viewerRoutes(),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
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
                SettledPromptOwner.Publication(
                    Window(published, initial, exact, targetByWireId),
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
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
        return slot.completeLocked(pending, DistributionInteractionResult(rows.toMap()))
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): DistributionInteractionResult =
        try {
            slot.await(pending, timeoutMs, ::DistributionInteractionTimeoutException)
        } catch (_: DistributionInteractionTimeoutException) {
            pending.value.fallback()
        }
}
