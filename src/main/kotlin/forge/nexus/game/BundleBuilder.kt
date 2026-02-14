package forge.nexus.game

import forge.game.Game
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Pure functions that build GRE message bundles for each flow milestone.
 *
 * No side effects, no Netty, no mutable handler state — takes everything as params,
 * returns messages + updated counters. Handler sends them; tests assert on them.
 */
object BundleBuilder {

    data class BundleResult(
        val messages: List<GREToClientMessage>,
        val nextMsgId: Int,
        val nextGsId: Int,
    )

    /**
     * Game-start bundle (post-keep):
     *   GRE 1: Diff, Beginning/Upkeep, SendHiFi (stage transition)
     *   GRE 2: Diff, empty priority-pass marker (gsId++)
     *   GRE 3: Full, Main1, SendAndRecord, zones + objects + actions
     *   GRE 4: ActionsAvailableReq
     */
    fun gameStart(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId
        val messages = mutableListOf<GREToClientMessage>()

        // GRE 1: Beginning/Upkeep transition (stage → Play)
        nextGs++
        val beginGs = StateMapper.buildTransitionState(
            game,
            nextGs,
            matchId,
            bridge,
            Phase.Beginning_a549,
            Step.Upkeep_a2cb,
            isStageTransition = true,
        )
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = beginGs
            },
        )

        // GRE 2: empty priority-pass marker (double-diff pattern)
        nextGs++
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = StateMapper.buildEmptyDiff(nextGs)
            },
        )

        // GRE 3: Full state at Main1 with zones, objects, and actions.
        // Must be Full (not Diff) because prior states used template instanceIds
        // that don't match the bridge's ID mapping.
        nextGs++
        val actions = StateMapper.buildActions(game, seatId, bridge)
        val mainGs = StateMapper.buildFromGame(game, nextGs, matchId, bridge, actions)
        messages.add(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = mainGs
            },
        )

        // GRE 4: ActionsAvailableReq
        messages.add(
            makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, nextMsg++) {
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(2).build())
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

    /**
     * Post-action state bundle:
     *   GRE 1: Diff GameStateMessage with embedded actions (only changed zones/objects)
     *   GRE 2: ActionsAvailableReq
     */
    fun postAction(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId

        nextGs++
        val actions = StateMapper.buildActions(game, seatId, bridge)
        val gs = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, actions)

        val messages = listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
                it.gameStateMessage = gs
            },
            makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, nextMsg++) {
                it.actionsAvailableReq = actions
                it.setPrompt(Prompt.newBuilder().setPromptId(2).build())
            },
        )

        return BundleResult(messages, nextMsg, nextGs)
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used to decide whether to auto-pass or send state to the client.
     */
    fun shouldAutoPass(actions: ActionsAvailableReq): Boolean =
        actions.actionsList.all { it.actionType == ActionType.Pass }

    /**
     * Build a pair of Diff GameStateMessages for a phase transition.
     * Every phase/step sends exactly 2 diffs: enter + priority-pass marker.
     */
    fun phaseTransitionDiff(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val phase = StateMapper.mapPhase(game.phaseHandler.phase)
        val step = StateMapper.mapStep(game.phaseHandler.phase)

        val gs = StateMapper.buildTransitionState(
            game,
            nextGs,
            matchId,
            bridge,
            phase,
            step,
        )
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        // Priority-pass marker (empty diff, gsId increments)
        nextGs++
        val msg2 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = StateMapper.buildEmptyDiff(nextGs)
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Declare-attackers bundle: Diff (DeclareAttack step) + DeclareAttackersReq (prompt id=6).
     */
    fun declareAttackersBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
        prebuiltReq: DeclareAttackersReq? = null,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val gs = StateMapper.buildFromGame(game, nextGs, matchId, bridge)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        val req = prebuiltReq ?: StateMapper.buildDeclareAttackersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareAttackersReq_695e, nextGs, seatId, nextMsg++) {
            it.declareAttackersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(6).build())
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Declare-blockers bundle: Diff (DeclareBlock step) + DeclareBlockersReq (prompt id=7).
     */
    fun declareBlockersBundle(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): BundleResult {
        var nextMsg = msgId
        var nextGs = gsId + 1

        val gs = StateMapper.buildFromGame(game, nextGs, matchId, bridge)
        val msg1 = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, nextMsg++) {
            it.gameStateMessage = gs
        }

        val req = StateMapper.buildDeclareBlockersReq(game, seatId, bridge)
        val msg2 = makeGRE(GREMessageType.DeclareBlockersReq_695e, nextGs, seatId, nextMsg++) {
            it.declareBlockersReq = req
            it.setPrompt(Prompt.newBuilder().setPromptId(7).build())
        }

        return BundleResult(listOf(msg1, msg2), nextMsg, nextGs)
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        seatId: Int,
        msgId: Int,
        gsId: Int,
    ): GREToClientMessage = makeGRE(GREMessageType.QueuedGameStateMessage, gsId, seatId, msgId) {
        it.gameStateMessage = gameState
    }

    /** Build a single GRE message. */
    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        seatId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }
}
