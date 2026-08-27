package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedSearchInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.SearchInteractionTimeoutException
import leyline.bridge.handoff.SearchWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact library-search lifecycle beneath [MatchCutCoordinator]. */
internal class MatchSearchInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : SearchInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.Search

    private data class Window(
        val published: PublishedSearchInteraction,
        val value: SearchWindowValue,
        override val cut: PendingPromptCut<SearchWindowValue>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SinglePromptWindow<List<Int>, PendingPromptCut<SearchWindowValue>> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val capture = SearchWindowCapture(owner)
    private val windows = SinglePromptWindowState<Window, PendingPromptCut<SearchWindowValue>, List<Int>>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingPromptCut<SearchWindowValue>, List<Int>>(
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
    internal var afterBaselineResetBeforeRelease: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitSearch(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.Search)
        val value =
            try {
                capture.capture(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        val pending = publish(value)
        return await(pending, timeoutMs)
    }

    override fun current(): PublishedSearchInteraction? = windows.current()?.published

    override fun claimTerminalCutLocked(): PendingPromptCut<SearchWindowValue>? =
        windows.pendingCutLocked().also { afterDeliveryCutLookup?.invoke() }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedInstanceIds: List<Int>,
    ): Boolean =
        synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val pending = windows.matchingLocked(interactionId, gameStateId) ?: return false
                if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return false
                val selectedOptions =
                    if (selectedInstanceIds.isEmpty()) {
                        if (pending.value.minFind != 0) return false
                        listOf(pending.value.optionCount)
                    } else {
                        if (selectedInstanceIds.size !in pending.value.minFind..pending.value.maxFind) return false
                        selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return false }
                    }
                resetBaseline()
                afterBaselineResetBeforeRelease?.invoke()
                windows.completeLocked(pending, selectedOptions)
            }
        }

    override fun terminate(cause: Throwable) = windows.terminate(cause)

    override fun reset() = windows.reset()

    private fun publish(value: SearchWindowValue): Window =
        kernel.publish(
            duplicateMessage = "A search interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, value)
                val preparedViewers =
                    try {
                        feed.builder.prepareSearchWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            value,
                            owner.viewerRoutes(),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published = PublishedSearchInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition.nextState
                val optionEntries =
                    value.candidateCardIdsByOption.map { (option, cardId) ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[cardId]?.value
                                ?: owner.failPrompt(IllegalStateException("Search candidate ${cardId.value} was not projected"), exact)
                        instanceId to option
                    }
                val optionByInstanceId = optionEntries.toMap()
                if (optionByInstanceId.size != optionEntries.size) {
                    owner.failPrompt(IllegalStateException("Search candidates have ambiguous client identities"), exact)
                }
                val created = Window(published, value, exact, optionByInstanceId)
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
    ): List<Int> =
        kernel.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = ::SearchInteractionTimeoutException,
            beforeTimeoutClaim = beforeTimeoutClaim,
            timeoutClaim = { claim ->
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) { claim() }
                }
            },
            beforeTimeoutCompleteLocked = {
                resetBaseline()
                afterBaselineResetBeforeRelease?.invoke()
            },
        )

    private fun resetBaseline() {
        val transition = owner.feed(owner.humanSeat).builder.prepareSearchBaselineReset(owner.bridge.projectionStateSnapshot())
        owner.cutInstaller.installProjectionOnly(transition, owner::fail)
    }
}
