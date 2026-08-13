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
 * Ordinary EventBus callbacks only aggregate a [PlaybackCutRequest]. Forge's
 * main-loop completion hook then closes one journal, materializes one immutable
 * [PendingCut], compiles it, enqueues its fixed batch immediately before install,
 * and applies pacing. A stale exact-revision install is terminal. Any failure
 * after journal close becomes a durable [PlaybackTerminalFailure] and stops progress.
 *
 * Combat declarations, damage windows, and combat end remain synchronous named
 * checkpoints because they expose mutation-complete states inside one Forge step.
 * A combat checkpoint subsumes any ordinary request for the same open journal.
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

    /** Ordinary callbacks aggregate until Forge completes the surrounding main-loop step. */
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
            captureLegacyCombatCheckpoint(COMBAT_DELAY)
        } else {
            captureLegacyCombatCheckpoint(0) // no pacing delay on own turn
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        captureLegacyCombatCheckpoint(COMBAT_DELAY)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        if (isRemoteActing()) return
        captureLegacyCombatCheckpoint(0)
    }

    /** Forge completion-hook entry point. Ordinary visitors do no projection work. */
    fun onMainLoopStepCompleted() {
        terminalFailure?.let { throw it }
        flushOrdinaryCut()
    }

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

    /** A successfully installed shell frame subsumes the ordinary request for its journal. */
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

    /**
     * Snapshot current game state as a diff, queue the GRE messages,
     * update the bridge snapshot, then sleep for animation pacing.
     *
     * Called on the engine thread -- state is frozen, safe to serialize.
     * Uses the shared [counter] — no seeding needed.
     */
    private fun captureAndPause(
        delayMs: Int,
        gameOverride: forge.game.Game? = null,
        eventsOverride: FrameEventLog? = null,
    ) {
        val game =
            gameOverride ?: bridge.getGame() ?: run {
                log.debug("GamePlayback: captureAndPause during teardown (game null), skipping")
                return
            }

        var effectiveDelay = delayMs
        try {
            val messageCount =
                synchronized(counter) {
                    synchronized(bridge.projectionBuildLock) {
                        synchronized(queueLock) {
                            val ordinary = requestedCut
                            val closedEvents = eventsOverride ?: bridge.closeBundleFrame(seatId)
                            val events = closedEvents
                            val count =
                                if (eventsOverride == null && events.events.shouldSplitCombatDamageWindow()) {
                                    captureSplitCombatDamage(game, events.events)
                                    2
                                } else {
                                    val messages = buildDiffMessages(game, ordinary?.turnStarted == true, events)
                                    queue.add(messages)
                                    if (bridge.consumePromptTimeoutNeedsAutoAdvance()) {
                                        bridge.autoAdvanceRequester?.invoke("prompt timeout playback queued")
                                    }
                                    messages.size
                                }
                            requestedCut = null
                            effectiveDelay = maxOf(delayMs, ordinary?.delayMs ?: 0)
                            count
                        }
                    }
                }

            // No need to advance the cursor here — remoteActionDiff commits the
            // projection baseline while queueLock is held. A redundant
            // buildFromSnapshot with the same gsId creates a self-referential snapshot.

            log.debug(
                "action captured: phase={} turn={} queued={} msgs={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                queue.size,
                messageCount,
            )
        } catch (ex: Exception) {
            throw terminate(
                null,
                MaterializationDiagnostic(PlaybackCutRequest(PlaybackCutReason.PhaseChanged, delayMs, false), eventsOverride),
                ex,
            )
        }

        // Pacing: sleep engine thread so client can animate
        val adjustedDelay = (effectiveDelay * delayMultiplier).toLong()
        if (adjustedDelay > 0) {
            try {
                Thread.sleep(adjustedDelay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun requestCut(
        reason: PlaybackCutReason,
        delayMs: Int,
        turnStarted: Boolean = false,
    ) {
        terminalFailure?.let { throw it }
        synchronized(queueLock) {
            val next = PlaybackCutRequest(reason, delayMs, turnStarted)
            requestedCut = requestedCut?.aggregate(next) ?: next
        }
    }

    private fun captureLegacyCombatCheckpoint(delayMs: Int) {
        terminalFailure?.let { throw it }
        captureAndPause(delayMs = delayMs)
    }

    private fun flushOrdinaryCut() {
        val game = bridge.getGame()
        val request =
            synchronized(counter) {
                synchronized(bridge.projectionBuildLock) {
                    synchronized(queueLock) {
                        val request = requestedCut ?: return
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
                                    bundleBuilder.materializePlaybackCut(game, counter, request.turnStarted, events),
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
                        try {
                            queue.add(prepared.messages)
                        } catch (ex: Exception) {
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
                            if (!installed) queue.remove(prepared.messages)
                            failTerminal(pending, null, ex)
                        }
                        request
                    }
                }
            }
        pace(request.delayMs)
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

    private fun captureSplitCombatDamage(
        game: forge.game.Game,
        events: List<LeylineGameEvent>,
    ) {
        val damageFrames = events.combatDamageFrames(game)
        val endCombatEvents =
            events.filter { event ->
                event is LeylineGameEvent.PhaseChanged && event.step == Step.EndCombat_a2cb.number
            }

        for (damageFrame in damageFrames) {
            val messages = buildDiffMessages(game, turnStarted = false, events = FrameEventLog(damageFrame.events))
            queue.add(messages.withLifeTotals(damageFrame.lifeTotals))
        }
        if (endCombatEvents.isNotEmpty()) {
            queue.add(buildDiffMessages(game, turnStarted = false, events = FrameEventLog(endCombatEvents)))
        }
    }

    private fun buildDiffMessages(
        game: forge.game.Game,
        turnStarted: Boolean,
        events: FrameEventLog,
    ): List<GREToClientMessage> =
        bundleBuilder
            .remoteActionDiff(
                game,
                counter,
                turnStarted = turnStarted,
                eventsOverride = events,
            ).messages

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

    private fun List<GREToClientMessage>.withLifeTotals(lifeTotals: Map<Int, Int>): List<GREToClientMessage> {
        if (lifeTotals.isEmpty()) return this
        return mapIndexed { index, message ->
            if (index != 0 || !message.hasGameStateMessage()) return@mapIndexed message
            val gsm = message.gameStateMessage
            val patchedPlayers =
                gsm.playersList.map { player ->
                    val life = lifeTotals[player.systemSeatNumber]
                    if (life == null) player else player.toBuilder().setLifeTotal(life).build()
                }
            message
                .toBuilder()
                .setGameStateMessage(
                    gsm
                        .toBuilder()
                        .clearPlayers()
                        .addAllPlayers(patchedPlayers)
                        .build(),
                ).build()
        }
    }

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
