package leyline.bridge.coord

import forge.game.Game
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Shared single-window correlation, timeout arbitration, and waiter retirement. */
internal interface SinglePromptWindow<R, C> {
    val interactionId: String
    val gameStateId: Int
    val cut: C
    val future: CompletableFuture<R>
}

internal class SinglePromptWindowState<W : SinglePromptWindow<R, C>, C, R>(
    private val owner: MatchCutCoordinator,
) {
    private var window: W? = null

    fun current(): W? = synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone } }

    fun ensureEmptyLocked(message: String) {
        check(window == null) { message }
    }

    fun installLocked(created: W) {
        check(window == null) { "A prompt interaction is already pending" }
        window = created
    }

    fun matchingLocked(
        interactionId: String,
        gameStateId: Int,
    ): W? =
        window?.takeUnless { it.future.isDone }?.takeIf {
            it.interactionId == interactionId && it.gameStateId == gameStateId
        }

    fun completeLocked(
        pending: W,
        result: R,
    ): Boolean {
        if (window !== pending || pending.future.isDone) return false
        window = null
        return pending.future.complete(result)
    }

    fun pendingCutLocked(): C? = window?.takeUnless { it.future.isDone }?.cut

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    fun await(
        pending: W,
        timeoutMs: Long?,
        timeoutException: () -> Throwable,
        beforeTimeoutClaim: (() -> Unit)? = null,
        timeoutClaim: ((() -> Unit) -> Unit) = { claim -> synchronized(owner.feedLock) { claim() } },
        beforeTimeoutCompleteLocked: (() -> Unit)? = null,
    ): R =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            timeoutClaim {
                if (window === pending && !pending.future.isDone) {
                    beforeTimeoutCompleteLocked?.invoke()
                    if (window === pending && !pending.future.isDone) {
                        window = null
                        pending.future.completeExceptionally(timeoutException())
                    }
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completedValue(pending: W): R =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
}

/** Prepared output and exact pending cut for one single-window publication. */
internal data class SinglePromptPublication<W>(
    val window: W,
    val transition: ProjectionTransition,
    val closesPlaybackFrame: Boolean,
    val viewerOutputs: List<PreparedViewerOutput>,
) {
    init {
        require(viewerOutputs.isNotEmpty()) { "A single prompt publication requires viewer output" }
    }
}

/** Owns the shared publication transaction for settled single-window prompts. */
internal class SinglePromptRuntimeKernel<W, C, R>(
    private val owner: MatchCutCoordinator,
    private val windows: SinglePromptWindowState<W, C, R>,
    private val publicationFailure: (Throwable, W) -> Nothing,
) where W : SinglePromptWindow<R, C> {
    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null

    fun publish(
        duplicateMessage: String,
        prepare: (String, MatchCutCoordinator.ViewerFeed, Game?, LogicalSequencePlanner) -> SinglePromptPublication<W>,
        ensureEmptyLocked: () -> Unit = {},
    ): W {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    windows.ensureEmptyLocked(duplicateMessage)
                    ensureEmptyLocked()
                    val feed = owner.feed(owner.humanSeat)
                    val prior = owner.bridge.projectionStateSnapshot()
                    val planner = LogicalSequencePlanner(prior.sequence)
                    val interactionId = UUID.randomUUID().toString()
                    val publication = prepare(interactionId, feed, owner.bridge.getGame(), planner)
                    publishPrepared(prior, planner, publication)
                    windows.installLocked(publication.window)
                    publication.window
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    fun await(
        pending: W,
        timeoutMs: Long?,
        timeoutException: () -> Throwable,
        beforeTimeoutClaim: (() -> Unit)? = null,
        timeoutClaim: ((() -> Unit) -> Unit) = { claim -> synchronized(owner.feedLock) { claim() } },
        beforeTimeoutCompleteLocked: (() -> Unit)? = null,
    ): R =
        windows.await(
            pending,
            timeoutMs,
            timeoutException,
            beforeTimeoutClaim,
            timeoutClaim,
            beforeTimeoutCompleteLocked,
        )

    private fun publishPrepared(
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
        publication: SinglePromptPublication<W>,
    ) {
        val cut =
            PreparedCut.prepareForViewers(
                prior,
                planner,
                publication.viewerOutputs,
                publication.transition,
                publication.closesPlaybackFrame,
                playbackOwnerSeatId = owner.humanSeat.takeIf { publication.closesPlaybackFrame },
            )
        owner.cutInstaller.install(
            cut,
            CutInstallHooks(beforeInstall = beforeInstall, afterInstall = afterInstall),
        ) { ex -> publicationFailure(ex, publication.window) }
    }
}
