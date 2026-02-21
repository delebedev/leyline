package forge.nexus.conformance

import forge.game.Game
import forge.game.zone.ZoneType
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.nexus.server.ListMessageSink
import forge.nexus.server.MatchRegistry
import forge.nexus.server.MatchSession
import forge.web.game.GameBootstrap
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Target as ProtoTarget

/**
 * Test harness wrapping real [MatchSession] — zero reimplemented logic.
 *
 * Creates a MatchSession with [ListMessageSink] (paceDelayMs=0).
 * All auto-pass, combat, targeting, game-over flows run through production code.
 *
 * @param validating when true (default), wraps the sink in [ValidatingMessageSink]
 *                   to get automatic invariant checking on every message
 */
class MatchFlowHarness(
    private val seed: Long = 42L,
    private val deckList: String? = null,
    validating: Boolean = true,
) {

    private val matchId = "test-match"
    private val seatId = 1

    val registry = MatchRegistry()
    val sink = ListMessageSink()

    /** Validating decorator — null when [validating] is false. */
    val validatingSink: ValidatingMessageSink? =
        if (validating) ValidatingMessageSink(sink, strict = true) else null

    /** The [MessageSink] passed to [MatchSession] (validating wrapper or plain). */
    private val effectiveSink get() = validatingSink ?: sink

    val accumulator = ClientAccumulator()
    val allMessages = mutableListOf<GREToClientMessage>()

    lateinit var session: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set

    /** Start game, keep hand, advance to first real-action phase via MatchSession. */
    fun connectAndKeep() {
        GameBootstrap.initializeCardDatabase(quiet = true)

        bridge = GameBridge()
        bridge.start(seed = seed, deckList = deckList)

        session = MatchSession(
            seatId = seatId,
            matchId = matchId,
            sink = effectiveSink,
            registry = registry,
            paceDelayMs = 0,
        )
        session.gameBridge = bridge
        registry.registerSession(matchId, seatId, session)

        bridge.submitKeep(seatId)

        // Seed accumulator with handshake Full (simulates the Full state the real
        // client gets before phaseTransitionDiff thin Diffs)
        val game = bridge.getGame()
        if (game != null) {
            val fullGsm = StateMapper.buildFromGame(game, 0, matchId, bridge, viewingSeatId = seatId)
            accumulator.seedFull(fullGsm)
            validatingSink?.seedFull(fullGsm)
        }

        session.onMulliganKeep()
        drainSink()
    }

    /** Play a land from hand. Returns true if successful. */
    fun playLand(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val land = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isLand } ?: return false

        val instanceId = bridge.getOrAllocInstanceId(land.id)
        val grpId = CardDb.lookupByName(land.name) ?: 0

        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.PerformActionResp_097b)
            .setPerformActionResp(
                PerformActionResp.newBuilder().addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId),
                ),
            ).build()

        session.onPerformAction(greMsg)
        drainSink()
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val creature = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isCreature } ?: return false

        val instanceId = bridge.getOrAllocInstanceId(creature.id)
        val grpId = CardDb.lookupByName(creature.name) ?: 0

        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.PerformActionResp_097b)
            .setPerformActionResp(
                PerformActionResp.newBuilder().addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId),
                ),
            ).build()

        session.onPerformAction(greMsg)
        drainSink()
        return true
    }

    /** Pass priority — sends a real Pass action through MatchSession. */
    fun passPriority() {
        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.PerformActionResp_097b)
            .setPerformActionResp(
                PerformActionResp.newBuilder().addActions(
                    Action.newBuilder().setActionType(ActionType.Pass),
                ),
            ).build()

        session.onPerformAction(greMsg)
        drainSink()
    }

    /**
     * Keep passing until a target turn is reached (or game over / max iterations).
     *
     * TODO(multi-turn-overshoot): A single [passPriority] call triggers
     *  [MatchSession.autoPassAndAdvance] which loops up to 50 times, auto-passing
     *  every phase where only Pass is available. This means one call can skip
     *  entire turns — e.g. with seed 42, land + creature + resolve + pass jumps
     *  from turn 1 to turn 3. Tests that need exact turn control should use haste
     *  creatures (turn-1 combat) or assert turn >= N instead of turn == N. A proper
     *  fix would be to add a turn-boundary stop in autoPassAndAdvance so the client
     *  always gets priority at the start of each new turn.
     */
    fun passUntilTurn(targetTurn: Int, maxPasses: Int = 30) {
        repeat(maxPasses) {
            if (turn() >= targetTurn || isGameOver()) return
            passPriority()
        }
    }

    /**
     * Replace the AI seat's controller with a [ScriptedPlayerController].
     * Call after [connectAndKeep] — the AI player must already exist.
     * Returns the scripted controller for inspection.
     */
    fun installScriptedAi(script: List<ScriptedAction>): ScriptedPlayerController {
        val game = game()
        val aiPlayer = bridge.getPlayer(2)
            ?: error("No AI player found")
        val controller = ScriptedPlayerController(game, aiPlayer, script)
        // Use highest timestamp so this controller takes priority over the default AI
        aiPlayer.addController(Long.MAX_VALUE, aiPlayer, controller, false)
        return controller
    }

    // --- Combat helpers ---

    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> {
        val player = bridge.getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Battlefield).cards
            .filter { it.isCreature }
            .map { bridge.getOrAllocInstanceId(it.id) to it.name }
    }

    /** Declare attackers by instanceId. Builds proto and calls [MatchSession.onDeclareAttackers]. */
    fun declareAttackers(attackerInstanceIds: List<Int>) {
        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.DeclareAttackersResp_097b)
            .setDeclareAttackersResp(
                DeclareAttackersResp.newBuilder().apply {
                    for (iid in attackerInstanceIds) {
                        addSelectedAttackers(Attacker.newBuilder().setAttackerInstanceId(iid))
                    }
                },
            ).build()
        session.onDeclareAttackers(greMsg)
        drainSink()
    }

    /** Declare no attackers (skip combat). */
    fun declareNoAttackers() {
        declareAttackers(emptyList())
    }

    /** Declare no blockers (let all attackers through). */
    fun declareNoBlockers() {
        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.DeclareBlockersResp_097b)
            .setDeclareBlockersResp(DeclareBlockersResp.newBuilder())
            .build()
        session.onDeclareBlockers(greMsg)
        drainSink()
    }

    // --- Targeting helpers ---

    /** Select targets by instanceId for a pending SelectTargetsReq. */
    fun selectTargets(targetInstanceIds: List<Int>) {
        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.SelectTargetsResp_097b)
            .setSelectTargetsResp(
                SelectTargetsResp.newBuilder()
                    .setTarget(
                        TargetSelection.newBuilder().apply {
                            for (iid in targetInstanceIds) {
                                addTargets(
                                    ProtoTarget.newBuilder()
                                        .setTargetInstanceId(iid)
                                        .setLegalAction(SelectAction.Select_a1ad),
                                )
                            }
                        },
                    ),
            ).build()
        session.onSelectTargets(greMsg)
        drainSink()
    }

    /** Cast a spell by card name. Returns false if card not in hand. */
    fun castSpellByName(cardName: String): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false

        val instanceId = bridge.getOrAllocInstanceId(card.id)
        val grpId = CardDb.lookupByName(card.name) ?: 0

        val greMsg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.PerformActionResp_097b)
            .setPerformActionResp(
                PerformActionResp.newBuilder().addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId),
                ),
            ).build()

        session.onPerformAction(greMsg)
        drainSink()
        return true
    }

    // --- Message inspection ---

    /** Snapshot current message count for later comparison with [messagesSince]. */
    fun messageSnapshot(): Int = allMessages.size

    /** Get all messages since a snapshot point. */
    fun messagesSince(snapshot: Int): List<GREToClientMessage> =
        allMessages.subList(snapshot, allMessages.size).toList()

    // --- State queries ---

    fun phase(): String? = game().phaseHandler.phase?.name
    fun turn(): Int = game().phaseHandler.turn
    fun isAiTurn(): Boolean {
        val human = bridge.getPlayer(seatId) ?: return false
        return game().phaseHandler.playerTurn != human
    }
    fun isGameOver(): Boolean = game().isGameOver
    fun game(): Game = bridge.getGame()!!
    fun shutdown() = bridge.shutdown()

    private fun drainSink() {
        allMessages.addAll(sink.messages)
        accumulator.processAll(sink.messages)
        sink.clear()
    }
}
