package leyline.bridge.coord

import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.types.SeatId
import leyline.game.MaterializationDiagnostic
import leyline.game.PendingCut
import leyline.game.PendingInteractionCut
import leyline.game.PlaybackCutBoundary
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.BlockingInteractionMaterializer
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Match-scoped imperative owner of projection cuts and committed outbound batches.
 *
 * The coordinator owns the journal close, exact cut, projection install, committed
 * viewer feed, and terminal state. Focused action and blocking runtimes are private
 * mechanisms beneath this one ownership boundary.
 */
internal class MatchCutCoordinator(
    internal val bridge: GameBridge,
    private val matchId: String,
    internal val counter: MessageCounter,
    private val delayMultiplier: Double,
) : BlockingInteractionRuntime {
    internal data class ViewerFeed(
        val seatId: SeatId,
        val builder: BundleBuilder,
        val queue: ConcurrentLinkedQueue<List<GREToClientMessage>> = ConcurrentLinkedQueue(),
        var requestedCut: PlaybackCutRequest? = null,
        var pendingCut: PendingCut? = null,
        var beforeBatchEnqueue: ((Int, List<GREToClientMessage>) -> Unit)? = null,
    )

    internal val feedLock = Any()
    private val feeds = mutableMapOf<SeatId, ViewerFeed>()
    internal val syncOnly = MatchSyncOnlyRuntime(this)
    private val actions = MatchActionWindowRuntime(this)
    private val interactions = MatchBlockingInteractionRuntime(this)

    @Volatile
    private var terminalFailure: PlaybackTerminalFailure? = null

    internal val humanSeat: SeatId get() = bridge.seating.humanSeat

    internal var beforeBlockingInstall: (() -> Unit)?
        get() = interactions.beforeInstall
        set(value) {
            interactions.beforeInstall = value
        }

    internal var beforeBlockingTimeoutClaim: (() -> Unit)?
        get() = interactions.beforeTimeoutClaim
        set(value) {
            interactions.beforeTimeoutClaim = value
        }

    internal var beforeCommanderCleanupMaterialization: (() -> Unit)? = null
    internal var beforeCommanderCleanupInstall: (() -> Unit)? = null

    internal var afterBlockingMaterialization: (() -> Unit)?
        get() = interactions.afterMaterialization
        set(value) {
            interactions.afterMaterialization = value
        }

    internal var beforePublicationLock: (() -> Unit)? = null

    internal var beforeActionEnqueue: (() -> Unit)?
        get() = actions.beforeEnqueue
        set(value) {
            actions.beforeEnqueue = value
        }

    internal var beforeActionInstall: (() -> Unit)?
        get() = actions.beforeInstall
        set(value) {
            actions.beforeInstall = value
        }

    internal var afterActionInstall: (() -> Unit)?
        get() = actions.afterInstall
        set(value) {
            actions.afterInstall = value
        }

    internal var beforeActionCatalogInstall: (() -> Unit)?
        get() = actions.beforeCatalogInstall
        set(value) {
            actions.beforeCatalogInstall = value
        }

    internal var beforeActionPublished: (() -> Unit)?
        get() = actions.beforePublished
        set(value) {
            actions.beforePublished = value
        }

    internal var beforeActionTimeoutClaim: (() -> Unit)?
        get() = actions.beforeTimeoutClaim
        set(value) {
            actions.beforeTimeoutClaim = value
        }

    internal var beforeSynchronizationTimeoutClaim: (() -> Unit)?
        get() = actions.beforeSynchronizationTimeoutClaim
        set(value) {
            actions.beforeSynchronizationTimeoutClaim = value
        }

    fun actionWindowRuntime(seatId: SeatId): GameActionBridge.ActionWindowRuntime = actions.bridge(seatId)

    fun legalAttackerIds(actionId: String): List<Int> = actions.legalAttackerIds(actionId)

    fun legalBlockerCount(actionId: String): Int = actions.legalBlockerCount(actionId)

    fun updateAttackerPresentation(
        actionId: String,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int>,
        selectedDamageRecipients: Map<Int, DamageRecipient>,
    ) = actions.updateAttackers(
        actionId,
        selectedAttackerIds,
        allLegalAttackerIds,
        selectedAttackAlternatives,
        selectedDamageRecipients,
    )

    fun updateBlockerPresentation(
        actionId: String,
        blockAssignments: Map<Int, Int>,
    ) = actions.updateBlockers(actionId, blockAssignments)

    fun bindInitialActionWindow(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq = actions.bindInitial(actionId, gameStateId)

    fun replaceWithPhaseTransition(actionId: String): List<GREToClientMessage> = actions.replaceWithPhaseTransition(actionId)

    fun hasMeaningfulPriorityAction(actionId: String): Boolean = actions.hasMeaningfulAction(actionId)

    fun claimPriorityResponse(
        actionId: String,
        responseGameStateId: Int,
        response: Action,
        defer: Boolean,
    ): MatchActionWindowRuntime.PriorityResponseClaim? = actions.claimPriority(actionId, responseGameStateId, response, defer)

    fun completeActionClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        childToken: Long? = null,
    ): Boolean = actions.complete(claim, childToken)

    fun failActionClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        cause: Throwable,
    ): Nothing = actions.failClaim(claim, cause)

    fun reopenActionClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean = actions.reopenClaim(claim)

    fun submitDeclaredAction(
        actionId: String,
        responseGameStateId: Int,
        answer: DeclarationAnswer,
        confirmation: (() -> GREToClientMessage)? = null,
    ): Boolean = actions.submitDeclaration(actionId, responseGameStateId, answer, confirmation)

    fun currentBlockingInteraction(): PublishedBlockingInteraction? = interactions.current()

    fun submitOptionalAnswer(
        interactionId: String,
        gameStateId: Int,
        accepted: Boolean,
    ): Boolean = interactions.submitOptional(interactionId, gameStateId, accepted)

    fun submitNumericAnswer(
        interactionId: String,
        gameStateId: Int,
        value: Int,
    ): Boolean = interactions.submitNumeric(interactionId, gameStateId, value)

    fun submitDamageAnswer(
        interactionId: String,
        gameStateId: Int,
        assignments: List<DamageAssignmentValue>,
    ): Boolean = interactions.submitDamage(interactionId, gameStateId, assignments)

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean = interactions.awaitOptional(interaction, timeoutMs, defaultOnTimeout)

    override fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int = interactions.awaitNumeric(interaction, timeoutMs)

    override fun awaitDamage(
        interaction: BlockingInteraction.Damage,
        attacker: forge.game.card.Card,
        blockers: forge.game.card.CardCollectionView,
        defender: forge.game.GameEntity?,
        timeoutMs: Long?,
        fallback: () -> MutableMap<forge.game.card.Card?, Int>?,
    ): MutableMap<forge.game.card.Card?, Int>? = interactions.awaitDamage(interaction, attacker, blockers, defender, timeoutMs, fallback)

    override fun takeCachedDamage(
        attacker: forge.game.card.Card,
        blockers: forge.game.card.CardCollectionView,
    ): MutableMap<forge.game.card.Card?, Int>? = interactions.takeCachedDamage(attacker, blockers)

    fun registerViewer(seatId: SeatId) {
        synchronized(feedLock) {
            feeds.getOrPut(seatId) { ViewerFeed(seatId, BundleBuilder(bridge, matchId, seatId.value)) }
        }
    }

    /** Teardown disposition: committed-but-undrained batches are discarded with their viewer feeds. */
    fun unregisterViewers() {
        synchronized(feedLock) { feeds.clear() }
    }

    /** Terminalize every owned waiter and reject later cuts/interactions. */
    fun shutdown(cause: Throwable = CancellationException("Match projection coordinator shut down")) {
        val failure = synchronized(feedLock) { terminalFailure ?: terminate(null, null, cause) }
        interactions.terminate(failure)
        actions.terminate()
        synchronized(feedLock) { feeds.values.forEach { it.requestedCut = null } }
        bridge.prioritySignal.signal()
    }

    fun resetForNewGame() {
        synchronized(feedLock) {
            check(feeds.isEmpty()) { "Cannot reset coordinator with registered viewers" }
            terminalFailure = null
            actions.reset()
        }
    }

    fun requestPlaybackCut(
        seatId: SeatId,
        request: PlaybackCutRequest,
    ) {
        beforePublicationLock?.invoke()
        synchronized(feedLock) {
            ensureOpen()
            val feed = feed(seatId)
            feed.requestedCut = feed.requestedCut?.aggregate(request) ?: request
        }
    }

    fun onMainGameLoopStarted(seatId: SeatId) {
        synchronized(feedLock) {
            ensureOpen()
            bridge.closeBundleFrame(seatId.value)
            feed(seatId).requestedCut = null
            bridge.actionBridge(seatId).forceNextWindowVisible()
        }
    }

    fun flushPlaybackCut(
        seatId: SeatId,
        boundary: PlaybackCutBoundary,
    ) {
        val game = bridge.getGame()
        val request =
            synchronized(counter) {
                synchronized(bridge.projectionBuildLock) {
                    synchronized(feedLock) {
                        ensureOpen()
                        val feed = feed(seatId)
                        val request = feed.requestedCut ?: return
                        if (request.boundary != boundary) return
                        if (game == null) fail(null, MaterializationDiagnostic(request, null), IllegalStateException("Game unavailable"))
                        val events =
                            try {
                                bridge.closeBundleFrame(seatId.value)
                            } catch (ex: Exception) {
                                fail(null, MaterializationDiagnostic(request, null), ex)
                            }
                        val pending =
                            try {
                                PendingCut(
                                    request,
                                    feed.builder.materializePlaybackCut(
                                        game,
                                        counter,
                                        PlaybackFrameSpecMaterializer.materialize(bridge, game, seatId, request, events),
                                    ),
                                )
                            } catch (ex: Exception) {
                                fail(null, MaterializationDiagnostic(request, events), ex)
                            }
                        feed.pendingCut = pending
                        val prepared =
                            try {
                                feed.builder.compilePlaybackCut(pending.projection)
                            } catch (ex: Exception) {
                                fail(pending, null, ex)
                            }
                        val enqueued = mutableListOf<List<GREToClientMessage>>()
                        try {
                            prepared.batches.forEachIndexed { index, batch ->
                                feed.beforeBatchEnqueue?.invoke(index, batch)
                                feed.queue.add(batch)
                                enqueued += batch
                            }
                        } catch (ex: Exception) {
                            removeEnqueuedBatches(feed, enqueued)
                            fail(pending, null, ex)
                        }
                        var installed = false
                        try {
                            bridge.commitProjection(prepared.transition) { installed = true }
                            feed.pendingCut = null
                            feed.requestedCut = null
                            if (bridge.consumePromptTimeoutNeedsAutoAdvance()) {
                                bridge.autoAdvanceRequester?.invoke("prompt timeout playback queued")
                            }
                        } catch (ex: Exception) {
                            if (!installed) removeEnqueuedBatches(feed, enqueued)
                            fail(pending, null, ex)
                        }
                        request
                    }
                }
            }
        pacePlayback(request.delayMs, delayMultiplier)
    }

    fun acknowledgeExternalFrame(seatId: SeatId) {
        synchronized(feedLock) { feeds[seatId]?.requestedCut = null }
    }

    fun drain(
        seatId: SeatId,
        beforeMsgId: Int? = null,
        maxGsId: Int = 0,
    ): List<List<GREToClientMessage>> =
        synchronized(feedLock) {
            val queue = feeds[seatId]?.queue ?: return emptyList()
            buildList {
                while (true) {
                    val batch = queue.peek() ?: break
                    if (beforeMsgId != null) {
                        val firstMsgId = batch.firstOrNull()?.msgId ?: Int.MAX_VALUE
                        val firstGsId = batch.firstGameStateId()
                        if (maxGsId != 0 && firstGsId != null) {
                            if (firstGsId >= maxGsId) break
                        } else if (firstMsgId >= beforeMsgId) {
                            break
                        }
                    }
                    add(queue.poll() ?: break)
                }
            }
        }

    fun hasCommittedBatches(seatId: SeatId): Boolean = synchronized(feedLock) { feeds[seatId]?.queue?.isNotEmpty() == true }

    fun failure(): PlaybackTerminalFailure? = terminalFailure

    fun setBeforeBatchEnqueue(
        seatId: SeatId,
        hook: ((Int, List<GREToClientMessage>) -> Unit)?,
    ) {
        synchronized(feedLock) { feed(seatId).beforeBatchEnqueue = hook }
    }

    fun requestedPlaybackCut(seatId: SeatId): PlaybackCutRequest? = synchronized(feedLock) { feeds[seatId]?.requestedCut }

    fun enqueueCommittedBatchForTest(
        seatId: SeatId,
        batch: List<GREToClientMessage>,
    ) {
        synchronized(feedLock) { feed(seatId).queue.add(batch) }
    }

    internal fun feed(seatId: SeatId): ViewerFeed = feeds[seatId] ?: error("No projection feed registered for seat ${seatId.value}")

    internal fun ensureOpen() {
        terminalFailure?.let { throw it }
    }

    internal fun publishPrepared(
        feed: ViewerFeed,
        prepared: BlockingInteractionMaterializer.Prepared,
        beforeInstall: (() -> Unit)? = null,
    ) {
        val batch = prepared.bundle.messages
        var enqueued = false
        var installed = false
        try {
            feed.queue.add(batch)
            enqueued = true
            beforeInstall?.invoke()
            prepared.transition?.let { transition ->
                bridge.commitProjection(transition) { installed = true }
            } ?: run {
                installed = true
            }
            if (prepared.closesPlaybackFrame) bridge.acknowledgePlaybackFrame(feed.seatId)
        } catch (ex: Exception) {
            if (!installed) {
                if (enqueued) removeOwnedBatch(feed, batch)
            }
            fail(ex)
        }
    }

    internal fun removeOwnedBatch(
        feed: ViewerFeed,
        batch: List<GREToClientMessage>,
    ): Boolean {
        val iterator = feed.queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === batch) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    internal fun fail(
        cause: Throwable,
        pendingInteraction: PendingInteractionCut? = null,
    ): Nothing = fail(null, null, cause, pendingInteraction)

    internal fun failDelivery(cause: Throwable): Nothing = fail(cause)

    private fun fail(
        pending: PendingCut?,
        diagnostic: MaterializationDiagnostic?,
        cause: Throwable,
        pendingInteraction: PendingInteractionCut? = null,
    ): Nothing = throw terminate(pending, diagnostic, cause, pendingInteraction)

    private fun terminate(
        pending: PendingCut?,
        diagnostic: MaterializationDiagnostic?,
        cause: Throwable,
        pendingInteraction: PendingInteractionCut? = null,
    ): PlaybackTerminalFailure =
        synchronized(feedLock) {
            terminalFailure?.let { return@synchronized it }
            PlaybackTerminalFailure(pending, diagnostic, pendingInteraction, cause).also { failure ->
                pending?.let { retained -> feeds.values.firstOrNull { it.pendingCut === retained }?.pendingCut = retained }
                terminalFailure = failure
                interactions.terminate(failure)
                actions.terminate()
                bridge.failActionWindows(failure)
                bridge.prioritySignal.signal()
            }
        }

    private fun removeEnqueuedBatches(
        feed: ViewerFeed,
        enqueued: List<List<GREToClientMessage>>,
    ) {
        enqueued.forEach { removeOwnedBatch(feed, it) }
    }

    private fun List<GREToClientMessage>.firstGameStateId(): Int? = firstOrNull { it.hasGameStateMessage() }?.gameStateMessage?.gameStateId
}
