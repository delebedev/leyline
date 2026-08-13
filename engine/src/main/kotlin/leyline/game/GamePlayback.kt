package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.event.*
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.combatDamageFact
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Requests and delivers per-action GRE state diffs for the client.
 *
 * EventBus callbacks only aggregate a [PlaybackCutRequest]. Forge's matching
 * mutation-completion hook closes one journal, materializes one immutable
 * [PendingCut], compiles its complete ordered frame plan, enqueues it immediately
 * before one install, and applies pacing. A stale exact-revision install is
 * terminal. Any failure after journal close becomes a durable
 * [PlaybackTerminalFailure] and stops progress.
 *
 * Uses the shared [leyline.game.bundle.MessageCounter] for protocol sequencing. Frame
 * production follows one lock order across engine and session domains: [counter], then
 * [leyline.game.state.GameBridge.projectionBuildLock], then [queueLock]. Individual counter
 * operations remain atomic; the monitor serializes allocation of a complete frame batch.
 *
 * Shares [leyline.game.state.ProjectionState] with the session-layer
 * `BundleBuilder`, including one common diff baseline.
 *
 * The [MatchHandler][leyline.match.MatchHandler] drains the queue
 * via [drainQueue] and sends messages to the TCP socket.
 *
 * @param bridge the GameBridge for state mapping and zone tracking
 * @param matchId match identifier for GRE messages
 * @param seatId the human player's seat (messages are from their perspective)
 * @param counter shared protocol counter (same instance used by MatchSession)
 */
