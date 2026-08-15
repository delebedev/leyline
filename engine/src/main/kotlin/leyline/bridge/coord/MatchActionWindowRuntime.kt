package leyline.bridge.coord

import forge.game.Game
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Runtime action catalogs and exact presentation ownership beneath [MatchCutCoordinator]. */
internal class MatchActionWindowRuntime(
    private val owner: MatchCutCoordinator,
) {
    private val actionWindows = mutableMapOf<String, RuntimeActionWindow>()
    private val declaredActions = mutableMapOf<String, PlayerAction>()
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

    internal fun claimTimeout(
        pending: GameActionBridge.PendingAction,
        cause: java.util.concurrent.TimeoutException,
    ): Boolean {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            val window = actionWindows[pending.actionId]
            if (window?.status != ActionWindowStatus.Published) return@synchronized false
            val claimed = pending.future.completeExceptionally(cause)
            if (claimed) {
                declaredActions.remove(pending.actionId)
                actionWindows.remove(pending.actionId)?.selections?.clear()
            }
            claimed
        }
    }

    internal fun claimSynchronizationTimeout(
        pending: GameActionBridge.PendingAction,
        cause: java.util.concurrent.TimeoutException,
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
            pending.future.complete(GameActionBridge.ActionSubmission.LegacyRuntimeAction(PlayerAction.PassPriority))
        }

    fun legalAttackerIds(actionId: String): List<Int> = synchronized(owner.feedLock) { actionWindows[actionId]?.legalAttackerIds.orEmpty() }

    fun legalBlockerCount(actionId: String): Int = synchronized(owner.feedLock) { actionWindows[actionId]?.legalBlockerCount ?: 0 }

    fun hasMeaningfulAction(actionId: String): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            actionWindows[actionId]?.actions?.let { !BundleBuilder.shouldAutoPass(it) } ?: false
        }

    fun complete(
        claim: ActionClaim,
        childToken: Long? = null,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[claim.actionId] ?: return false
            if (window.status != ActionWindowStatus.Claimed(claim.kind, claim.token)) return false
            val completionToken = childToken ?: claim.token
            if (childToken != null) {
                val selection = window.deferredChildSelections[childToken] ?: return false
                window.selections[childToken] = selection
            }
            val completed = owner.bridge.actionBridge(window.seatId).submitRuntimeToken(claim.actionId, completionToken)
            if (completed) window.status = ActionWindowStatus.Completed
            completed
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
                    val pending = owner.bridge.actionBridge(window.seatId).exactPending(claim.actionId) ?: return false
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
                    pending.claimed = false
                    true
                }
            }
        }

    fun updateAttackers(
        actionId: String,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int>,
        selectedDamageRecipients: Map<Int, DamageRecipient>,
    ) = updatePresentation(actionId, PendingActionKind.DECLARE_ATTACKERS) { feed, game, window ->
        feed.builder.prepareEchoAttackers(
            game,
            owner.counter,
            selectedAttackerIds,
            allLegalAttackerIds,
            selectedAttackAlternatives,
            selectedDamageRecipients,
            window.presentationActions,
        )
    }

    fun updateBlockers(
        actionId: String,
        blockAssignments: Map<Int, Int>,
    ) = updatePresentation(actionId, PendingActionKind.DECLARE_BLOCKERS) { feed, game, window ->
        feed.builder.prepareEchoBlockers(game, owner.counter, blockAssignments, window.presentationActions)
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
            owner.bridge
                .actionBridge(window.seatId)
                .getPending()
                ?.takeIf { it.actionId == actionId }
                ?.promptGameStateId = gameStateId
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
                    pending.claimed = true
                    if (owner.bridge.matchConfig.game.timer) {
                        val timer = owner.feed(window.seatId).builder.timerStop(owner.counter)
                        owner.feed(window.seatId).queue.add(timer.messages)
                    }
                    PriorityResponseClaim(offer.sourceCardId(), ActionClaim(actionId, token, kind, window.deferredCostPlans[token]))
                }
            }
        }

    fun submitDeclaration(
        actionId: String,
        responseGameStateId: Int,
        answer: DeclarationAnswer,
        confirmation: (() -> GREToClientMessage)? = null,
    ): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val window = actionWindows[actionId] ?: return false
                    if (responseGameStateId != 0 && responseGameStateId != window.promptGameStateId) return false
                    val action =
                        when (answer) {
                            is DeclarationAnswer.Attackers ->
                                PlayerAction.DeclareAttackers(
                                    answer.attackerIds,
                                    answer.attackAlternativeByAttacker,
                                    answer.defender,
                                    answer.defenderByAttacker,
                                )
                            is DeclarationAnswer.Blockers -> PlayerAction.DeclareBlockers(answer.blockAssignments)
                        }
                    val expected =
                        if (answer is DeclarationAnswer.Attackers) {
                            PendingActionKind.DECLARE_ATTACKERS
                        } else {
                            PendingActionKind.DECLARE_BLOCKERS
                        }
                    val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
                    if (pending.actionId != actionId ||
                        pending.state.kind != expected ||
                        window.status != ActionWindowStatus.Published
                    ) {
                        return false
                    }
                    val confirmationMessage =
                        try {
                            confirmation?.invoke()
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    val token = nextActionToken++
                    pending.claimed = true
                    declaredActions[actionId] = action
                    window.selections[token] =
                        RuntimeActionSelection(
                            GameActionBridge.ActionOffer(Action.getDefaultInstance(), action),
                            Action.getDefaultInstance(),
                        )
                    confirmationMessage?.let { owner.feed(window.seatId).queue.add(listOf(it)) }
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
            declaredActions.clear()
        }
    }

    fun reset() {
        terminate()
        nextActionToken = 1L
    }

    private fun updatePresentation(
        actionId: String,
        expectedKind: PendingActionKind,
        build: (MatchCutCoordinator.ViewerFeed, Game, RuntimeActionWindow) -> BundleBuilder.ActionWindowPrepared,
    ) {
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    val window = actionWindows[actionId] ?: error("No action window $actionId")
                    val pending = owner.bridge.actionBridge(window.seatId).getPending()
                    check(pending?.actionId == actionId && pending.state.kind == expectedKind) {
                        "Action window $actionId is not $expectedKind"
                    }
                    val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                    val feed = owner.feed(window.seatId)
                    val prepared =
                        try {
                            build(feed, game, window)
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    publishPresentation(feed, window, prepared)
                }
            }
        }
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
                            if (pending.state.kind == PendingActionKind.PRIORITY && owner.bridge.matchConfig.game.timer) {
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
                            )
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
                    try {
                        beforeEnqueue?.invoke()
                        feed.queue.add(messages)
                    } catch (ex: Exception) {
                        nextActionToken = tokenBefore
                        owner.fail(ex)
                    }
                    var installed = false
                    try {
                        beforeInstall?.invoke()
                        prepared.transition?.let { transition -> owner.bridge.commitProjection(transition) { installed = true } }
                            ?: run { installed = true }
                        afterInstall?.invoke()
                        if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(seatId)
                    } catch (ex: Exception) {
                        if (!installed) {
                            owner.removeOwnedBatch(feed, messages)
                            nextActionToken = tokenBefore
                        }
                        owner.fail(ex)
                    }
                    actionWindows.remove(pending.actionId)?.selections?.clear()
                    actionWindows[pending.actionId] = created
                    pending.promptGameStateId = promptGsId
                    feed.requestedCut = null
                    beforePublished?.invoke()
                    pending.published = true
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
        var enqueued = false
        var installed = false
        try {
            beforeEnqueue?.invoke()
            feed.queue.add(messages)
            enqueued = true
            beforeInstall?.invoke()
            prepared.transition?.let { transition -> owner.bridge.commitProjection(transition) { installed = true } }
                ?: run { installed = true }
            if (removePrevious) {
                check(owner.removeOwnedBatch(feed, window.publishedBatch)) { "Action window ${window.actionId} became visible" }
            }
            afterInstall?.invoke()
            if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(feed.seatId)
        } catch (ex: Exception) {
            if (enqueued && !installed) {
                owner.removeOwnedBatch(feed, messages)
            }
            owner.fail(ex)
        }
        actionWindows[window.actionId] = window.copy(promptGameStateId = promptGsId, publishedBatch = messages)
        owner.bridge
            .actionBridge(window.seatId)
            .exactPending(window.actionId)
            ?.takeIf { it.actionId == window.actionId }
            ?.promptGameStateId = promptGsId
        return messages
    }

    internal fun resolve(
        pending: GameActionBridge.PendingAction,
        submission: GameActionBridge.ActionSubmission.RuntimeToken,
    ): PlayerAction? =
        synchronized(owner.feedLock) {
            declaredActions.remove(pending.actionId)?.let { return it }
            val selection = actionWindows[pending.actionId]?.selections?.remove(submission.token) ?: return null
            val command = selection.offer.command
            if (command is PlayerAction.CastSpell) owner.bridge.setSelectedSpellGrpId(command.cardId, selection.offer.spellGrpId)
            if (command is PlayerAction.ActivateMana) command.copy(selectedColor = selectedManaColor(selection.response)) else command
        }

    internal fun close(actionId: String) {
        synchronized(owner.feedLock) {
            declaredActions.remove(actionId)
            actionWindows.remove(actionId)?.selections?.clear()
        }
    }
}
