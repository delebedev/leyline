package leyline.bridge.coord

import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** Runtime action catalogs and exact presentation ownership beneath [MatchCutCoordinator]. */
internal class MatchActionWindowRuntime(
    private val owner: MatchCutCoordinator,
) : DeferredCastActionOwner {
    // Written under the coordinator feed lock; read lock-free by the engine wait
    // adapter and session threads asking whether a window is still open.
    private val actionWindows = ConcurrentHashMap<String, RuntimeActionWindow>()
    private var nextActionToken = 1L

    internal var beforeEnqueue: (() -> Unit)? = null
    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeCatalogInstall: (() -> Unit)? = null
    internal var beforePublished: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var beforeSynchronizationTimeoutClaim: (() -> Unit)? = null

    @ConsistentCopyVisibility
    data class ActionClaim internal constructor(
        val actionId: String,
        internal val token: Long,
        internal val kind: ActionClaimKind,
        val deferredCostPlan: leyline.bridge.handoff.DeferredCastCostPlan?,
    )

    @ConsistentCopyVisibility
    data class PriorityResponseClaim internal constructor(
        val cardId: ForgeCardId?,
        val actionClaim: ActionClaim,
    )

    fun bridge(seatId: SeatId): GameActionBridge.ActionWindowRuntime = CoordinatorActionWindowBridge(this, seatId)

    override fun isDeferredClaim(claim: ActionClaim): Boolean =
        actionWindows[claim.actionId]?.status == ActionWindowStatus.Claimed(ActionClaimKind.Deferred, claim.token)

    override fun seatFor(actionId: String): SeatId = checkNotNull(actionWindows[actionId]?.seatId)

    override fun completeDeferredClaim(
        claim: ActionClaim,
        childToken: Long?,
    ): Boolean = complete(claim, childToken)

    override fun reopenDeferredClaim(claim: ActionClaim): Boolean = reopenClaim(claim)

    /**
     * The engine and client see a window only while it is
     * [ActionWindowStatus.Published]. Read without the feed lock: the engine wait
     * adapter polls it from threads that must not block behind a publication.
     */
    internal fun isVisible(actionId: String): Boolean = actionWindows[actionId]?.status == ActionWindowStatus.Published

    internal fun promptGameStateId(actionId: String): Int? = actionWindows[actionId]?.promptGameStateId

    @org.jetbrains.annotations.VisibleForTesting
    internal fun hideForTest(actionId: String) {
        synchronized(owner.feedLock) { actionWindows[actionId]?.status = ActionWindowStatus.Publishing }
    }

    /** Record a published state-only synchronization stop; it has no action catalog. */
    internal fun markSynchronizationPublished(
        seatId: SeatId,
        actionId: String,
    ) {
        actionWindows[actionId] = synchronizationActionWindow(seatId, actionId)
    }

    internal fun claimTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            val window = actionWindows[pending.actionId]
            if (window?.status != ActionWindowStatus.Published) return@synchronized false
            val claimed = pending.future.completeExceptionally(cause)
            if (claimed) {
                actionWindows.remove(pending.actionId)?.selections?.clear()
            }
            claimed
        }
    }

    internal fun claimSynchronizationTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean {
        beforeSynchronizationTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            if (!pending.future.completeExceptionally(cause)) return@synchronized false
            owner.fail(cause)
        }
    }

    internal fun completeSynchronization(pending: GameActionBridge.PendingAction): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            pending.future.complete(GameActionBridge.ActionSubmission.RuntimeToken(GameActionBridge.ENGINE_PASS_TOKEN))
        }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun actionOffersForTest(actionId: String): List<GameActionBridge.ActionOffer> =
        synchronized(owner.feedLock) {
            actionWindows[actionId]
                ?.offers
                ?.values
                ?.flatten()
                ?.map { it.second }
                .orEmpty()
        }

    fun legalAttackerIds(actionId: String): List<Int> = synchronized(owner.feedLock) { actionWindows[actionId]?.legalAttackerIds.orEmpty() }

    fun hasLegalAttackers(actionId: String): Boolean =
        synchronized(owner.feedLock) {
            actionWindows[actionId]?.combat?.hasLegalAttackers() == true
        }

    fun legalBlockerCount(actionId: String): Int = synchronized(owner.feedLock) { actionWindows[actionId]?.legalBlockerCount ?: 0 }

    fun complete(
        claim: ActionClaim,
        childToken: Long? = null,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            completeDeferredLocked(claim, childToken)
        }

    private fun completeDeferredLocked(
        claim: ActionClaim,
        childToken: Long?,
    ): Boolean {
        val window = actionWindows[claim.actionId] ?: return false
        if (window.status != ActionWindowStatus.Claimed(claim.kind, claim.token)) return false
        val completionToken = childToken ?: claim.token
        if (childToken != null) {
            val selection = window.deferredChildSelections[childToken] ?: return false
            window.selections[childToken] = selection
        }
        val completed = owner.bridge.actionBridge(window.seatId).submitRuntimeToken(claim.actionId, completionToken)
        if (completed) window.status = ActionWindowStatus.Completed
        return completed
    }

    @Suppress("UNUSED_PARAMETER")
    fun failClaim(
        claim: ActionClaim,
        cause: Throwable,
    ): Nothing =
        synchronized(owner.feedLock) {
            owner.fail(cause)
        }

    fun reopenClaim(claim: ActionClaim): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[claim.actionId] ?: return false
                    if (window.status != ActionWindowStatus.Claimed(claim.kind, claim.token)) return false
                    owner.bridge.actionBridge(window.seatId).exactPending(claim.actionId) ?: return false
                    val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                    val feed = owner.feed(window.seatId)
                    val prepared =
                        try {
                            feed.builder.preparePhaseTransitionDiff(game, owner.counter, window.actions)
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    publishPresentation(feed, window, prepared)
                    val reopened = checkNotNull(actionWindows[claim.actionId])
                    reopened.selections.clear()
                    reopened.status = ActionWindowStatus.Published
                    true
                }
            }
        }

    fun updateDeclaration(
        actionId: String,
        responseGameStateId: Int,
        answer: DeclarationAnswer,
    ): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[actionId] ?: return false
                    val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
                    if (pending.actionId != actionId ||
                        window.status != ActionWindowStatus.Published ||
                        responseGameStateId != window.promptGameStateId
                    ) {
                        return false
                    }
                    val combat = window.combat ?: return false
                    when (pending.state.kind) {
                        PendingActionKind.DECLARE_ATTACKERS -> {
                            val attackers = answer as? DeclarationAnswer.Attackers ?: return false
                            val next = combat.nextAttackers(attackers) ?: return false
                            combat.replaceAttackers(next)
                            check(publishDeclarationPresentation(window, PendingActionKind.DECLARE_ATTACKERS))
                        }
                        PendingActionKind.DECLARE_BLOCKERS -> {
                            val blockers = answer as? DeclarationAnswer.Blockers ?: return false
                            val next = combat.nextBlockers(blockers) ?: return false
                            combat.replaceBlockers(next)
                            check(publishDeclarationPresentation(window, PendingActionKind.DECLARE_BLOCKERS))
                        }
                        PendingActionKind.PRIORITY,
                        PendingActionKind.SYNC_ONLY,
                        -> return false
                    }
                    true
                }
            }
        }

    fun republishDeclaration(actionId: String): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    val window = actionWindows[actionId] ?: return false
                    val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
                    if (pending.actionId != actionId ||
                        pending.state.kind !in setOf(PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS) ||
                        window.status != ActionWindowStatus.Published ||
                        window.combat == null
                    ) {
                        return false
                    }
                    publishDeclarationPresentation(window, pending.state.kind)
                }
            }
        }

    private fun publishDeclarationPresentation(
        window: RuntimeActionWindow,
        kind: PendingActionKind,
    ): Boolean {
        val combat = window.combat ?: return false
        val feed = owner.feed(window.seatId)
        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
        val prepared =
            try {
                when (kind) {
                    PendingActionKind.DECLARE_ATTACKERS ->
                        feed.builder.prepareEchoAttackers(
                            game,
                            owner.counter,
                            combat.selectedAttackerInstanceIds(),
                            window.legalAttackerIds,
                            combat.selectedAttackAlternatives(),
                            combat.selectedDamageRecipients(),
                            window.presentationActions,
                        )
                    PendingActionKind.DECLARE_BLOCKERS ->
                        feed.builder.prepareEchoBlockers(game, owner.counter, combat.selectedBlockAssignments(), window.presentationActions)
                    PendingActionKind.PRIORITY,
                    PendingActionKind.SYNC_ONLY,
                    -> return false
                }
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        publishPresentation(feed, window, prepared)
        return true
    }

    fun bindInitial(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: error("No action window $actionId")
            val feed = owner.feed(window.seatId)
            if (window.publishedBatch.isNotEmpty()) {
                check(owner.removeOwnedBatch(feed, window.publishedBatch)) { "Action window $actionId is already visible" }
            } else {
                check(window.status == ActionWindowStatus.Published) { "Action window $actionId is no longer pending" }
                check(owner.bridge.actionBridge(window.seatId).exactPending(actionId) != null) {
                    "Action window $actionId is no longer pending"
                }
            }
            actionWindows[actionId] = window.copy(promptGameStateId = gameStateId, publishedBatch = emptyList())
            window.actions
        }

    fun replaceWithPhaseTransition(
        actionId: String,
        includePriorityPrompt: Boolean = true,
    ): List<GREToClientMessage> =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[actionId] ?: error("No action window $actionId")
                    val feed = owner.feed(window.seatId)
                    check(feed.queue.any { it === window.publishedBatch }) { "Action window $actionId is already visible" }
                    val game = owner.bridge.getGame() ?: error("Game unavailable")
                    val prepared =
                        try {
                            feed.builder.preparePhaseTransitionDiff(
                                game,
                                owner.counter,
                                window.actions,
                                includePriorityPrompt,
                            )
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    publishPresentation(feed, window, prepared, removePrevious = true)
                }
            }
        }

    /** Preserve an undrained priority window's state frame without exposing its response prompt. */
    fun suppressPriorityPresentation(actionId: String): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: return false
            val pending = owner.bridge.actionBridge(window.seatId).exactPending(actionId) ?: return false
            if (pending.state.kind != PendingActionKind.PRIORITY || window.status != ActionWindowStatus.Published) return false
            val feed = owner.feed(window.seatId)
            if (!owner.removeOwnedBatch(feed, window.publishedBatch)) return false
            val stateOnly =
                window.publishedBatch.mapNotNull { message ->
                    when {
                        message.hasActionsAvailableReq() -> null
                        message.hasGameStateMessage() ->
                            message
                                .toBuilder()
                                .setGameStateMessage(
                                    message.gameStateMessage
                                        .toBuilder()
                                        .clearActions()
                                        .setPendingMessageCount(0),
                                ).build()
                        else -> message
                    }
                }
            feed.queue.add(stateOnly)
            owner.signalDelivery()
            actionWindows[actionId] = window.copy(publishedBatch = stateOnly)
            true
        }

    fun claimPriority(
        actionId: String,
        responseGameStateId: Int,
        response: Action,
        defer: Boolean,
    ): PriorityResponseClaim? =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[actionId] ?: return null
                    val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return null
                    if (pending.actionId != actionId || pending.future.isDone || window.status != ActionWindowStatus.Published) return null
                    val (token, offer) = window.resolveOfferedSelection(responseGameStateId, response) ?: return null
                    window.selections[token] = RuntimeActionSelection(offer, response)
                    val kind = if (defer) ActionClaimKind.Deferred else ActionClaimKind.Immediate
                    window.status = ActionWindowStatus.Claimed(kind, token)
                    if (owner.bridge.engineSettings.timer) {
                        val timer = owner.feed(window.seatId).builder.timerStop(owner.counter)
                        owner.feed(window.seatId).queue.add(timer.messages)
                        owner.signalDelivery()
                    }
                    PriorityResponseClaim(offer.sourceCardId(), ActionClaim(actionId, token, kind, window.deferredCostPlans[token]))
                }
            }
        }

    fun submitDeclaration(
        actionId: String,
        responseGameStateId: Int,
        confirmation: (() -> GREToClientMessage)? = null,
    ): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[actionId] ?: return false
                    val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
                    if (pending.actionId != actionId ||
                        pending.state.kind !in setOf(PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS) ||
                        window.status != ActionWindowStatus.Published ||
                        responseGameStateId != window.promptGameStateId
                    ) {
                        return false
                    }
                    val action = window.combat?.resolveDeclaration(pending.state.kind) ?: return false
                    val confirmationMessage =
                        try {
                            confirmation?.invoke()
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    val token = nextActionToken++
                    window.status = ActionWindowStatus.Claimed(ActionClaimKind.Immediate, token)
                    window.selections[token] =
                        RuntimeActionSelection(
                            GameActionBridge.ActionOffer(Action.getDefaultInstance(), action),
                            Action.getDefaultInstance(),
                        )
                    confirmationMessage?.let {
                        owner.feed(window.seatId).queue.add(listOf(it))
                        owner.signalDelivery()
                    }
                    val completed = owner.bridge.actionBridge(window.seatId).submitRuntimeToken(actionId, token)
                    if (completed) window.status = ActionWindowStatus.Completed
                    completed
                }
            }
        }

    fun terminate() {
        synchronized(owner.feedLock) {
            actionWindows.values.forEach { it.selections.clear() }
            actionWindows.clear()
        }
    }

    fun reset() {
        terminate()
        nextActionToken = 1L
    }

    internal fun publish(
        seatId: SeatId,
        pending: GameActionBridge.PendingAction,
    ) {
        if (pending.state.kind == PendingActionKind.SYNC_ONLY) {
            owner.syncOnly.publish(seatId, pending)
            return
        }
        owner.beforePublicationLock?.invoke()
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                    val feed = owner.feed(seatId)
                    val tokenBefore = nextActionToken
                    val prepared =
                        try {
                            when (pending.state.kind) {
                                PendingActionKind.PRIORITY ->
                                    feed.builder.preparePostAction(game, owner.counter, priorityCandidates = pending.priorityCandidates)
                                PendingActionKind.SYNC_ONLY -> error("Synchronization windows have no client presentation")
                                PendingActionKind.DECLARE_ATTACKERS -> feed.builder.prepareDeclareAttackers(game, owner.counter)
                                PendingActionKind.DECLARE_BLOCKERS -> feed.builder.prepareDeclareBlockers(game, owner.counter)
                            }
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    val result = prepared.bundle
                    val messages =
                        try {
                            if (pending.state.kind == PendingActionKind.PRIORITY && owner.bridge.engineSettings.timer) {
                                result.messages + feed.builder.timerStart(owner.counter).messages
                            } else {
                                result.messages
                            }
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    val promptGsId = result.actionGameStateId ?: result.messages.firstOrNull()?.gameStateId ?: owner.counter.currentGsId()
                    if (hasAmbiguousActionCatalog(result.actionOffers)) {
                        owner.fail(IllegalStateException("Ambiguous action offer catalog"))
                    }
                    val created =
                        try {
                            createRuntimeActionWindow(
                                seatId,
                                pending,
                                prepared,
                                messages,
                                promptGsId,
                                nextToken = { nextActionToken++ },
                                materializeDeferredCost = { _, offer ->
                                    DeferredCastCostPlanMaterializer.materialize(owner.bridge, offer) { nextActionToken++ }
                                },
                            ).copy(combat = RuntimeCombatWindow.capture(owner, game, messages))
                        } catch (ex: Exception) {
                            nextActionToken = tokenBefore
                            owner.fail(ex)
                        }
                    try {
                        beforeCatalogInstall?.invoke()
                    } catch (ex: Exception) {
                        nextActionToken = tokenBefore
                        owner.fail(ex)
                    }
                    owner.cutInstaller.install(
                        feed,
                        PreparedCut(messages, prepared.transition, prepared.closesPlaybackFrame),
                        CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall, afterInstall = afterInstall),
                        onRollback = { nextActionToken = tokenBefore },
                    ) { ex -> owner.fail(ex) }
                    actionWindows.remove(pending.actionId)?.selections?.clear()
                    actionWindows[pending.actionId] = created
                    feed.requestedCut = null
                    beforePublished?.invoke()
                    created.status = ActionWindowStatus.Published
                }
            }
        }
    }

    private fun publishPresentation(
        feed: MatchCutCoordinator.ViewerFeed,
        window: RuntimeActionWindow,
        prepared: BundleBuilder.ActionWindowPrepared,
        removePrevious: Boolean = false,
    ): List<GREToClientMessage> {
        val messages = prepared.bundle.messages
        val promptGsId = checkNotNull(prepared.bundle.actionGameStateId)
        owner.cutInstaller.install(
            feed,
            PreparedCut(messages, prepared.transition, prepared.closesPlaybackFrame),
            CutInstallHooks(
                beforeEnqueue = beforeEnqueue,
                beforeInstall = beforeInstall,
                afterInstall = {
                    if (removePrevious) {
                        check(owner.removeOwnedBatch(feed, window.publishedBatch)) { "Action window ${window.actionId} became visible" }
                    }
                    afterInstall?.invoke()
                },
            ),
        ) { ex -> owner.fail(ex) }
        actionWindows[window.actionId] = window.copy(promptGameStateId = promptGsId, publishedBatch = messages)
        return messages
    }

    internal fun resolve(
        pending: GameActionBridge.PendingAction,
        submission: GameActionBridge.ActionSubmission.RuntimeToken,
    ): PlayerAction? =
        synchronized(owner.feedLock) {
            if (submission.token == GameActionBridge.ENGINE_PASS_TOKEN) return PlayerAction.PassPriority
            val selection = actionWindows[pending.actionId]?.selections?.remove(submission.token) ?: return null
            val command = selection.offer.command
            if (command is PlayerAction.CastSpell) owner.bridge.setSelectedSpellGrpId(command.cardId, selection.offer.spellGrpId)
            if (command is PlayerAction.ActivateMana) command.copy(selectedColor = selectedManaColor(selection.response)) else command
        }

    internal fun close(actionId: String) {
        synchronized(owner.feedLock) {
            owner.deferredCast.close(actionId)
            actionWindows.remove(actionId)?.selections?.clear()
        }
    }
}