class GamePlayback(
    private val bridge: GameBridge,
    private val matchId: String,
    private val seatId: Int,
    private val counter: MessageCounter,
    /** Delay multiplier (1.0 = default, 0.5 = 2x speed, 0 = instant). Derived from config ai.speed. */
    private val delayMultiplier: Double = 1.0,
    /** Spectator playback captures both seats because every turn is remote to the viewer. */
    private val captureLocalActions: Boolean = false,
) : IGameEventVisitor.Base<Unit>() {
    private val bundleBuilder = BundleBuilder(bridge, matchId, seatId)

    private val log = LoggerFactory.getLogger(GamePlayback::class.java)

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    /** Thread-safe queue of GRE message batches for the handler to drain. */
    private val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()

    @VisibleForTesting
    internal var beforeBatchEnqueue: ((Int, List<GREToClientMessage>) -> Unit)? = null

    /** Playback callbacks aggregate until Forge reaches their matching completion boundary. */
    private var requestedCut: PlaybackCutRequest? = null

    @Volatile
    private var terminalFailure: PlaybackTerminalFailure? = null

    @Volatile
    private var pendingCut: PendingCut? = null

    /**
     * Guards the build-and-enqueue window. Producers acquire this only after the shared
     * message counter and projection-build monitors. Drains acquire this monitor alone.
     */
    private val queueLock = Any()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        requestCut(PlaybackCutReason.LandPlayed, LAND_DELAY)
    }

    /** Local stack objects seen at their cast/enter event and awaiting a
     * matching resolve event. Independent of GameEventCollector's trigger maps:
     * collector and playback are separate EventBus subscribers. */
    private val pendingLocalTriggers = ConcurrentHashMap<LocalStackKey, Int>()
    private val pendingLocalAbilities = ConcurrentHashMap<LocalStackKey, Int>()
    private val pendingLocalCasts = ConcurrentHashMap<LocalStackKey, Int>()

    override fun visit(ev: GameEventSpellAbilityCast) {
        val isTrigger = ev.si()?.isTrigger == true
        val isAbility = ev.si()?.isAbility == true
        val key = localStackKey(ev.sa()?.id ?: 0, ev.sa()?.hostCard?.id)
        val remoteActing = isRemoteActing()

        if (!remoteActing) {
            when {
                isTrigger -> {
                    if (key != null) markPending(pendingLocalTriggers, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
                isAbility -> {
                    if (key != null) markPending(pendingLocalAbilities, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
                !isAbility -> {
                    if (key != null) markPending(pendingLocalCasts, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
            }
            return
        }

        requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val key = localStackKey(ev.spell()?.id ?: 0, ev.spell()?.hostCard?.id)
        val splitLocalStackObject =
            key != null &&
                (
                    consumePending(pendingLocalTriggers, key) ||
                        consumePending(pendingLocalAbilities, key) ||
                        consumePending(pendingLocalCasts, key)
                )
        if (!isRemoteActing() && !splitLocalStackObject) return
        requestCut(PlaybackCutReason.StackObjectResolved, RESOLVE_DELAY)
    }

    override fun visit(ev: GameEventCardChangeZone) {
        if (ev.from()?.zoneType != ZoneType.Stack) return
        requestCut(PlaybackCutReason.ResolutionZoneCompleted, 0)
    }

    override fun visit(ev: GameEventCardCounters) {
        if (isRemoteActing()) return
        requestCut(PlaybackCutReason.CountersChanged, COUNTER_DELAY)
    }

    override fun visit(ev: GameEventPlayerPoisoned) {
        if (isRemoteActing()) return
        requestCut(PlaybackCutReason.PoisonChanged, COUNTER_DELAY)
    }

    override fun visit(ev: GameEventTurnBegan) {
        if (!isRemoteActing()) return
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnBegan during teardown (game null), dropping event")
                return
            }
        lastCapturedTurn = game.phaseHandler.turn
        lastCapturedPhase = game.phaseHandler.phase
        requestCut(PlaybackCutReason.TurnBegan, PHASE_DELAY, turnStarted = true)
    }

    override fun visit(ev: GameEventTurnPhase) {
        if (!isRemoteActing()) return
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnPhase during teardown (game null), dropping event")
                return
            }
        val turn = game.phaseHandler.turn
        val phase = game.phaseHandler.phase
        // Skip if TurnBegan already captured this exact turn+phase
        if (turn == lastCapturedTurn && phase == lastCapturedPhase) return
        lastCapturedTurn = turn
        lastCapturedPhase = phase
        val delay =
            when (ev.phase()) {
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.COMBAT_END,
                -> COMBAT_DELAY
                else -> PHASE_DELAY
            }
        requestCut(PlaybackCutReason.PhaseChanged, delay)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        // Capture for BOTH local and remote attackers. The client expects a
        // combat-state diff (tapped creatures + attackState=Attacking) after
        // attackers are declared regardless of whose turn it is. Without this,
        // the human-seat auto-pass loop overshoots past combat before building
        // a diff, and the client never sees attackers tapped (leyline-o2q).
        if (isRemoteActing()) {
            requestCut(PlaybackCutReason.AttackersDeclared, COMBAT_DELAY, boundary = PlaybackCutBoundary.AttackersDeclared)
        } else {
            requestCut(PlaybackCutReason.AttackersDeclared, 0, boundary = PlaybackCutBoundary.AttackersDeclared)
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        requestCut(PlaybackCutReason.BlockersDeclared, COMBAT_DELAY, boundary = PlaybackCutBoundary.BlockersDeclared)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        if (isRemoteActing()) return
        requestCut(PlaybackCutReason.CombatEnded, 0, boundary = PlaybackCutBoundary.CombatEnded)
    }

    /** Forge main-loop completion entry point. Event visitors do no projection work. */
    fun onMainLoopStepCompleted() {
        terminalFailure?.let { throw it }
        flushRequestedCut(PlaybackCutBoundary.MainLoopStep)
    }

    fun onAttackersDeclaredCompleted() = flushRequestedCut(PlaybackCutBoundary.AttackersDeclared)

    fun onBlockersDeclaredCompleted() = flushRequestedCut(PlaybackCutBoundary.BlockersDeclared)

    fun onCombatEndedCompleted() = flushRequestedCut(PlaybackCutBoundary.CombatEnded)

    /**
     * Establishes the ownership boundary between setup/mulligan state and ordinary
     * playback before Forge enters its first main-loop step.
     */
    fun onMainGameLoopStarted() {
        terminalFailure?.let { throw it }
        synchronized(queueLock) {
            bridge.closeBundleFrame(seatId)
            requestedCut = null
        }
    }

    /** A successfully installed shell frame subsumes the pending request for its journal. */
    internal fun onFrameCommitted() {
        synchronized(queueLock) { requestedCut = null }
    }

    // -- Queue access (called from MatchHandler / Netty thread) --

    /** Drain all queued message batches. Returns empty list if nothing queued. */
    fun drainQueue(): List<List<GREToClientMessage>> =
        synchronized(queueLock) {
            buildList {
                while (true) {
                    add(queue.poll() ?: break)
                }
            }
        }

    /** Drain queued batches that must precede the caller's next outbound message. */
    fun drainQueueBeforeMsgId(
        msgId: Int,
        maxGsId: Int = 0,
    ): List<List<GREToClientMessage>> =
        synchronized(queueLock) {
            buildList {
                while (true) {
                    val batch = queue.peek() ?: break
                    val firstMsgId = batch.firstOrNull()?.msgId ?: Int.MAX_VALUE
                    val firstGsId = batch.firstGameStateId()
                    if (maxGsId != 0 && firstGsId != null) {
                        if (firstGsId >= maxGsId) break
                    } else if (firstMsgId >= msgId) {
                        break
                    }
                    add(queue.poll() ?: break)
                }
            }
        }

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = synchronized(queueLock) { queue.isNotEmpty() }

    internal fun failure(): PlaybackTerminalFailure? = terminalFailure

    // -- Internal --

    private fun requestCut(
        reason: PlaybackCutReason,
        delayMs: Int,
        turnStarted: Boolean = false,
        boundary: PlaybackCutBoundary = PlaybackCutBoundary.MainLoopStep,
    ) {
        terminalFailure?.let { throw it }
        synchronized(queueLock) {
            val next = PlaybackCutRequest(reason, delayMs, turnStarted, boundary)
            requestedCut = requestedCut?.aggregate(next) ?: next
        }
    }

    private fun flushRequestedCut(boundary: PlaybackCutBoundary) {
        terminalFailure?.let { throw it }
        val game = bridge.getGame()
        val request =
            synchronized(counter) {
                synchronized(bridge.projectionBuildLock) {
                    synchronized(queueLock) {
                        val request = requestedCut ?: return
                        if (request.boundary != boundary) return
                        if (game == null) {
                            failTerminal(
                                null,
                                MaterializationDiagnostic(request, null),
                                IllegalStateException("Game unavailable"),
                            )
                        }
                        val events =
                            try {
                                bridge.closeBundleFrame(seatId)
                            } catch (ex: Exception) {
                                failTerminal(null, MaterializationDiagnostic(request, null), ex)
                            }
                        val pending =
                            try {
                                PendingCut(
                                    request,
                                    bundleBuilder.materializePlaybackCut(
                                        game,
                                        counter,
                                        playbackFrameSpecs(game, request, events),
                                    ),
                                )
                            } catch (ex: Exception) {
                                failTerminal(null, MaterializationDiagnostic(request, events), ex)
                            }
                        pendingCut = pending
                        val prepared =
                            try {
                                bundleBuilder.compilePlaybackCut(pending.projection)
                            } catch (ex: Exception) {
                                failTerminal(pending, null, ex)
                            }
                        val enqueued = mutableListOf<List<GREToClientMessage>>()
                        try {
                            for ((index, batch) in prepared.batches.withIndex()) {
                                beforeBatchEnqueue?.invoke(index, batch)
                                queue.add(batch)
                                enqueued += batch
                            }
                        } catch (ex: Exception) {
                            removeEnqueuedBatches(enqueued)
                            failTerminal(pending, null, ex)
                        }
                        var installed = false
                        try {
                            bridge.commitProjection(prepared.transition) { installed = true }
                            pendingCut = null
                            requestedCut = null
                            if (bridge.consumePromptTimeoutNeedsAutoAdvance()) {
                                bridge.autoAdvanceRequester?.invoke("prompt timeout playback queued")
                            }
                        } catch (ex: Exception) {
                            if (!installed) removeEnqueuedBatches(enqueued)
                            failTerminal(pending, null, ex)
                        }
                        request
                    }
                }
            }
        pace(request.delayMs)
    }

    private fun removeEnqueuedBatches(enqueued: List<List<GREToClientMessage>>) {
        for (batch in enqueued) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() === batch) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    private fun playbackFrameSpecs(
        game: forge.game.Game,
        request: PlaybackCutRequest,
        events: FrameEventLog,
    ): List<BundleBuilder.PlaybackFrameSpec> {
        if (!events.events.shouldSplitCombatDamageWindow()) {
            return listOf(BundleBuilder.PlaybackFrameSpec(events, turnStarted = request.turnStarted))
        }
        val damageFrames = events.events.combatDamageFrames(game)
        val endCombatEvents =
            events.events.filter { event ->
                event is LeylineGameEvent.PhaseChanged && event.step == Step.EndCombat_a2cb.number
            }
        return buildList {
            damageFrames.forEach { frame ->
                add(
                    BundleBuilder.PlaybackFrameSpec(
                        events = FrameEventLog(frame.events),
                        lifeTotals = frame.lifeTotals,
                    ),
                )
            }
            if (endCombatEvents.isNotEmpty()) {
                add(BundleBuilder.PlaybackFrameSpec(FrameEventLog(endCombatEvents)))
            }
        }
    }

    private fun terminate(
        pending: PendingCut?,
        diagnostic: MaterializationDiagnostic?,
        cause: Throwable,
    ): PlaybackTerminalFailure =
        PlaybackTerminalFailure(pending, diagnostic, cause).also {
            pendingCut = pending
            terminalFailure = it
        }

    private fun failTerminal(
        pending: PendingCut?,
        diagnostic: MaterializationDiagnostic?,
        cause: Throwable,
    ): Nothing = throw terminate(pending, diagnostic, cause)

    private fun pace(delayMs: Int) {
        val adjustedDelay = (delayMs * delayMultiplier).toLong()
        if (adjustedDelay <= 0) return
        try {
            Thread.sleep(adjustedDelay)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun List<GREToClientMessage>.firstGameStateId(): Int? = firstOrNull { it.hasGameStateMessage() }?.gameStateMessage?.gameStateId

    private fun List<LeylineGameEvent>.hasCombatDamage(): Boolean = any { it.combatDamageFact() == true }

    private data class CombatDamageFrame(
        val events: List<LeylineGameEvent>,
        val lifeTotals: Map<Int, Int> = emptyMap(),
    )

    private fun List<LeylineGameEvent>.combatDamageFrames(game: forge.game.Game): List<CombatDamageFrame> {
        if (!canSafelySplitCombatDamage()) {
            return listOf(
                CombatDamageFrame(
                    filterNot { event -> event is LeylineGameEvent.PhaseChanged }
                        .prependCombatDamagePhase(game, this),
                ),
            )
        }

        val frames = mutableListOf<CombatDamageFrame>()
        var current = mutableListOf<LeylineGameEvent>()

        fun flushFrame() {
            if (current.hasCombatDamage()) {
                frames += CombatDamageFrame(current.toList(), current.lifeTotals())
            }
            current = mutableListOf()
        }

        for (event in this) {
            if (event is LeylineGameEvent.PhaseChanged) {
                if (event.isDamageStep()) {
                    if (current.hasCombatDamage()) {
                        flushFrame()
                    } else {
                        current.removeAll { pending -> pending is LeylineGameEvent.PhaseChanged && pending.isDamageStep() }
                    }
                    current += event
                }
                continue
            }
            current += event
        }

        flushFrame()
        if (frames.isNotEmpty()) return frames

        return listOf(
            CombatDamageFrame(
                filterNot { event -> event is LeylineGameEvent.PhaseChanged }
                    .prependCombatDamagePhase(game, this),
            ),
        )
    }

    private fun List<LeylineGameEvent>.canSafelySplitCombatDamage(): Boolean {
        var inDamageStep = false
        for (event in this) {
            val damageFact = event.combatDamageFact()
            if (event is LeylineGameEvent.PhaseChanged) {
                if (event.isDamageStep()) inDamageStep = true
            } else if (damageFact != null) {
                if (!damageFact || !inDamageStep) return false
                if (event is LeylineGameEvent.DamageDealtToCard) return false
            } else if (event is LeylineGameEvent.LifeChanged ||
                event == LeylineGameEvent.CombatEnded
            ) {
                if (!inDamageStep) return false
            } else {
                if (!inDamageStep && !event.isSafeBeforeDamageStep()) return false
            }
        }
        return true
    }

    private fun LeylineGameEvent.isSafeBeforeDamageStep(): Boolean =
        this is LeylineGameEvent.CardTapped ||
            this is LeylineGameEvent.AttackersDeclared ||
            this is LeylineGameEvent.BlockersDeclared

    private fun List<LeylineGameEvent>.lifeTotals(): Map<Int, Int> =
        filterIsInstance<LeylineGameEvent.LifeChanged>()
            .associate { event -> event.seatId.value to event.newLife }

    private fun LeylineGameEvent.PhaseChanged.isDamageStep(): Boolean =
        step == Step.FirstStrikeDamage_a2cb.number || step == Step.CombatDamage_a2cb.number

    private fun List<LeylineGameEvent>.prependCombatDamagePhase(
        game: forge.game.Game,
        sourceEvents: List<LeylineGameEvent>,
    ): List<LeylineGameEvent> {
        val activeSeat = combatDamageSourceSeat(sourceEvents) ?: currentTurnSeat(game) ?: seatId
        val damageStep = sourceEvents.combatDamageStep()
        return listOf(
            LeylineGameEvent.PhaseChanged(
                SeatId(activeSeat),
                Phase.Combat_a549.number,
                damageStep,
            ),
        ) + this
    }

    private fun List<LeylineGameEvent>.combatDamageStep(): Int =
        run {
            var currentDamageStep = Step.CombatDamage_a2cb.number
            for (event in this) {
                if (event is LeylineGameEvent.PhaseChanged) {
                    if (event.step == Step.FirstStrikeDamage_a2cb.number || event.step == Step.CombatDamage_a2cb.number) {
                        currentDamageStep = event.step
                    }
                }
                if (event.combatDamageFact() == true) {
                    return@run currentDamageStep
                }
            }
            Step.CombatDamage_a2cb.number
        }

    private fun combatDamageSourceSeat(events: List<LeylineGameEvent>): Int? {
        events
            .firstNotNullOfOrNull { event ->
                (event as? LeylineGameEvent.DamageDealtToPlayer)
                    ?.takeIf { it.sourceKind == DamageSourceKind.Combat }
                    ?.targetSeatId
                    ?.value
            }?.let { defenderSeat ->
                val otherSeats = bridge.gameSeatIds() - defenderSeat
                if (otherSeats.size == 1) return otherSeats.single()
                return if (defenderSeat == 1) 2 else 1
            }
        val sourceId =
            events.firstNotNullOfOrNull { event ->
                when (event) {
                    is LeylineGameEvent.DamageDealtToCard -> event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    is LeylineGameEvent.DamageDealtToPlayer -> event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    else -> null
                }
            } ?: return null
        val controller = bridge.findCard(sourceId)?.controller ?: return null
        return bridge.gameSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == controller }
    }

    private fun currentTurnSeat(game: forge.game.Game): Int? {
        val turnPlayer = game.phaseHandler.playerTurn ?: return null
        return bridge.gameSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == turnPlayer }
    }

    /**
     * True when the current turn's active player is not this playback's seat.
     * Fires for AI turns and remote-seat turns uniformly.
     */
    private fun isRemoteActing(): Boolean {
        // No log here — called from every event listener; logging would
        // duplicate the teardown messages emitted by the listeners themselves.
        val game = bridge.getGame() ?: return false
        val turnPlayer = game.phaseHandler.playerTurn ?: return false
        val myPlayer = bridge.getPlayer(SeatId(seatId)) ?: return false
        return captureLocalActions || turnPlayer != myPlayer
    }

    companion object {
        const val PHASE_DELAY = 200 // ms
        const val COMBAT_DELAY = 400
        const val CAST_DELAY = 400
        const val RESOLVE_DELAY = 400
        const val COUNTER_DELAY = 300
        const val LAND_DELAY = 300
    }

    private data class LocalStackKey(
        val abilityId: Int,
        val hostCardId: Int,
    )

    private fun localStackKey(
        abilityId: Int,
        hostCardId: Int?,
    ): LocalStackKey? {
        if (abilityId == 0 || hostCardId == null) return null
        return LocalStackKey(abilityId, hostCardId)
    }

    private fun markPending(
        pending: ConcurrentHashMap<LocalStackKey, Int>,
        key: LocalStackKey,
    ) {
        pending.merge(key, 1, Int::plus)
    }

    private fun consumePending(
        pending: ConcurrentHashMap<LocalStackKey, Int>,
        key: LocalStackKey,
    ): Boolean {
        var consumed = false
        pending.computeIfPresent(key) { _, count ->
            consumed = true
            if (count <= 1) null else count - 1
        }
        return consumed
    }
}

/** Split only source-homogeneous combat windows; mixed damage keeps causal frame ownership. */
internal fun List<LeylineGameEvent>.shouldSplitCombatDamageWindow(): Boolean {
    val damageFacts = mapNotNull { it.combatDamageFact() }
    return damageFacts.isNotEmpty() && damageFacts.all { it }
}
