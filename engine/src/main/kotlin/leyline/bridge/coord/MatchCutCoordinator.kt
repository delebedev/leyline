package leyline.bridge.coord

import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.types.SeatId
import leyline.game.MaterializationDiagnostic
import leyline.game.PendingCut
import leyline.game.PendingPromptCut
import leyline.game.PlaybackCutBoundary
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
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
 * Owns journal close, exact cuts, projection install, committed viewer feeds,
 * terminal state, and the focused interaction runtimes beneath that boundary.
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
    internal val cutInstaller = CoordinatorCutInstaller(this)
    internal val syncOnly = MatchSyncOnlyRuntime(this)
    internal val gameOver = MatchGameOverRuntime(this)
    internal val actions = MatchActionWindowRuntime(this)
    internal val prompts = MatchPromptRuntimeSet(this)

    // Read-only views; [prompts] remains the sole lifecycle owner.
    internal val targeting get() = prompts.targeting
    internal val compatibilityCostSelection get() = prompts.compatibilityCostSelection
    internal val search get() = prompts.search
    internal val order get() = prompts.order
    internal val distribution get() = prompts.distribution
    internal val replacement get() = prompts.replacement
    internal val grouping get() = prompts.grouping
    internal val cardSelect get() = prompts.cardSelect
    internal val staticChoices get() = prompts.staticChoices
    internal val revealChoices get() = prompts.revealChoices
    internal val modalChoices get() = prompts.modalChoices
    internal val manaSourcePayments get() = prompts.manaSourcePayments
    internal val oneShotPayCosts get() = prompts.oneShotPayCosts
    internal val interactions get() = prompts.blocking

    private val terminal = MatchCutTerminalRuntime(this)

    internal val humanSeat: SeatId get() = bridge.seating.humanSeat

    internal var beforeCommanderCleanupMaterialization: (() -> Unit)? = null
    internal var beforeCommanderCleanupInstall: (() -> Unit)? = null

    internal var beforePublicationLock: (() -> Unit)? = null

    fun actionWindowRuntime(seatId: SeatId): GameActionBridge.ActionWindowRuntime = actions.bridge(seatId)

    /** Publish the terminal lifecycle cut for one viewer. Delivery stays with the session. */
    fun publishGameOver(
        seatId: SeatId,
        intent: GameOverIntent,
    ) = gameOver.publish(seatId, intent)

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

    fun replaceWithPhaseTransition(
        actionId: String,
        includePriorityPrompt: Boolean = true,
    ): List<GREToClientMessage> = actions.replaceWithPhaseTransition(actionId, includePriorityPrompt)

    fun hasMeaningfulPriorityAction(actionId: String): Boolean = actions.hasMeaningfulAction(actionId)

    fun suppressPriorityPresentation(actionId: String): Boolean = actions.suppressPriorityPresentation(actionId)

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

    fun submitDistributionAnswer(
        interactionId: String,
        gameStateId: Int,
        rows: List<Pair<DistributionTargetRef, Int>>,
    ): Boolean = distribution.submit(interactionId, gameStateId, rows)

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
        synchronized(feedLock) { terminal.current() ?: terminal.terminate(cause) }
        synchronized(feedLock) { feeds.values.forEach { it.requestedCut = null } }
        bridge.prioritySignal.signal()
    }

    fun resetForNewGame() {
        synchronized(feedLock) {
            check(feeds.isEmpty()) { "Cannot reset coordinator with registered viewers" }
            terminal.reset()
            actions.reset()
            prompts.reset()
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
                        if (game == null) {
                            failPlayback(
                                IllegalStateException("Game unavailable"),
                                diagnostic = MaterializationDiagnostic(request, null),
                            )
                        }
                        val events =
                            try {
                                bridge.closeBundleFrame(seatId.value)
                            } catch (ex: Exception) {
                                failPlayback(ex, diagnostic = MaterializationDiagnostic(request, null))
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
                                failPlayback(ex, diagnostic = MaterializationDiagnostic(request, events))
                            }
                        feed.pendingCut = pending
                        val prepared =
                            try {
                                feed.builder.compilePlaybackCut(pending.projection)
                            } catch (ex: Exception) {
                                failPlayback(ex, pending = pending)
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
                            failPlayback(ex, pending = pending)
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
                            failPlayback(ex, pending = pending)
                        }
                        request
                    }
                }
            }
        pacePlayback(request.delayMs, delayMultiplier)
        bridge.playbackDrainRequester?.invoke()
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

    fun failure(): PlaybackTerminalFailure? = terminal.current()

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
        terminal.ensureOpen()
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
        pendingPrompt: PendingPromptCut<*>? = null,
    ): Nothing =
        if (pendingPrompt == null) {
            failTerminal(cause, MatchCutTerminalRuntime.Context())
        } else {
            failPrompt(cause, pendingPrompt)
        }

    internal fun failDelivery(cause: Throwable): Nothing = prompts.failDelivery(cause)

    internal fun failTerminal(
        cause: Throwable,
        context: MatchCutTerminalRuntime.Context,
    ): Nothing = throw terminal.terminate(cause, context)

    private fun failPlayback(
        cause: Throwable,
        pending: PendingCut? = null,
        diagnostic: MaterializationDiagnostic? = null,
    ): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pending = pending, diagnostic = diagnostic))

    internal fun retainPendingCut(pending: PendingCut) {
        feeds.values.firstOrNull { it.pendingCut === pending }?.pendingCut = pending
    }

    private fun removeEnqueuedBatches(
        feed: ViewerFeed,
        enqueued: List<List<GREToClientMessage>>,
    ) {
        enqueued.forEach { removeOwnedBatch(feed, it) }
    }

    private fun List<GREToClientMessage>.firstGameStateId(): Int? = firstOrNull { it.hasGameStateMessage() }?.gameStateMessage?.gameStateId
}
