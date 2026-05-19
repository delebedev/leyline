package leyline.testkit

import forge.game.Game
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.config.AiConfig
import leyline.config.MatchConfig
import leyline.config.ServerConfig
import leyline.game.bundle.InvariantSelection
import leyline.game.bundle.MessageCounter
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
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
@Suppress("LargeClass") // Test harness grows linearly with prompt-type coverage; refactor is its own task.
class MatchFlowHarness(
    private val seed: Long = 42L,
    private val deckList: String? = null,
    validating: Boolean = true,
    private val validation: InvariantSelection = defaultValidation(validating),
    private val validationStrict: Boolean = true,
    private val matchConfig: MatchConfig =
        MatchConfig(
            ai = AiConfig(speed = 0.0),
            // Fail fast in tests. Local gameplay leaves the human bridge
            // timeout disabled; here the engine
            // responds in <100ms so aggressive timeouts surface hangs quickly.
            server =
                ServerConfig(
                    bridgeTimeoutMs = 5_000L,
                    aiTurnWaitMs = 2_000L,
                    mulliganWaitMs = 2_000L,
                ),
        ),
    private val variant: String? = null,
    /**
     * Bypass the YAML-fixture-backed [TestCardRegistry] when set. The harness
     * walks the deck list, asks Forge to load each card by name (failing
     * loudly if any card is unknown), and uses the supplied repository for
     * card-identity lookups instead of the fixture-derived in-memory store.
     *
     * Default null preserves the existing fixture-pinned behavior the
     * conformance suite relies on. SimClient passes a SQLite-backed repo so
     * arbitrary decks work without per-card fixtures.
     */
    private val cardRepositoryOverride: leyline.game.data.CardRepository? = null,
) {
    companion object {
        fun defaultValidation(validating: Boolean): InvariantSelection =
            if (validating) {
                InvariantSelection.protocolFacts()
            } else {
                InvariantSelection.none("legacy disabled validation flag")
            }
    }

    private val matchId = "test-match"
    private val seatId = SeatId(1)

    val registry = MatchRegistry()
    val sink = ListMessageSink()

    /** Validating decorator — null only when the selected validation set is empty. */
    val validatingSink: ValidatingMessageSink? =
        if (!validation.isEmpty()) ValidatingMessageSink(sink, strict = validationStrict, selection = validation) else null

    /** The [MessageSink] passed to [MatchSession] (validating wrapper or plain). */
    private val effectiveSink get() = validatingSink ?: sink

    val accumulator = ClientAccumulator()
    val allMessages = mutableListOf<GREToClientMessage>()

    /** All raw messages (SettingsResp, MatchCompleted, etc.) sent via [MessageSink.sendRaw]. */
    val allRawMessages = mutableListOf<MatchServiceToClientMessage>()

    /** When set, the next auto-accepted optional-action prompt declines instead.
     *  Use [declineNextOptionalAction] to set this. Cleared after one use. */
    private var nextOptionalResponse: OptionResponse? = null
    private var holdNextOptionalResponse = false
    private var nextNumericInputValue: Int? = null

    lateinit var session: MatchSession
        private set
    lateinit var bridge: GameBridge
        private set

    /** Start game, keep hand, advance to first real-action phase via MatchSession. */
    fun connectAndKeep(aiScript: List<ScriptedAction>? = null) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val repo: leyline.game.data.CardRepository =
            if (cardRepositoryOverride != null) {
                ensureForgeKnowsDeck(deckList)
                cardRepositoryOverride
            } else {
                TestCardRegistry.ensureRegistered()
                if (deckList != null) TestCardRegistry.ensureDeckRegistered(deckList)
                TestCardRegistry.repo
            }

        bridge =
            GameBridge(
                bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                matchConfig = matchConfig,
                messageCounter = MessageCounter(),
                cardRepository = repo,
            )
        bridge.priorityWaitMs = 2_000L
        bridge.start(seed = seed, deckList = deckList, variant = variant)

        session =
            MatchSession(
                connection =
                    ConnectionState(
                        seatId = seatId,
                        matchId = matchId,
                        sink = effectiveSink,
                        registry = registry,
                    ),
                gameBridge = bridge,
                paceDelayMs = 0,
            )
        registry.registerSession(matchId, seatId, session)

        // Seed accumulator + validator with a Full GSM BEFORE submitKeep.
        // At this point the engine is blocked at mulligan — safe to call
        // buildFromSnapshot without racing the engine thread's AI action capture.
        // After submitKeep, the engine runs (potentially AI-first) and concurrent
        // buildFromSnapshot calls would race on drainEvents/nextAnnotationId.
        val game = bridge.getGame()
        if (game != null) {
            val snap = GsmSnapshot.capture(game, bridge, matchId, 0)
            val fullResult = StateMapper.buildFromSnapshot(snap, 0, matchId, bridge, viewingSeatId = seatId.value)
            bridge.applyMutations(fullResult.mutations)
            accumulator.seedFull(fullResult.gsm)
            validatingSink?.seedFull(fullResult.gsm)
        }

        bridge.submitKeep(seatId)

        session.onMulliganKeep()
        drainSink()

        if (aiScript != null) installScriptedAi(aiScript)
    }

    /** Start puzzle game from classpath resource, advance to first action phase. */
    fun connectAndKeepPuzzle(
        resourcePath: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
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
    fun connectAndKeepPuzzleText(
        puzzleText: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
        // Card DB must init before PuzzleSource.loadFromText — the Puzzle
        // constructor triggers GameState.<clinit> which requires localization.
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleBridge(PuzzleSource.loadFromText(puzzleText), aiScript)
    }

    private fun startPuzzleBridge(
        puzzle: forge.gamemodes.puzzle.Puzzle,
        aiScript: List<ScriptedAction>?,
    ) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val repo: leyline.game.data.CardRepository =
            if (cardRepositoryOverride != null) {
                cardRepositoryOverride
            } else {
                TestCardRegistry.ensureRegistered()
                TestCardRegistry.repo
            }

        bridge =
            GameBridge(
                bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                matchConfig = matchConfig,
                messageCounter = MessageCounter(),
                cardRepository = repo,
            )
        bridge.priorityWaitMs = 2_000L
        bridge.startPuzzle(puzzle)
        // Fixture path: hydrate per-card YAML identity into the in-memory repo.
        // SQLite-override path: card identity comes from the supplied repository;
        // Forge's lazy script loader already pulled in every card the puzzle
        // parser referenced.
        if (cardRepositoryOverride == null) {
            TestCardRegistry.registerPuzzleCards(bridge.getGame()!!)
        }

        // Install scripted AI BEFORE onPuzzleStart — auto-pass will advance
        // through the human turn into the AI turn, where the script takes over.
        if (aiScript != null) {
            installScriptedAi(aiScript)
        }

        session =
            MatchSession(
                connection =
                    ConnectionState(
                        seatId = seatId,
                        matchId = matchId,
                        sink = effectiveSink,
                        registry = registry,
                    ),
                gameBridge = bridge,
                paceDelayMs = 0,
            )
        registry.registerSession(matchId, seatId, session)

        val game = bridge.getGame()
        if (game != null) {
            val snap = GsmSnapshot.capture(game, bridge, matchId, 0)
            val fullResult = StateMapper.buildFromSnapshot(snap, 0, matchId, bridge, viewingSeatId = seatId.value)
            bridge.applyMutations(fullResult.mutations)
            accumulator.seedFull(fullResult.gsm)
            validatingSink?.seedFull(fullResult.gsm)
        }

        session.onPuzzleStart()
        drainSink()
    }

    /**
     * Play a land from hand. Returns true if successful.
     *
     * @param name optional preferred land name. When set, plays that named
     *             land if present in hand; otherwise falls back to any land.
     *             Specifying intent here matters: tests that follow up with
     *             a coloured spell cast (e.g. Raging Goblin needs {R}) must
     *             play a same-coloured basic. Without intent, this picks the
     *             first land in hand, which is shuffle-dependent — and shuffle
     *             order shifts with upstream forge edition data because
     *             PaperCard.hashCode is printing-specific. A wrong-coloured
     *             land then makes the follow-up spell uncastable, autoPass
     *             advances through phases unblocked, and the turn counter
     *             skips past T1 before the test asserts.
     */
    fun playLand(name: String? = null): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val handCards = player.getZone(ZoneType.Hand).cards
        val land =
            (if (name != null) handCards.firstOrNull { it.isLand && it.name.equals(name, ignoreCase = true) } else null)
                ?: handCards.firstOrNull { it.isLand }
                ?: return false

        val msg =
            performAction {
                actionType = ActionType.Play_add3
                instanceId = bridge.getOrAllocInstanceId(ForgeCardId(land.id)).value
                grpId = bridge.cardRepository.findGrpIdByName(land.name) ?: 0
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val creature =
            player
                .getZone(ZoneType.Hand)
                .cards
                .firstOrNull { it.isCreature } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.getOrAllocInstanceId(ForgeCardId(creature.id)).value
                grpId = bridge.cardRepository.findGrpIdByName(creature.name) ?: 0
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Pass priority — sends a real Pass action through MatchSession. */
    fun passPriority() {
        session.onPerformAction(submitWithGsId(performAction { actionType = ActionType.Pass }))
        drainSink()
    }

    /**
     * Advance through whichever default client response is appropriate for
     * the current stop. Priority uses Pass; combat declaration prompts need
     * their own submit messages.
     */
    private fun advanceDefaultStop() {
        when (phase()) {
            "COMBAT_DECLARE_ATTACKERS" -> declareNoAttackers()
            "COMBAT_DECLARE_BLOCKERS" -> declareNoBlockers()
            else -> passPriority()
        }
    }

    /**
     * Keep passing until [stopWhen] becomes true, the game ends, or [maxPasses] is hit.
     *
     * Returns true when [stopWhen] was observed before the pass budget ran out.
     * Prefer this over fixed `repeat(N) { passPriority() }` loops in integration tests.
     */
    fun passUntil(
        maxPasses: Int = 20,
        stopWhen: MatchFlowHarness.() -> Boolean,
    ): Boolean {
        repeat(maxPasses) {
            if (stopWhen() || isGameOver()) return true
            advanceDefaultStop()
        }
        return stopWhen() || isGameOver()
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
    fun passUntilTurn(
        targetTurn: Int,
        maxPasses: Int = 30,
    ) {
        repeat(maxPasses) {
            if (turn() >= targetTurn || isGameOver()) return
            advanceDefaultStop()
        }
    }

    /**
     * Pass priority through remaining combat until the turn advances or game ends.
     * Replaces the verbose `repeat(15) { if (gameOver/nextTurn) return@repeat; passPriority() }` pattern.
     */
    fun passThroughCombat(
        startTurn: Int = turn(),
        maxPasses: Int = 15,
    ) {
        repeat(maxPasses) {
            if (isGameOver() || turn() > startTurn) return
            advanceDefaultStop()
        }
    }

    /**
     * Trigger autoPassAndAdvance directly — without submitting an action first.
     *
     * Use when the engine is already blocked at a combat phase (e.g.
     * COMBAT_DECLARE_BLOCKERS) and you need CombatHandler to send the
     * prompt message. Calling [passPriority] would submit Pass to the
     * combat pending, which is not what you want.
     */
    fun triggerAutoPass() {
        session.triggerAutoPass()
    }

    /**
     * Replace the AI seat's controller with a [ScriptedPlayerController].
     * Call after [connectAndKeep] — the AI player must already exist.
     * Returns the scripted controller for inspection.
     */
    fun installScriptedAi(script: List<ScriptedAction>): ScriptedPlayerController {
        val game = game()
        val aiPlayer =
            bridge.getPlayer(SeatId(2))
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
    fun advanceToPhase(
        phase: String,
        turn: Int? = null,
    ) = leyline.game.advanceToPhase(bridge, phase, turn)

    /** Advance to Main1 via bridge. */
    fun advanceToMain1() = leyline.game.advanceToMain1(bridge)

    /** Advance to COMBAT_DECLARE_ATTACKERS via bridge. */
    fun advanceToCombat(turn: Int? = null) = leyline.game.advanceToCombat(bridge, turn)

    /** Advance to MAIN2 via bridge. */
    fun advanceToMain2(turn: Int? = null) = leyline.game.advanceToMain2(bridge, turn)

    // --- Combat helpers ---

    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> {
        val player = bridge.getPlayer(seatId) ?: return emptyList()
        return player
            .getZone(ZoneType.Battlefield)
            .cards
            .filter { it.isCreature }
            .map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value to it.name }
    }

    /**
     * Declare attackers by instanceId using the two-phase Arena protocol:
     * 1. Send [DeclareAttackersResp] with selection (iterative update)
     * 2. Send [SubmitAttackersReq] to finalize (the "Done" button)
     */
    fun declareAttackers(attackerInstanceIds: List<Int>) {
        session.onDeclareAttackers(submitWithGsId(declareAttackersResp(attackers = attackerInstanceIds)))
        drainSink()

        session.onDeclareAttackers(submitWithGsId(submitAttackersReq(seatId.value)))
        drainSink()
    }

    /** Declare no attackers (skip combat). Sends empty selection then submits. */
    fun declareNoAttackers() {
        declareAttackers(emptyList())
    }

    /**
     * Send only the iterative DeclareAttackersResp (no Submit) — simulates an Arena
     * attacker-option toggle. Returns messages produced by the echo-back.
     */
    fun toggleAttackers(
        attackerInstanceIds: List<Int>,
        attackerAlternatives: Map<Int, Int> = emptyMap(),
    ): List<GREToClientMessage> {
        val snap = messageSnapshot()
        session.onDeclareAttackers(
            submitWithGsId(
                declareAttackersResp(
                    attackers = attackerInstanceIds,
                    attackerAlternatives = attackerAlternatives,
                ),
            ),
        )
        drainSink()
        return messagesSince(snap)
    }

    /**
     * Send SubmitAttackersReq (type=31, no payload) — the reference client's "Done" button.
     *
     * In the two-phase combat protocol, iterative creature toggles send
     * [DeclareAttackersResp] (type=30) with selection state, while the final
     * confirmation sends [SubmitAttackersReq] (type=31) which is **type-only,
     * no payload**. The server must use the last known selection.
     */
    fun submitAttackers() {
        session.onDeclareAttackers(submitWithGsId(submitAttackersReq(seatId.value)))
        drainSink()
    }

    /**
     * Send DeclareAttackersResp with auto_declare=true — the "Attack All" button.
     *
     * In Arena, this is the iterative update that selects all qualified attackers
     * targeting the specified damage recipient. Should be followed by [submitAttackers].
     */
    fun declareAllAttackers() {
        session.onDeclareAttackers(submitWithGsId(declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2)))
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
        session.onDeclareBlockers(submitWithGsId(declareBlockersResp(assignments)))
        drainSink()

        session.onDeclareBlockers(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    /** Declare no blockers (let all attackers through). Sends SubmitBlockersReq directly. */
    fun declareNoBlockers() {
        session.onDeclareBlockers(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    /**
     * Send only the iterative DeclareBlockersResp (no Submit) — simulates a single
     * blocker assignment click. Returns messages produced by the echo-back.
     */
    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        session.onDeclareBlockers(submitWithGsId(declareBlockersResp(assignments)))
        drainSink()
        return messagesSince(snap)
    }

    /**
     * Send DeclareBlockersResp deselecting a single blocker (wire shape: Blocker
     * entry with blockerInstanceId and empty selectedAttackerInstanceIds).
     */
    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> {
        val snap = messageSnapshot()
        session.onDeclareBlockers(submitWithGsId(declareBlockersRespDeselect(blockerInstanceId)))
        drainSink()
        return messagesSince(snap)
    }

    /**
     * Send SubmitBlockersReq (type-only, no payload) — the reference client's "Done" button.
     */
    fun submitBlockers() {
        session.onDeclareBlockers(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    // --- Damage assignment helpers ---

    /**
     * Send AssignDamageResp with damage assignments.
     *
     * @param assigners list of (attackerInstanceId, assignments) where assignments
     *                  is a list of (blockerOrDefenderInstanceId, damage)
     */
    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) {
        session.onAssignDamage(submitWithGsId(assignDamageResp(assigners)))
        drainSink()
    }

    // --- Targeting helpers ---

    /**
     * Full two-phase target selection: SelectTargetsResp (phase 1) + SubmitTargetsReq (phase 2).
     *
     * Convenience wrapper — sends both messages so existing tests don't need to change.
     * Use [selectTargetsIterative] + [submitTargets] for phase-by-phase control.
     */
    fun selectTargets(targetInstanceIds: List<Int>) {
        session.onSelectTargets(submitWithGsId(selectTargetsResp(targets = targetInstanceIds)))
        drainSink()
        session.onSubmitTargets(submitWithGsId(submitTargetsReq()))
        drainSink()
    }

    /**
     * Phase 1 only: send SelectTargetsResp without SubmitTargetsReq.
     * Use to inspect the echo-back re-prompt before confirming.
     */
    fun selectTargetsIterative(targetInstanceIds: List<Int>) {
        session.onSelectTargets(submitWithGsId(selectTargetsResp(targets = targetInstanceIds)))
        drainSink()
    }

    /** Phase 2: send SubmitTargetsReq — the client's "Done" button. */
    fun submitTargets() {
        session.onSubmitTargets(submitWithGsId(submitTargetsReq()))
        drainSink()
    }

    /** Cancel a pending targeting action (backs out of spell cast). */
    fun cancelAction() {
        session.onCancelAction(submitWithGsId(cancelActionReq()))
        drainSink()
    }

    /**
     * Respond to a GroupReq (surveil/scry). Places specified instanceIds into the
     * "away" group (graveyard for surveil, bottom for scry). Remaining cards stay on top.
     *
     * @param awayInstanceIds cards to put into the away zone (group 1)
     * @param allInstanceIds all card instanceIds from the GroupReq (for the keep group)
     */
    fun respondToGroupReq(
        awayInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val keepIds = allInstanceIds.filter { it !in awayInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(keepIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(awayInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Graveyard)
                                .setSubZoneType(SubZoneType.None_a455),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        session.onGroupResp(submitWithGsId(msg))
        drainSink()
    }

    /**
     * Respond to a GroupReq for scry. Places specified instanceIds on the bottom
     * of library. Remaining cards stay on top.
     */
    fun respondToScry(
        bottomInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val topIds = allInstanceIds.filter { it !in bottomInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(topIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(bottomInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Bottom),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        session.onGroupResp(submitWithGsId(msg))
        drainSink()
    }

    /**
     * Cast a spell by card name from the given [zone] (default: Hand).
     * For flashback/escape, use `zone = ZoneType.Graveyard`.
     * Returns false if card not found in the zone.
     */
    fun castSpellByName(
        cardName: String,
        zone: ZoneType = ZoneType.Hand,
        alternativeGrpId: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(zone)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                if (alternativeGrpId != 0) this.alternativeGrpId = alternativeGrpId
            }

        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    /** Alias for `castSpellByName(cardName, ZoneType.Graveyard)`. */
    fun castFromGraveyard(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Graveyard)

    /** Alias for `castSpellByName(cardName, ZoneType.Exile)`. */
    fun castFromExile(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Exile)

    /**
     * Cast a spell and pass once to resolve it.
     *
     * Use only for spells that do not require an interactive client response
     * (no targeting, grouping, modal, or SelectN prompt).
     */
    fun resolveSpell(cardName: String): Boolean {
        if (!castSpellByName(cardName)) return false
        passPriority()
        return true
    }

    /**
     * Cast a spell, run any required follow-up advancement, and return the
     * latest prompt message matching [extract].
     *
     * Keeps flow tests focused on protocol assertions instead of the repeated
     * cast -> advance -> scan message log sequence.
     */
    fun <T> castSpellUntil(
        cardName: String,
        promptName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = {},
        extract: (GREToClientMessage) -> T?,
    ): T {
        check(castSpellByName(cardName)) { "Could not cast $cardName" }
        advanceAfterCast()
        return allMessages.asReversed().firstNotNullOfOrNull(extract)
            ?: error("Expected $promptName after casting $cardName")
    }

    fun castSpellUntilGroupReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): GroupReq =
        castSpellUntil(cardName, promptName = "GroupReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasGroupReq()) msg.groupReq else null
        }

    fun castSpellUntilSelectNReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): SelectNReq =
        castSpellUntil(cardName, promptName = "SelectNReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasSelectNReq()) msg.selectNReq else null
        }

    fun castSpellUntilCastingTimeOptionsReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = { passPriority() },
    ): CastingTimeOptionsReq =
        castSpellUntil(cardName, promptName = "CastingTimeOptionsReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasCastingTimeOptionsReq()) msg.castingTimeOptionsReq else null
        }

    /**
     * Activate a non-mana ability on a battlefield card by name and ability index.
     *
     * @param cardName name of the card on the battlefield
     * @param abilityIndex 0-based index into the card's non-mana activated abilities
     *                     (e.g., planeswalker: 0=first loyalty, 1=second, 2=ultimate)
     * @return true if the card was found and action sent
     */
    fun activateAbility(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        return submitActivateAction(card, abilityIndex)
    }

    /** Activate an ability on a card in the player's hand (Channel, Cycling, etc.). */
    fun activateAbilityFromHand(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(ZoneType.Hand)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        return submitActivateAction(card, abilityIndex)
    }

    /** Activate an ability on a card in the player's graveyard (Unearth, Embalm, Eternalize). */
    fun activateAbilityFromGraveyard(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(ZoneType.Graveyard)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        return submitActivateAction(card, abilityIndex)
    }

    /** Common Activate_add3 submission for both battlefield and hand cards. */
    private fun submitActivateAction(
        card: forge.game.card.Card,
        abilityIndex: Int,
    ): Boolean {
        val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
        val cardData = bridge.cardRepository.findByGrpId(grpId)
        val ability = bridge.getPlayer(seatId)?.let { getNonManaActivatedAbilities(card, it).getOrNull(abilityIndex) }
        val abilityGrpId =
            if (cardData != null && ability != null) {
                bridge.abilityRegistryFor(card, cardData)?.forSpellAbility(ability.id) ?: 0
            } else {
                0
            }

        val msg =
            performAction {
                actionType = ActionType.Activate_add3
                instanceId = iid
                this.grpId = grpId
                this.abilityGrpId = abilityGrpId
            }
        session.onPerformAction(submitWithGsId(msg))
        drainSink()
        return true
    }

    // --- SelectN helpers ---

    /**
     * Respond to a SelectNReq (legend rule, "choose N" prompts) with selected instanceIds.
     *
     * @param selectedInstanceIds the instanceIds the player chose (e.g. the legendary to keep)
     */
    fun respondToSelectN(selectedInstanceIds: List<Int>) {
        session.onSelectN(submitWithGsId(selectNResp(ids = selectedInstanceIds)))
        drainSink()
    }

    fun respondToSearch(itemsFound: List<Int>) {
        session.onSearch(submitWithGsId(searchResp(itemsFound)))
        drainSink()
    }

    fun respondToEffectCost(selectedInstanceIds: List<Int>) {
        session.onEffectCost(submitWithGsId(effectCostResp(selectedInstanceIds)))
        drainSink()
    }

    // --- Modal helpers ---

    /** Respond to a CastingTimeOptionsReq (modal choice) with selected grpIds. */
    fun respondModalChoice(selectedGrpIds: List<Int>) {
        session.onCastingTimeOptions(submitWithGsId(castingTimeOptionsResp(selectedGrpIds = selectedGrpIds)))
        drainSink()
    }

    // --- Optional cost helpers ---

    /** Respond to a CastingTimeOptionsReq with the given ctoId (kicker, buyback). */
    fun respondToOptionalCost(ctoId: Int) {
        session.onCastingTimeOptions(submitWithGsId(optionalCostResp(ctoId)))
        drainSink()
    }
    // --- Message inspection ---

    /** Snapshot current message count for later comparison with [messagesSince]. */
    fun messageSnapshot(): Int = allMessages.size

    /** Get all messages since a snapshot point. */
    fun messagesSince(snapshot: Int): List<GREToClientMessage> = allMessages.subList(snapshot, allMessages.size).toList()

    /** Get all game-state messages since a snapshot point. */
    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> =
        messagesSince(snapshot)
            .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

    /** Get all annotations from game-state messages since a snapshot point. */
    fun annotationsSince(snapshot: Int): List<AnnotationInfo> = gameStateMessagesSince(snapshot).flatMap { it.annotationsList }

    // --- State queries ---

    fun phase(): String? = game().phaseHandler.phase?.name

    fun turn(): Int = game().phaseHandler.turn

    fun isAiTurn(): Boolean {
        val human = bridge.getPlayer(seatId) ?: return false
        return game().phaseHandler.playerTurn != human
    }

    fun isGameOver(): Boolean {
        val game = bridge.getGame()
        if (game != null) return game.isGameOver

        if (
            allMessages.any {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasGameInfo() &&
                    it.gameStateMessage.gameInfo.stage == GameStage.GameOver
            }
        ) {
            return true
        }

        return allRawMessages.any {
            it.hasMatchGameRoomStateChangedEvent() &&
                it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                MatchGameRoomStateType.MatchCompleted
        }
    }

    fun game(): Game = bridge.getGame()!!

    /**
     * True when the seat's [GameActionBridge] has a pending action awaiting
     * the client's response. False means the engine isn't blocked on us — any
     * submit we make will trigger
     * `WARN ActionPerformer: PerformActionResp but no pending action` and a
     * spurious state resync. Use as a guard before `session.onPerformAction`
     * in long-running drivers (simclient) where the auto-pass loop frequently
     * advances past priority windows between observe and submit.
     */
    fun hasPendingAction(seat: SeatId = seatId): Boolean = bridge.actionBridge(seat).getPending() != null

    fun shutdown() = bridge.shutdown()

    // --- Real-client gsId reflection ---
    //
    // A real client reflects the gsId of the latest prompt-bearing GRE it
    // has received on every response it sends. The harness used to leave
    // `gameStateId` at proto default 0, which short-circuited the production
    // staleness check (`clientGsId != 0 && ...`) and meant no testGate test
    // exercised it.
    //
    // [submitWithGsId] fills the field by scanning [allMessages] — the
    // drained record of what the harness has *seen*, mirroring a real
    // client's TCP receive view. Reading from `bridge.messageCounter.
    // lastPromptGsId()` directly would race against the engine thread:
    // the engine can emit a new prompt between the harness's read and the
    // session's processing of the response, leaving the response stamped
    // with an old gsId that the staleness predicate then rejects (observed
    // on CI under load, never reproduces locally because the engine drains
    // synchronously fast enough to hide the race).
    //
    // Tests that need to send an explicit (or stale) gsId can pass a
    // non-zero `gameStateId` on the inbound message; the wrapper leaves
    // those untouched.

    /**
     * gsId of the most recent prompt-bearing GRE the harness has drained.
     * 0 pre-handshake or before any prompt has been received.
     *
     * Walks [allMessages] in reverse — that's the harness's view of what
     * the "client" has seen. Deliberately does not consult
     * `bridge.messageCounter.lastPromptGsId()`: the bridge counter is
     * shared mutable state advanced from the engine thread, so reading it
     * races against in-flight emissions.
     */
    fun latestPromptGsId(): Int {
        for (i in allMessages.indices.reversed()) {
            val m = allMessages[i]
            if (m.type in leyline.game.bundle.PROMPT_GRE_TYPES) return m.gameStateId
        }
        return 0
    }

    /**
     * Reflect the latest prompt gsId onto a client message before it enters
     * the session, mirroring real-client behaviour. Pass-through when the
     * message already carries an explicit non-zero gsId (used by tests that
     * need to drive a stale or pre-handshake submission deliberately).
     *
     * `internal` so cross-package drivers in the same module (notably
     * [leyline.simclient.SimClientDriver]) can route their direct
     * `session.on*` calls through it instead of bypassing reflection.
     */
    internal fun submitWithGsId(msg: ClientToGREMessage): ClientToGREMessage =
        if (msg.gameStateId == 0) {
            msg.toBuilder().setGameStateId(latestPromptGsId()).build()
        } else {
            msg
        }

    /**
     * Walk a deck list and ask Forge's static data to load every unique card
     * by name. Required when [cardRepositoryOverride] is in play — without
     * the YAML-fixture path, nothing else routes through
     * [forge.StaticData.attemptToLoadCard], and the engine fails opaquely
     * when a deck contains a card it has never seen.
     *
     * Fails loudly with the offending card name when Forge cannot resolve it,
     * matching the simclient policy: a deck either runs end-to-end or it
     * doesn't run at all.
     *
     * **simclient-only contract.** The `synchronized(StaticData.instance())`
     * block is safe under `:simclient`'s `maxParallelForks = 1` — Forge's
     * static `MyRandom` already forces serial execution there. Do NOT reuse
     * this override path from a parallelised gradle test task; the lock would
     * still hold but threads would silently serialise on it. If a non-simclient
     * caller needs SQLite-backed card resolution, factor a thread-safe
     * resolver out first.
     */
    private fun ensureForgeKnowsDeck(deckList: String?) {
        val list = deckList ?: return
        val sectionHeader = Regex("""^\[.+]$|^(Deck|Sideboard|Maybeboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)
        val names =
            list
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { !sectionHeader.matches(it) }
                // Strip leading `<count> ` and trailing Arena-export suffix
                // ` (SET) NNN` (e.g. `4 Diregraf Ghoul (FDN) 171` → `Diregraf Ghoul`).
                // Mirrors `DeckLoader.parseDeckList` — without this, the
                // validator looks up `Diregraf Ghoul (FDN) 171` verbatim and
                // every Arena-export deck spuriously fails to resolve.
                .map {
                    it
                        .replaceFirst(Regex("^\\d+\\s+"), "")
                        .replace(Regex("""\s*\([A-Z0-9]+\)\s*\d*\s*$"""), "")
                        .trim()
                }.distinct()
        val db =
            forge.model.FModel
                .getMagicDb()
                ?.commonCards
                ?: error("Forge card DB not initialised — call GameBootstrap.initializeCardDatabase first")
        val missing = mutableListOf<String>()
        synchronized(forge.StaticData.instance()) {
            for (name in names) {
                if (db.getCard(name) != null) continue
                forge.StaticData.instance().attemptToLoadCard(name)
                if (db.getCard(name) == null) missing.add(name)
            }
        }
        check(missing.isEmpty()) {
            "Forge cannot resolve cards in deck list: ${missing.joinToString()}. " +
                "Either the names are mistyped or Forge's card DB does not include them."
        }
    }

    internal fun drainSink() {
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()

        // Auto-respond to engine-initiated prompts so the engine can continue.
        // Loops because chained prompts (e.g. Wildborn Preserver: optional
        // accept → numeric input → potentially another optional on the next
        // resolution step) need every step responded to within one drain.
        // Each helper returns whether it actually fired; loop while any did.
        do {
            val acted = autoRespondToOptionalAction() || autoRespondToNumericInput()
        } while (acted)
    }

    private fun autoRespondToOptionalAction(): Boolean {
        val wpc = bridge.humanController ?: return false
        wpc.pendingOptionalAction ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e } ?: return false
        if (holdNextOptionalResponse) {
            holdNextOptionalResponse = false
            return false
        }

        // If a test pre-seeded a one-shot response via [declineNextOptionalAction],
        // use it and clear the slot. Otherwise default to AllowYes to keep existing
        // tests unblocked.
        val response = nextOptionalResponse ?: OptionResponse.AllowYes
        nextOptionalResponse = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(response),
                ).build()
        session.onOptionalActionResp(greMsg)

        // Drain follow-up messages without recursing
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
        return true
    }

    /**
     * Pre-seed the next auto-accepted optional-action prompt to be declined instead.
     * One-shot; cleared after the next OptionalActionMessage is auto-responded to.
     * Use when exercising decline branches (e.g. Madness "put in graveyard" path).
     */
    fun declineNextOptionalAction() {
        nextOptionalResponse = OptionResponse.CancelNo
    }

    /** Leave the next OptionalActionMessage pending so the test can call [respondToOptionalAction]. */
    fun holdNextOptionalAction() {
        holdNextOptionalResponse = true
    }

    private fun autoRespondToNumericInput(): Boolean {
        val wpc = bridge.humanController ?: return false
        wpc.pendingNumericInput ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e } ?: return false

        val value = nextNumericInputValue ?: 0
        nextNumericInputValue = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        session.onNumericInputResp(greMsg)

        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
        return true
    }

    /**
     * Pre-seed the next auto-responded NumericInputReq with [value].
     * One-shot; cleared after the next response. Default (no pre-seed) is `0`.
     */
    fun nextNumericInput(value: Int) {
        nextNumericInputValue = value
    }

    /**
     * Respond to a NumericInputReq with [value] explicitly.
     * For tests that need direct control over the numeric pick.
     */
    fun respondToNumericInput(value: Int) {
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        session.onNumericInputResp(greMsg)
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
    }

    /**
     * Respond to an OptionalActionMessage with Accept or Decline.
     * For tests that need explicit control over the optional decision.
     */
    fun respondToOptionalAction(accept: Boolean) {
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(if (accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
                ).build()
        session.onOptionalActionResp(greMsg)
        allMessages.addAll(sink.messages)
        allRawMessages.addAll(sink.rawMessages)
        accumulator.processAll(sink.messages)
        sink.clear()
    }
}
