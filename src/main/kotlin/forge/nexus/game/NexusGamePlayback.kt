package forge.nexus.game

import com.google.common.eventbus.Subscribe
import forge.ai.LobbyPlayerAi
import forge.game.event.*
import forge.game.phase.PhaseType
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Paces AI turns by sleeping the game thread at key events and capturing
 * per-action GRE state diffs for the client.
 *
 * Subscribes to the engine's Guava EventBus. Events fire synchronously on
 * the game thread -- sleeping here freezes engine progress and state, making
 * it safe to snapshot and diff. Mirrors [forge.web.game.WebGamePlayback].
 *
 * The [MatchHandler][forge.nexus.server.MatchHandler] drains the queue
 * via [drainQueue] and sends messages to the TCP socket with correct
 * protocol counters.
 *
 * @param bridge the GameBridge for state mapping and zone tracking
 * @param matchId match identifier for GRE messages
 * @param seatId the human player's seat (messages are from their perspective)
 */
class NexusGamePlayback(
    private val bridge: GameBridge,
    private val matchId: String,
    private val seatId: Int,
) : IGameEventVisitor.Base<Unit>() {

    private val log = LoggerFactory.getLogger(NexusGamePlayback::class.java)

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    /** Thread-safe queue of GRE message batches for the handler to drain. */
    private val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()

    /** Counter for gsId -- shared via atomic for thread safety. */
    private val gsIdCounter = AtomicInteger(0)

    /** Counter for msgId -- shared via atomic for thread safety. */
    private val msgIdCounter = AtomicInteger(0)

    /**
     * Sync counters from MatchHandler's current values.
     *
     * Uses max semantics: never goes backwards. The game thread may have already
     * advanced the counters via [captureAndPause] between the handler's drain and
     * this seed call. Clobbering with a stale value causes gsId collisions.
     */
    fun seedCounters(msgId: Int, gsId: Int) {
        msgIdCounter.updateAndGet { maxOf(it, msgId) }
        gsIdCounter.updateAndGet { maxOf(it, gsId) }
    }

    /** Returns current counter values so MatchHandler can sync after drain. */
    fun getCounters(): Pair<Int, Int> = msgIdCounter.get() to gsIdCounter.get()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isAiActing()) return
        captureAndPause(LAND_DELAY)
    }

    override fun visit(ev: GameEventSpellAbilityCast) {
        if (!isAiActing()) return
        captureAndPause(CAST_DELAY)
    }

    override fun visit(ev: GameEventSpellResolved) {
        if (!isAiActing()) return
        captureAndPause(RESOLVE_DELAY)
    }

    override fun visit(ev: GameEventTurnBegan) {
        if (!isAiActing()) return
        val game = bridge.getGame() ?: return
        lastCapturedTurn = game.phaseHandler.turn
        lastCapturedPhase = game.phaseHandler.phase
        captureAndPause(PHASE_DELAY, phaseChanged = true, turnStarted = true)
    }

    override fun visit(ev: GameEventTurnPhase) {
        if (!isAiActing()) return
        val game = bridge.getGame() ?: return
        val turn = game.phaseHandler.turn
        val phase = game.phaseHandler.phase
        // Skip if TurnBegan already captured this exact turn+phase
        if (turn == lastCapturedTurn && phase == lastCapturedPhase) return
        lastCapturedTurn = turn
        lastCapturedPhase = phase
        val delay = when (ev.phase()) {
            PhaseType.COMBAT_DECLARE_ATTACKERS,
            PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.COMBAT_END,
            -> COMBAT_DELAY
            else -> PHASE_DELAY
        }
        captureAndPause(delay, phaseChanged = true)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        if (!isAiActing()) return
        captureAndPause(COMBAT_DELAY)
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isAiActing()) return
        captureAndPause(COMBAT_DELAY)
    }

    // -- Queue access (called from MatchHandler / Netty thread) --

    /** Drain all queued message batches. Returns empty list if nothing queued. */
    fun drainQueue(): List<List<GREToClientMessage>> {
        val result = mutableListOf<List<GREToClientMessage>>()
        while (true) {
            val batch = queue.poll() ?: break
            result.add(batch)
        }
        return result
    }

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = queue.isNotEmpty()

    // -- Internal --

    /**
     * Snapshot current game state as a diff, queue the GRE messages,
     * update the bridge snapshot, then sleep for animation pacing.
     *
     * Called on the engine thread -- state is frozen, safe to serialize.
     */
    private fun captureAndPause(
        delayMs: Int,
        phaseChanged: Boolean = false,
        turnStarted: Boolean = false,
    ) {
        val game = bridge.getGame() ?: return

        try {
            val result = BundleBuilder.aiActionDiff(
                game,
                bridge,
                matchId,
                seatId,
                msgIdCounter.get(),
                gsIdCounter.get(),
                phaseChanged = phaseChanged,
                turnStarted = turnStarted,
            )
            msgIdCounter.set(result.nextMsgId)
            gsIdCounter.set(result.nextGsId)

            queue.add(result.messages)

            // Update bridge snapshot so next diff is relative to this state.
            // Pass the last gsId we emitted so prevGameStateId chains correctly.
            bridge.snapshotState(game, result.nextGsId)

            log.debug(
                "AI action captured: phase={} turn={} queued={} msgs={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                queue.size,
                result.messages.size,
            )
        } catch (ex: Exception) {
            log.warn("Failed to capture AI action state: {}", ex.message, ex)
        }

        // Pacing: sleep engine thread so client can animate
        try {
            Thread.sleep(delayMs.toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * True when the current turn's active player is AI.
     * Skips capture on human turns (bridge blocking provides natural pacing).
     */
    private fun isAiActing(): Boolean {
        val game = bridge.getGame() ?: return false
        val turnPlayer = game.phaseHandler.playerTurn ?: return false
        return turnPlayer.lobbyPlayer is LobbyPlayerAi
    }

    companion object {
        const val PHASE_DELAY = 200 // ms
        const val COMBAT_DELAY = 400
        const val CAST_DELAY = 400
        const val RESOLVE_DELAY = 400
        const val LAND_DELAY = 300
    }
}
