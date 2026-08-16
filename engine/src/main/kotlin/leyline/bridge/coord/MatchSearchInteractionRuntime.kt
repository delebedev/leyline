package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedSearchInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.SearchInteractionTimeoutException
import leyline.bridge.handoff.SearchWindowValue
import leyline.game.PendingSearchCut
import leyline.game.SearchMaterializationDiagnostic
import java.util.concurrent.CompletableFuture

/** Exact library-search lifecycle beneath [MatchCutCoordinator]. */
internal class MatchSearchInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : SearchInteractionRuntime {
    private data class Window(
        val published: PublishedSearchInteraction,
        val value: SearchWindowValue,
        override val cut: PendingSearchCut,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SinglePromptWindow<List<Int>, PendingSearchCut> {
        override val interactionId: String get() = published.interactionId
        override val gameStateId: Int get() = published.gameStateId
    }

    private val capture = SearchWindowCapture(owner)
    private val windows = SinglePromptWindowState<Window, PendingSearchCut, List<Int>>(owner)
    private val kernel =
        SinglePromptRuntimeKernel<Window, PendingSearchCut, List<Int>>(
            owner,
            windows,
            publicationFailure = { cause, failed -> owner.failSearch(cause, failed.cut) },
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

    fun current(): PublishedSearchInteraction? = windows.current()?.published

    internal fun pendingCutLocked(): PendingSearchCut? = windows.pendingCutLocked().also { afterDeliveryCutLookup?.invoke() }

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

    fun terminate(cause: Throwable) = windows.terminate(cause)

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            val pending = windows.pendingCutLocked()
            afterDeliveryCutLookup?.invoke()
            pending?.let { owner.failSearch(cause, it) } ?: owner.fail(cause)
        }

    fun reset() = windows.reset()

    private fun publish(value: SearchWindowValue): Window =
        kernel.publish(
            duplicateMessage = "A search interaction is already pending",
            prepare = { interactionId, feed, game ->
                val diagnostic = SearchMaterializationDiagnostic(interactionId, value)
                val prepared =
                    try {
                        feed.builder.prepareSearchWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            owner.counter,
                            value,
                        )
                    } catch (ex: Exception) {
                        owner.failSearch(ex, diagnostic = diagnostic)
                    }
                val published = PublishedSearchInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingSearchCut(
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
                                ?: owner.failSearch(IllegalStateException("Search candidate ${cardId.value} was not projected"), exact)
                        instanceId to option
                    }
                val optionByInstanceId = optionEntries.toMap()
                if (optionByInstanceId.size != optionEntries.size) {
                    owner.failSearch(IllegalStateException("Search candidates have ambiguous client identities"), exact)
                }
                val created = Window(published, value, exact, optionByInstanceId)
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
        try {
            owner.bridge.commitProjection(transition)
        } catch (ex: Exception) {
            owner.fail(ex)
        }
    }
}
