package leyline.conformance

import forge.game.Game
import forge.game.zone.ZoneType
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.PuzzleSource
import leyline.game.StateMapper
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.*

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

    /** All raw messages (SettingsResp, MatchCompleted, etc.) sent via [MessageSink.sendRaw]. */
    val allRawMessages = mutableListOf<MatchServiceToClientMessage>()

    lateinit var session: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set

    /** Start game, keep hand, advance to first real-action phase via MatchSession. */
    fun connectAndKeep() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()
        if (deckList != null) TestCardRegistry.ensureDeckRegistered(deckList)

        session = MatchSession(
            seatId = seatId,
            matchId = matchId,
            sink = effectiveSink,
            registry = registry,
            paceDelayMs = 0,
        )

        bridge = GameBridge(bridgeTimeoutMs = 5_000L, messageCounter = session.counter, cards = TestCardRegistry.repo)
        bridge.priorityWaitMs = 2_000L
        bridge.start(seed = seed, deckList = deckList)

        session.connectBridge(bridge)
        registry.registerSession(matchId, seatId, session)

        // Seed accumulator + validator with a Full GSM BEFORE submitKeep.
        // At this point the engine is blocked at mulligan — safe to call
        // buildFromGame without racing the engine thread's AI action capture.
        // After submitKeep, the engine runs (potentially AI-first) and concurrent
        // buildFromGame calls would race on drainEvents/nextAnnotationId.
        val game = bridge.getGame()
        if (game != null) {
            val fullGsm = StateMapper.buildFromGame(game, 0, matchId, bridge, viewingSeatId = seatId)
            accumulator.seedFull(fullGsm)
            validatingSink?.seedFull(fullGsm)
        }

        bridge.submitKeep(seatId)

        session.onMulliganKeep()
        drainSink()
    }

    /** Start puzzle game from classpath resource, advance to first action phase. */
    fun connectAndKeepPuzzle(resourcePath: String, aiScript: List<ScriptedAction>? = null) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleBridge(PuzzleSource.loadFromResource(resourcePath), aiScript)
    }

    /**
     * Start puzzle game from inline `.pzl` text, advance to first action phase.
     *
     * Faster than [connectAndKeep]: skips mulligan + turn advancement.
     * Board state is defined declaratively — no multi-turn setup loops.
     *
     * @param aiScript optional scripted actions for the AI — installed before
     *                 auto-pass runs so the AI follows the script on its first turn.
     */
    fun connectAndKeepPuzzleText(puzzleText: String, aiScript: List<ScriptedAction>? = null) {
        // Card DB must init before PuzzleSource.loadFromText — the Puzzle
        // constructor triggers GameState.<clinit> which requires localization.
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleBridge(PuzzleSource.loadFromText(puzzleText), aiScript)
    }

    private fun startPuzzleBridge(puzzle: forge.gamemodes.puzzle.Puzzle, aiScript: List<ScriptedAction>?) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()

        session = MatchSession(
            seatId = seatId,
            matchId = matchId,
            sink = effectiveSink,
            registry = registry,
            paceDelayMs = 0,
        )

        bridge = GameBridge(bridgeTimeoutMs = 5_000L, messageCounter = session.counter, cards = TestCardRegistry.repo)
        bridge.priorityWaitMs = 2_000L
        bridge.startPuzzle(puzzle)

        // Install scripted AI BEFORE onPuzzleStart — auto-pass will advance
        // through the human turn into the AI turn, where the script takes over.
        if (aiScript != null) {
            installScriptedAi(aiScript)
        }

        session.connectBridge(bridge)
        registry.registerSession(matchId, seatId, session)

        val game = bridge.getGame()
        if (game != null) {
            val fullGsm = StateMapper.buildFromGame(game, 0, matchId, bridge, viewingSeatId = seatId)
            accumulator.seedFull(fullGsm)
            validatingSink?.seedFull(fullGsm)
        }

        session.onPuzzleStart()
        drainSink()
    }

    /** Play a land from hand. Returns true if successful. */
    fun playLand(): Boolean {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return false
        val land = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isLand } ?: return false

        val msg = performAction {
            actionType = ActionType.Play_add3
            instanceId = bridge.getOrAllocInstanceId(ForgeCardId(land.id)).value
            grpId = bridge.cards.findGrpIdByName(land.name) ?: 0
        }

        session.onPerformAction(msg)
        drainSink()
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return false
        val creature = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.isCreature } ?: return false

        val msg = performAction {
            actionType = ActionType.Cast
            instanceId = bridge.getOrAllocInstanceId(ForgeCardId(creature.id)).value
            grpId = bridge.cards.findGrpIdByName(creature.name) ?: 0
        }

        session.onPerformAction(msg)
        drainSink()
        return true
    }

    /** Pass priority — sends a real Pass action through MatchSession. */
    fun passPriority() {
        session.onPerformAction(performAction { actionType = ActionType.Pass })
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
     * Pass priority through remaining combat until the turn advances or game ends.
     * Replaces the verbose `repeat(15) { if (gameOver/nextTurn) return@repeat; passPriority() }` pattern.
     */
    fun passThroughCombat(startTurn: Int = turn(), maxPasses: Int = 15) {
        repeat(maxPasses) {
            if (isGameOver() || turn() > startTurn) return
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
        val aiPlayer = bridge.getPlayer(SeatId(2))
            ?: error("No AI player found")
        val controller = ScriptedPlayerController(game, aiPlayer, script)
        // Use highest timestamp so this controller takes priority over the default AI
        aiPlayer.addController(Long.MAX_VALUE, aiPlayer, controller, false)
        return controller
    }

    // --- Phase-precise advancement (bridge-level, no AutoPassEngine) ---

    /**
     * Advance to a specific phase via bridge — one PassPriority at a time.
     * No AutoPassEngine involvement, no phase overshoot.
     */
    fun advanceToPhase(phase: String, turn: Int? = null) =
        leyline.game.advanceToPhase(bridge, phase, turn)

    /** Advance to Main1 via bridge. */
    fun advanceToMain1() = leyline.game.advanceToMain1(bridge)

    /** Advance to COMBAT_DECLARE_ATTACKERS via bridge. */
    fun advanceToCombat(turn: Int? = null) =
        leyline.game.advanceToCombat(bridge, turn)

    /** Advance to MAIN2 via bridge. */
    fun advanceToMain2(turn: Int? = null) =
        leyline.game.advanceToMain2(bridge, turn)

    // --- Combat helpers ---

    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return emptyList()
        return player.getZone(ZoneType.Battlefield).cards
            .filter { it.isCreature }
            .map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value to it.name }
    }

    /**
     * Declare attackers by instanceId using the two-phase Arena protocol:
     * 1. Send [DeclareAttackersResp] with selection (iterative update)
     * 2. Send [SubmitAttackersReq] to finalize (the "Done" button)
     */
    fun declareAttackers(attackerInstanceIds: List<Int>) {
        session.onDeclareAttackers(declareAttackersResp(attackers = attackerInstanceIds))
        drainSink()

        session.onDeclareAttackers(submitAttackersReq(seatId))
        drainSink()
    }

    /** Declare no attackers (skip combat). Sends empty selection then submits. */
    fun declareNoAttackers() {
        declareAttackers(emptyList())
    }

    /**
     * Send only the iterative DeclareAttackersResp (no Submit) — simulates a single
     * creature toggle click. Returns messages produced by the echo-back.
     */
    fun toggleAttackers(attackerInstanceIds: List<Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        session.onDeclareAttackers(declareAttackersResp(attackers = attackerInstanceIds))
        drainSink()
        return messagesSince(snap)
    }

    /**
     * Send SubmitAttackersReq (type=31, no payload) — the real client's "Done" button.
     *
     * In Arena's two-phase combat protocol, iterative creature toggles send
     * [DeclareAttackersResp] (type=30) with selection state, while the final
     * confirmation sends [SubmitAttackersReq] (type=31) which is **type-only,
     * no payload**. The server must use the last known selection.
     */
    fun submitAttackers() {
        session.onDeclareAttackers(submitAttackersReq(seatId))
        drainSink()
    }

    /**
     * Send DeclareAttackersResp with auto_declare=true — the "Attack All" button.
     *
     * In Arena, this is the iterative update that selects all qualified attackers
     * targeting the specified damage recipient. Should be followed by [submitAttackers].
     */
    fun declareAllAttackers() {
        session.onDeclareAttackers(declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2))
        drainSink()
    }

    /**
     * Declare blockers with assignments using two-phase Arena protocol:
     * 1. Send [DeclareBlockersResp] with assignments (iterative update)
     * 2. Send [SubmitBlockersReq] to finalize
     *
     * Each entry means "this blocker blocks that attacker."
     */
    fun declareBlockers(assignments: Map<Int, Int>) {
        session.onDeclareBlockers(declareBlockersResp(assignments))
        drainSink()

        session.onDeclareBlockers(submitBlockersReq(seatId))
        drainSink()
    }

    /** Declare no blockers (let all attackers through). Sends SubmitBlockersReq directly. */
    fun declareNoBlockers() {
        session.onDeclareBlockers(submitBlockersReq(seatId))
        drainSink()
    }

    // --- Targeting helpers ---

    /** Select targets by instanceId for a pending SelectTargetsReq. */
    fun selectTargets(targetInstanceIds: List<Int>) {
        session.onSelectTargets(selectTargetsResp(targets = targetInstanceIds))
        drainSink()
    }

    /** Cancel a pending targeting action (backs out of spell cast). */
    fun cancelAction() {
        session.onCancelAction(cancelActionReq())
        drainSink()
    }

    /**
     * Respond to a GroupReq (surveil/scry). Places specified instanceIds into the
     * "away" group (graveyard for surveil, bottom for scry). Remaining cards stay on top.
     *
     * @param awayInstanceIds cards to put into the away zone (group 1)
     * @param allInstanceIds all card instanceIds from the GroupReq (for the keep group)
     */
    fun respondToGroupReq(awayInstanceIds: List<Int>, allInstanceIds: List<Int>) {
        val keepIds = allInstanceIds.filter { it !in awayInstanceIds }
        val msg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.GroupResp_097b)
            .setGroupResp(
                GroupResp.newBuilder()
                    .addGroups(
                        Group.newBuilder()
                            .addAllIds(keepIds)
                            .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                            .setSubZoneType(SubZoneType.Top),
                    )
                    .addGroups(
                        Group.newBuilder()
                            .addAllIds(awayInstanceIds)
                            .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Graveyard)
                            .setSubZoneType(SubZoneType.None_a455),
                    )
                    .setGroupType(GroupType.Ordered),
            )
            .build()
        session.onGroupResp(msg)
        drainSink()
    }

    /**
     * Respond to a GroupReq for scry. Places specified instanceIds on the bottom
     * of library. Remaining cards stay on top.
     */
    fun respondToScry(bottomInstanceIds: List<Int>, allInstanceIds: List<Int>) {
        val topIds = allInstanceIds.filter { it !in bottomInstanceIds }
        val msg = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.GroupResp_097b)
            .setGroupResp(
                GroupResp.newBuilder()
                    .addGroups(
                        Group.newBuilder()
                            .addAllIds(topIds)
                            .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                            .setSubZoneType(SubZoneType.Top),
                    )
                    .addGroups(
                        Group.newBuilder()
                            .addAllIds(bottomInstanceIds)
                            .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                            .setSubZoneType(SubZoneType.Bottom),
                    )
                    .setGroupType(GroupType.Ordered),
            )
            .build()
        session.onGroupResp(msg)
        drainSink()
    }

    /** Cast a spell by card name. Returns false if card not in hand. */
    fun castSpellByName(cardName: String): Boolean {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return false
        val card = player.getZone(ZoneType.Hand).cards
            .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false

        val msg = performAction {
            actionType = ActionType.Cast
            instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            grpId = bridge.cards.findGrpIdByName(card.name) ?: 0
        }

        session.onPerformAction(msg)
        drainSink()
        return true
    }

    // --- Modal helpers ---

    /** Respond to a CastingTimeOptionsReq (modal choice) with selected grpIds. */
    fun respondModalChoice(selectedGrpIds: List<Int>) {
        session.onCastingTimeOptions(castingTimeOptionsResp(selectedGrpIds = selectedGrpIds))
        drainSink()
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
        val human = bridge.getPlayer(SeatId(seatId)) ?: return false
        return game().phaseHandler.playerTurn != human
    }
    fun isGameOver(): Boolean = game().isGameOver
    fun game(): Game = bridge.getGame()!!
    fun shutdown() = bridge.shutdown()

    internal fun drainSink() {
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
    }
}
