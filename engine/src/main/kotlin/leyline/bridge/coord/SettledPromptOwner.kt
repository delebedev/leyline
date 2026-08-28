package leyline.bridge.coord

import forge.game.Game
import leyline.game.PendingPromptCut
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One match-scoped owner for every settled prompt slot. */
internal class SettledPromptOwner(
    private val owner: MatchCutCoordinator,
) : PromptLifecycle,
    PromptTerminalCutOwner {
    private val slots = mutableListOf<MountedSlot>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    fun <W, R> mount(
        terminalPriority: PromptTerminalPriority,
        publicationFailure: (Throwable, W) -> Nothing,
        onTerminateLocked: (W?, Throwable) -> Unit = { _, _ -> },
        onResetLocked: (W?) -> Unit = {},
    ): Slot<W, R> where W : Window<R> =
        Slot(
            terminalPriority,
            publicationFailure,
            onTerminateLocked,
            onResetLocked,
        ).also(slots::add)

    override fun current(): Any? = synchronized(owner.feedLock) { slots.firstNotNullOfOrNull(MountedSlot::currentLocked) }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) { slots.forEach { it.terminateLocked(cause) } }
    }

    override fun reset() {
        synchronized(owner.feedLock) { slots.forEach(MountedSlot::resetLocked) }
    }

    override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? {
        val candidate = slots.mapNotNull(MountedSlot::terminalCutCandidateLocked).minByOrNull { it.priority }
        afterDeliveryCutLookup?.invoke()
        return candidate
    }

    internal interface Window<R> {
        val interactionId: String
        val gameStateId: Int
        val cut: PendingPromptCut<*>
        val future: CompletableFuture<R>
    }

    internal data class Publication<W>(
        val window: W,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
        val viewerOutputs: List<PreparedViewerOutput>,
    ) {
        init {
            require(viewerOutputs.isNotEmpty()) { "A settled prompt publication requires viewer output" }
        }
    }

    internal inner class Slot<W, R>(
        private val terminalPriority: PromptTerminalPriority,
        private val publicationFailure: (Throwable, W) -> Nothing,
        private val onTerminateLocked: (W?, Throwable) -> Unit,
        private val onResetLocked: (W?) -> Unit,
    ) : MountedSlot where W : Window<R> {
        private var window: W? = null

        fun current(): W? = synchronized(owner.feedLock) { currentLocked() }

        fun ensureEmptyLocked(message: String) {
            check(window == null) { message }
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

        fun publish(
            duplicateMessage: String,
            prepare: (String, MatchCutCoordinator.ViewerFeed, Game?, LogicalSequencePlanner) -> Publication<W>,
            ensureEmptyLocked: () -> Unit = {},
        ): W {
            owner.beforePublicationLock?.invoke()
            val created =
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    ensureEmptyLocked(duplicateMessage)
                    ensureEmptyLocked()
                    val feed = owner.feed(owner.humanSeat)
                    val prior = owner.bridge.projectionStateSnapshot()
                    val planner = LogicalSequencePlanner(prior.sequence)
                    val publication = prepare(UUID.randomUUID().toString(), feed, owner.bridge.getGame(), planner)
                    publishPrepared(prior, planner, publication)
                    window = publication.window
                    publication.window
                }
            owner.bridge.prioritySignal.signal()
            return created
        }

        fun await(
            pending: W,
            timeoutMs: Long?,
            timeoutException: () -> Throwable,
            beforeTimeoutCompleteLocked: (() -> Unit)? = null,
        ): R =
            try {
                if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                beforeTimeoutClaim?.invoke()
                synchronized(owner.feedLock) {
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

        override fun currentLocked(): W? = window?.takeUnless { it.future.isDone }

        override fun terminateLocked(cause: Throwable) {
            val pending = window
            onTerminateLocked(pending, cause)
            pending?.future?.completeExceptionally(cause)
            window = null
        }

        override fun resetLocked() {
            onResetLocked(window)
            window = null
        }

        override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? =
            currentLocked()?.let { PromptTerminalCutCandidate(terminalPriority, it.cut) }

        private fun publishPrepared(
            prior: ProjectionState,
            planner: LogicalSequencePlanner,
            publication: Publication<W>,
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

        private fun completedValue(pending: W): R =
            try {
                pending.future.get()
            } catch (ex: ExecutionException) {
                throw ex.cause ?: ex
            }
    }

    private interface MountedSlot {
        fun currentLocked(): Any?

        fun terminateLocked(cause: Throwable)

        fun resetLocked()

        fun terminalCutCandidateLocked(): PromptTerminalCutCandidate?
    }
}
