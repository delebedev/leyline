package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.event.*
import forge.game.phase.PhaseType
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue

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
) : IGameEventVisitor.Base<Unit>() {
    private val bundleBuilder = BundleBuilder(bridge, matchId, seatId)

    private val log = LoggerFactory.getLogger(GamePlayback::class.java)

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    /** Thread-safe queue of GRE message batches for the handler to drain. */
    private val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        captureAndPause(LAND_DELAY)
    }

    /**
     * SpellAbility ids of trigger casts seen on the local turn that haven't yet
     * resolved. Used to recognise the matching `GameEventSpellResolved` so we
     * can split the trigger lifecycle into its own GSMs even when the player
     * is acting (the canonical Mobilize wire ships announcement, resolution
     * + tokens, and combat damage in three separate diffs at Combat/DeclareAttack
     * → Combat/CombatDamage). Independent of [GameEventCollector.pendingTriggers]
     * because the EventBus drains both subscribers in registration order and
     * the collector consumes its map on the resolve event.
     */
    private val pendingLocalTriggers = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    override fun visit(ev: GameEventSpellAbilityCast) {
        val isTrigger = ev.si()?.isTrigger == true
        val splitForLocalTrigger = isTrigger && shouldSplitOnLocalTurn(ev.sa()?.hostCard?.id)
        if (splitForLocalTrigger) {
            val saId = ev.sa()?.id ?: 0
            if (saId != 0) pendingLocalTriggers[saId] = true
        }
        // Specific keyword triggers (Mobilize today) always need their own diff
        // on the local turn so the client renders the trigger landing on the
        // stack before the resolution + tokens diff. Other triggers keep the
        // legacy single-GSM-per-action bundling — broader rollout follows
        // shape-survey of integration tests that asserted on the old shape.
        if (!isRemoteActing() && !splitForLocalTrigger) return
        captureAndPause(CAST_DELAY)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val saId = ev.spell()?.id ?: 0
        val splitForLocalTrigger = saId != 0 && pendingLocalTriggers.remove(saId) != null
        // Mirror the cast hook above — trigger resolutions on a local turn need
        // their own diff so TokenCreated lands before the next combat-damage
        // diff. Gated on the same Mobilize-keyword check via the
        // [pendingLocalTriggers] entry recorded at cast time.
        if (!isRemoteActing() && !splitForLocalTrigger) return
        captureAndPause(RESOLVE_DELAY)
    }

    /** Decide whether to split this trigger's lifecycle into its own diff on
     *  the local turn. Today: only Mobilize keyword triggers (so the warrior
     *  tokens enter a beat before combat damage).
     *
     *  Widening to other keyword triggers (other combat triggers, ETB
     *  mechanics with delayed-trigger tokens, etc.) inserts an extra Diff
     *  GSM per trigger fire. Any [MatchFlowHarness]-based integration test
     *  asserting a single-GSM-per-action wire shape will need to update its
     *  assertions before the keyword can be added to the predicate. Audit
     *  before extending the list. */
    private fun shouldSplitOnLocalTurn(hostCardForgeId: Int?): Boolean {
        if (hostCardForgeId == null) return false
        val card = bridge.findCard(leyline.bridge.types.ForgeCardId(hostCardForgeId)) ?: return false
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: return false
        return bridge.cardRepository.findKeywordAbilityGrpId(grpId, leyline.game.data.KeywordAbilityIds.MOBILIZE) != null
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
        buildList {
            while (true) {
                add(queue.poll() ?: break)
            }
        }

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = queue.isNotEmpty()

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
    ) {
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: captureAndPause during teardown (game null), skipping")
                return
            }

        try {
            val result =
                bundleBuilder.remoteActionDiff(
                    game,
                    counter,
                    turnStarted = turnStarted,
                )

            queue.add(result.messages)

            // No need to advance the cursor here — buildDiff (called by remoteActionDiff)
            // writes cursor.lastSent after computing the diff. A redundant
            // buildFromSnapshot with the same gsId creates a self-referential snapshot.

            log.debug(
                "action captured: phase={} turn={} queued={} msgs={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                queue.size,
                result.messages.size,
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
        return turnPlayer != myPlayer
    }

    companion object {
        const val PHASE_DELAY = 200 // ms
        const val COMBAT_DELAY = 400
        const val CAST_DELAY = 400
        const val RESOLVE_DELAY = 400
        const val LAND_DELAY = 300
    }
}
