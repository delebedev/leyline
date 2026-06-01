package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.event.*
import forge.game.phase.PhaseType
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.event.FrameEventLog
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Captures per-action GRE state diffs for the client, pacing remote turns
 * by sleeping the game thread at key events.
 *
 * Subscribes to the engine's Guava EventBus. Events fire synchronously on
 * the game thread -- sleeping here freezes engine progress and state, making
 * it safe to snapshot and diff. Mirrors [leyline.bridge.WebGamePlayback].
 *
 * Uses the shared [leyline.game.bundle.MessageCounter] for protocol sequencing. Both the session
 * thread and this (engine thread) call `counter.nextMsgId()`/`counter.nextGsId()`
 * on the same atomic — no seeding or syncing needed.
 *
 * Shares [leyline.game.bundle.BundleCursor] with the session-layer `BundleBuilder` via
 * [leyline.game.state.GameBridge.bundleCursor]: the two builders must agree on the diff baseline
 * or `buildDiff` produces a Full when the client expects a Diff. See
 * [leyline.game.bundle.BundleCursor] KDoc for the sharing invariant.
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

    /**
     * Guards the build-and-enqueue window. Message ids are allocated while the
     * batch is built, so drains must not observe an empty queue until that batch
     * is either fully enqueued or the capture fails.
     */
    private val queueLock = Any()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        captureAndPause(LAND_DELAY)
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
                    captureAndPause(CAST_DELAY)
                }
                isAbility -> {
                    if (key != null) markPending(pendingLocalAbilities, key)
                    captureAndPause(CAST_DELAY)
                }
                !isAbility -> {
                    if (key != null) markPending(pendingLocalCasts, key)
                    captureAndPause(CAST_DELAY)
                }
            }
            return
        }

        captureAndPause(CAST_DELAY)
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
        captureAndPause(RESOLVE_DELAY)
    }

    override fun visit(ev: GameEventCardCounters) {
        if (isRemoteActing()) return
        captureAndPause(COUNTER_DELAY)
    }

    override fun visit(ev: GameEventPlayerPoisoned) {
        if (isRemoteActing()) return
        captureAndPause(COUNTER_DELAY)
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
        captureAndPause(PHASE_DELAY, turnStarted = true)
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
        captureAndPause(delay)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        // Capture for BOTH local and remote attackers. The client expects a
        // combat-state diff (tapped creatures + attackState=Attacking) after
        // attackers are declared regardless of whose turn it is. Without this,
        // the human-seat auto-pass loop overshoots past combat before building
        // a diff, and the client never sees attackers tapped (leyline-o2q).
        if (isRemoteActing()) {
            captureAndPause(COMBAT_DELAY)
        } else {
            captureAndPause(0) // no pacing delay on own turn
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        captureAndPause(COMBAT_DELAY)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        if (isRemoteActing()) return
        captureAndPause(0)
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

    /** Drain queued batches that were allocated before the caller's next outbound message. */
    fun drainQueueBeforeMsgId(msgId: Int): List<List<GREToClientMessage>> =
        synchronized(queueLock) {
            buildList {
                while (true) {
                    val batch = queue.peek() ?: break
                    val firstMsgId = batch.firstOrNull()?.msgId ?: Int.MAX_VALUE
                    if (firstMsgId >= msgId) break
                    add(queue.poll() ?: break)
                }
            }
        }

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = synchronized(queueLock) { queue.isNotEmpty() }

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
        turnStarted: Boolean = false,
        gameOverride: forge.game.Game? = null,
        eventsOverride: FrameEventLog? = null,
    ) {
        val game =
            gameOverride ?: bridge.getGame() ?: run {
                log.debug("GamePlayback: captureAndPause during teardown (game null), skipping")
                return
            }

        try {
            val messageCount =
                synchronized(queueLock) {
                    val events = eventsOverride ?: bridge.closeBundleFrame(seatId)
                    if (eventsOverride == null && events.events.hasCombatDamage()) {
                        captureSplitCombatDamage(game, events.events)
                        2
                    } else {
                        val messages = buildDiffMessages(game, turnStarted, events)
                        queue.add(messages)
                        if (bridge.consumePromptTimeoutNeedsAutoAdvance()) {
                            bridge.autoAdvanceRequester?.invoke("prompt timeout playback queued")
                        }
                        messages.size
                    }
                }

            // No need to advance the cursor here — buildDiff (called by remoteActionDiff)
            // writes cursor.lastSent after computing the diff. A redundant
            // buildFromSnapshot with the same gsId creates a self-referential snapshot.

            log.debug(
                "action captured: phase={} turn={} queued={} msgs={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                queue.size,
                messageCount,
            )
        } catch (ex: Exception) {
            log.warn("Failed to capture AI action state: {}", ex.message, ex)
        }

        // Pacing: sleep engine thread so client can animate
        val adjustedDelay = (delayMs * delayMultiplier).toLong()
        if (adjustedDelay > 0) {
            try {
                Thread.sleep(adjustedDelay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
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

    private fun List<LeylineGameEvent>.hasCombatDamage(): Boolean =
        any { it is LeylineGameEvent.DamageDealtToCard || it is LeylineGameEvent.DamageDealtToPlayer }

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
            if (event is LeylineGameEvent.PhaseChanged) {
                if (event.isDamageStep()) inDamageStep = true
            } else if (event is LeylineGameEvent.DamageDealtToPlayer ||
                event is LeylineGameEvent.LifeChanged ||
                event == LeylineGameEvent.CombatEnded
            ) {
                if (!inDamageStep) return false
            } else if (event is LeylineGameEvent.DamageDealtToCard) {
                return false
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
                if (event is LeylineGameEvent.DamageDealtToCard || event is LeylineGameEvent.DamageDealtToPlayer) {
                    return@run currentDamageStep
                }
            }
            Step.CombatDamage_a2cb.number
        }

    private fun combatDamageSourceSeat(events: List<LeylineGameEvent>): Int? {
        events
            .firstNotNullOfOrNull { event ->
                (event as? LeylineGameEvent.DamageDealtToPlayer)?.targetSeatId?.value
            }?.let { defenderSeat ->
                val otherSeats = bridge.gameSeatIds() - defenderSeat
                if (otherSeats.size == 1) return otherSeats.single()
                return if (defenderSeat == 1) 2 else 1
            }
        val sourceId =
            events.firstNotNullOfOrNull { event ->
                when (event) {
                    is LeylineGameEvent.DamageDealtToCard -> event.sourceCardId
                    is LeylineGameEvent.DamageDealtToPlayer -> event.sourceCardId
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
