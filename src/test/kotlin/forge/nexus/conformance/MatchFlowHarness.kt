package forge.nexus.conformance

import forge.game.Game
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Test harness that simulates MatchHandler's game loop without Netty.
 *
 * Wraps GameBridge + BundleBuilder. Instead of sending messages over TCP,
 * collects them into [accumulator] and [allMessages]. Mirrors the
 * autoPassAndAdvance logic from MatchHandler.
 */
class MatchFlowHarness(private val seed: Long = 42L) {

    private val matchId = "test-match"
    private val seatId = 1

    lateinit var bridge: GameBridge
        private set

    val accumulator = ClientAccumulator()
    val allMessages = mutableListOf<GREToClientMessage>()

    private var msgId = 1
    private var gsId = 0

    /** Start game, keep hand, advance to Main1 with auto-pass. */
    fun connectAndKeep() {
        GameBootstrap.initializeCardDatabase()
        bridge = GameBridge()
        bridge.start(seed = seed)
        bridge.submitKeep(seatId)
        bridge.awaitPriority()

        // Drain any AI action diffs queued during awaitPriority
        bridge.playback?.drainQueue()

        val game = game()
        bridge.snapshotState(game)

        // Send game-start bundle
        val result = BundleBuilder.gameStart(game, bridge, matchId, seatId, msgId, gsId)
        collect(result)
        bridge.playback?.seedCounters(msgId, gsId)
        bridge.snapshotState(game)

        // Auto-pass to reach a phase with real actions
        autoPassAndAdvance()
    }

    /** Play a land from hand. Returns true if successful. */
    fun playLand(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val land = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isLand } ?: return false
        val pending = bridge.actionBridge.getPending() ?: return false

        bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land.id))
        bridge.awaitPriority()
        drainPlayback()

        val game = game()
        bridge.snapshotState(game)
        val result = BundleBuilder.postAction(game, bridge, matchId, seatId, msgId, gsId)
        collect(result)
        bridge.playback?.seedCounters(msgId, gsId)
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val creature = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isCreature } ?: return false
        val pending = bridge.actionBridge.getPending() ?: return false

        bridge.actionBridge.submitAction(pending.actionId, PlayerAction.CastSpell(creature.id))
        bridge.awaitPriority()
        drainPlayback()

        val game = game()
        // Spell on stack -- send state, then pass to resolve
        if (!game.stack.isEmpty) {
            bridge.snapshotState(game)
            val stackResult = BundleBuilder.postAction(game, bridge, matchId, seatId, msgId, gsId)
            collect(stackResult)

            // Pass to resolve
            val stackPending = bridge.actionBridge.getPending() ?: return true
            bridge.actionBridge.submitAction(stackPending.actionId, PlayerAction.PassPriority)
            bridge.awaitPriority()
            drainPlayback()
        }

        bridge.snapshotState(game)
        val result = BundleBuilder.postAction(game, bridge, matchId, seatId, msgId, gsId)
        collect(result)
        bridge.playback?.seedCounters(msgId, gsId)
        return true
    }

    /** Pass priority. Triggers autoPassAndAdvance. */
    fun passPriority() {
        val pending = bridge.actionBridge.getPending() ?: return
        bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        bridge.awaitPriority()
        drainPlayback()
        autoPassAndAdvance()
    }

    /** Current engine phase name. */
    fun phase(): String? = game().phaseHandler.phase?.name

    /** Current turn number. */
    fun turn(): Int = game().phaseHandler.turn

    /** Is it the AI's turn? */
    fun isAiTurn(): Boolean {
        val human = bridge.getPlayer(seatId) ?: return false
        return game().phaseHandler.playerTurn != human
    }

    /** Is the game over? */
    fun isGameOver(): Boolean = game().isGameOver

    fun game(): Game = bridge.getGame()!!

    fun shutdown() = bridge.shutdown()

    // --- Auto-pass logic (mirrors MatchHandler.autoPassAndAdvance) ---

    private fun autoPassAndAdvance(maxIterations: Int = 50) {
        repeat(maxIterations) {
            val game = game()
            if (game.isGameOver) return

            drainPlayback()

            val human = bridge.getPlayer(seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat: DeclareAttackers on human's attacking turn
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isHumanTurn) {
                val req = StateMapper.buildDeclareAttackersReq(game, seatId, bridge)
                if (req.attackersCount > 0) {
                    bridge.snapshotState(game)
                    val result = BundleBuilder.declareAttackersBundle(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                        req,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return // test must handle combat
                }
                // No legal attackers — fall through to auto-pass
            }

            // AI attacking: send state so test sees creatures with attackState
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && isAiTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    bridge.snapshotState(game)
                    val result = BundleBuilder.postAction(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return
                }
                // No attackers — fall through to auto-pass
            }

            // Combat: DeclareBlockers on AI's attacking turn (human is defending)
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isAiTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    bridge.snapshotState(game)
                    val result = BundleBuilder.declareBlockersBundle(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return
                }
            }

            // AI blocking (defending against human attack): send state
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && isHumanTurn) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    bridge.snapshotState(game)
                    val result = BundleBuilder.postAction(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return
                }
            }

            // Combat damage: send state so damage is visible
            if (phase == PhaseType.COMBAT_DAMAGE) {
                bridge.snapshotState(game)
                val result = BundleBuilder.postAction(
                    game,
                    bridge,
                    matchId,
                    seatId,
                    msgId,
                    gsId,
                )
                collect(result)
                bridge.playback?.seedCounters(msgId, gsId)
                return
            }

            // Combat end: send state if combat actually happened
            if (phase == PhaseType.COMBAT_END) {
                val combat = game.phaseHandler.combat
                if (combat != null && combat.attackers.isNotEmpty()) {
                    bridge.snapshotState(game)
                    val result = BundleBuilder.postAction(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return
                }
            }

            // Check for pending interactive prompt (targeting, sacrifice, etc.)
            val pendingPrompt = bridge.promptBridge.getPendingPrompt()
            if (pendingPrompt != null && pendingPrompt.request.candidateRefs.isNotEmpty()) {
                val req = StateMapper.buildSelectTargetsReq(pendingPrompt, bridge)
                bridge.snapshotState(game)
                val result = BundleBuilder.selectTargetsBundle(
                    game,
                    bridge,
                    matchId,
                    seatId,
                    msgId,
                    gsId,
                    req,
                )
                collect(result)
                bridge.playback?.seedCounters(msgId, gsId)
                return // test must handle targeting
            }

            val actions = StateMapper.buildActions(game, seatId, bridge)
            if (!BundleBuilder.shouldAutoPass(actions)) {
                // Player has real actions — send state
                bridge.snapshotState(game)
                val result = BundleBuilder.postAction(
                    game,
                    bridge,
                    matchId,
                    seatId,
                    msgId,
                    gsId,
                )
                collect(result)
                bridge.playback?.seedCounters(msgId, gsId)
                return
            }

            // Auto-pass: only Pass available
            val pending = bridge.actionBridge.getPending()
            if (pending != null) {
                bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                bridge.awaitPriority()
            } else if (isAiTurn) {
                val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
                if (!reachedPriority) {
                    val g = bridge.getGame()
                    if (g != null && g.isGameOver) return
                    // Timed out — send whatever we have
                    bridge.snapshotState(game)
                    val result = BundleBuilder.postAction(
                        game,
                        bridge,
                        matchId,
                        seatId,
                        msgId,
                        gsId,
                    )
                    collect(result)
                    bridge.playback?.seedCounters(msgId, gsId)
                    return
                }
            } else {
                bridge.awaitPriority()
            }
        }
    }

    // --- Helpers ---

    private fun collect(result: BundleBuilder.BundleResult) {
        msgId = result.nextMsgId
        gsId = result.nextGsId
        allMessages.addAll(result.messages)
        accumulator.processAll(result.messages)
    }

    private fun drainPlayback() {
        val playback = bridge.playback ?: return
        if (playback.hasPendingMessages()) {
            playback.drainQueue()
            val (nextMsg, nextGs) = playback.getCounters()
            msgId = nextMsg
            gsId = nextGs
            bridge.snapshotState(game())
        }
    }
}
