package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.event.*
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.event.Zone
import leyline.game.event.combatDamageFact
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Materializes per-action values for owner-side projection while pacing remote
 * turns by sleeping the game thread at key events.
 *
 * Subscribes to the engine's Guava EventBus. Events fire synchronously on
 * the game thread -- sleeping here freezes engine progress and state, making
 * it safe to snapshot and diff. Mirrors [leyline.bridge.WebGamePlayback].
 *
 * Every mode publishes immutable [PlaybackYield] values. The match owner
 * compiles, sequences, and delivers them.
 *
 * @param bridge the GameBridge for state mapping and zone tracking
 * @param matchId match identifier for GRE messages
 * @param seatId the human player's seat (messages are from their perspective)
 */
class GamePlayback(
    private val bridge: GameBridge,
    private val matchId: String,
    private val seatId: Int,
    /** Delay multiplier (1.0 = default, 0.5 = 2x speed, 0 = instant). Derived from config ai.speed. */
    private val delayMultiplier: Double = 1.0,
    /** Spectator playback captures both seats because every turn is remote to the viewer. */
    private val captureLocalActions: Boolean = false,
) : IGameEventVisitor.Base<Unit>() {
    private val combatFramePlanner = CombatFramePlanner(bridge, seatId)
    private val playbackMaterializer =
        InteractivePlaybackMaterializer(bridge, matchId, seatId, combatFramePlanner)

    private val log = LoggerFactory.getLogger(GamePlayback::class.java)

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        captureAndPause(PlaybackCutReason.LAND_PLAYED, LAND_DELAY)
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
                    captureAndPause(PlaybackCutReason.SPELL_ABILITY_CAST, CAST_DELAY)
                }
                isAbility -> {
                    if (key != null) markPending(pendingLocalAbilities, key)
                    captureAndPause(PlaybackCutReason.SPELL_ABILITY_CAST, CAST_DELAY)
                }
                !isAbility -> {
                    if (key != null) markPending(pendingLocalCasts, key)
                    captureAndPause(PlaybackCutReason.SPELL_ABILITY_CAST, CAST_DELAY)
                }
            }
            return
        }

        captureAndPause(PlaybackCutReason.SPELL_ABILITY_CAST, CAST_DELAY)
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
        captureAndPause(PlaybackCutReason.SPELL_RESOLVED, RESOLVE_DELAY)
    }

    override fun visit(ev: GameEventCardChangeZone) {
        if (!playbackMaterializer.isAwaitingResolutionBoundary() || ev.from()?.zoneType != ZoneType.Stack) return
        captureAndPause(PlaybackCutReason.STACK_EXIT_COMPLETION, 0)
    }

    override fun visit(ev: GameEventCardCounters) {
        if (isRemoteActing()) return
        captureAndPause(PlaybackCutReason.CARD_COUNTERS, COUNTER_DELAY)
    }

    override fun visit(ev: GameEventPlayerPoisoned) {
        if (isRemoteActing()) return
        captureAndPause(PlaybackCutReason.PLAYER_POISONED, COUNTER_DELAY)
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
        captureAndPause(PlaybackCutReason.TURN_BEGAN, PHASE_DELAY, turnStarted = true)
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
        captureAndPause(PlaybackCutReason.TURN_PHASE, delay)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        // Capture for BOTH local and remote attackers. The client expects a
        // combat-state diff (tapped creatures + attackState=Attacking) after
        // attackers are declared regardless of whose turn it is. Without this,
        // the human-seat auto-pass loop overshoots past combat before building
        // a diff, and the client never sees attackers tapped (leyline-o2q).
        if (isRemoteActing()) {
            captureAndPause(PlaybackCutReason.ATTACKERS_DECLARED, COMBAT_DELAY)
        } else {
            captureAndPause(PlaybackCutReason.ATTACKERS_DECLARED, 0) // no pacing delay on own turn
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        captureAndPause(PlaybackCutReason.BLOCKERS_DECLARED, COMBAT_DELAY)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        if (isRemoteActing()) return
        captureAndPause(PlaybackCutReason.COMBAT_ENDED, 0)
    }

    // -- Internal --

    /**
     * Materialize one value yield, then sleep for animation pacing.
     *
     * Called on the engine thread while state is frozen at the cut.
     */
    private fun captureAndPause(
        cutReason: PlaybackCutReason,
        delayMs: Int,
        turnStarted: Boolean = false,
    ) {
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: captureAndPause during teardown (game null), skipping")
                return
            }

        try {
            val published = playbackMaterializer.materialize(game, cutReason, turnStarted)

            // Projection baseline advancement belongs to the path that commits
            // the materialized frame.

            log.debug(
                "action materialized: phase={} turn={} published={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                published,
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

internal fun FrameEventLog.shouldAwaitResolutionBoundary(): Boolean {
    if (events.none { it.combatDamageFact() == false }) return false
    return events
        .filterIsInstance<LeylineGameEvent.SpellResolved>()
        .filterNot { resolved -> resolved.isAbility || resolved.isTrigger }
        .any { resolved ->
            zoneMoves.none { move -> move.cardId == resolved.cardId && move.from == Zone.Stack }
        }
}

/**
 * A still-open reservation already contains its earlier prefix. If another
 * frame consumed that prefix, retain the detached pending input and append the
 * newly reserved suffix.
 */
internal fun FrameEventLog.mergeReservedInput(next: FrameEventLog): FrameEventLog =
    if (
        next.events.take(events.size) == events &&
        next.zoneMoves.take(zoneMoves.size) == zoneMoves
    ) {
        next
    } else {
        FrameEventLog(events + next.events, zoneMoves + next.zoneMoves)
    }
