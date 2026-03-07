package leyline.game

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.GameType
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.gamemodes.puzzle.Puzzle
import forge.player.PlayerControllerHuman
import forge.util.MyRandom
import leyline.bridge.DeckLoader
import leyline.bridge.GameActionBridge
import leyline.bridge.GameBootstrap
import leyline.bridge.GameLoopController
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.MulliganBridge
import leyline.bridge.MulliganPhase
import leyline.bridge.PhaseStopProfile
import leyline.bridge.PrioritySignal
import leyline.bridge.WebPlayerController
import leyline.config.MatchConfig
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import java.lang.reflect.InvocationTargetException
import java.util.Random

/**
 * Bridges the client protocol to a real Forge [forge.game.Game] engine.
 *
 * Creates a constructed game (human seat 1 + AI seat 2), starts the game loop,
 * and blocks until the engine reaches mulligan. The client handler reads hands
 * and submits keep/mull decisions through this bridge.
 *
 * Internally composed of focused components:
 * - [InstanceIdRegistry] — Forge cardId ↔ client instanceId bimap
 * - [LimboTracker] — retired instanceId history
 * - [DiffSnapshotter] — zone tracking + state snapshots for diff computation
 *
 * Threading: [start] blocks the caller (~2-3s first call for card DB, <100ms after).
 * The engine thread blocks at mulligan via [leyline.bridge.MulliganBridge].
 */
class GameBridge(
    /** Timeout for action bridge / prompt bridge / mulligan bridge.
     *  Production: 120s. Tests: ~2-5s (engine responds in <100ms). */
    private val bridgeTimeoutMs: Long = 120_000L,
    /** Playtest config — controls AI speed, die roll, etc. */
    val matchConfig: MatchConfig = MatchConfig(),
    /** Shared protocol counter for GRE message sequencing.
     *  Production: shared with MatchSession. Tests: local default. */
    val messageCounter: MessageCounter = MessageCounter(),
    /** Card data repository — lookups for grpId ↔ name, card metadata.
     *  Swapped to [InMemoryCardRepository] on puzzle hot-swap. */
    var cards: CardRepository = InMemoryCardRepository(),
    /** Proto builder for GameObjectInfo — uses [cards] for static card data.
     *  Rebuilt on puzzle hot-swap to reference the new repo. */
    var cardProto: CardProtoBuilder = CardProtoBuilder(cards),
) : IdMapping,
    PlayerLookup,
    ZoneTracking,
    StateSnapshot,
    AnnotationIds,
    EventDrain {
    private val log = LoggerFactory.getLogger(GameBridge::class.java)

    private var game: Game? = null
    private var loopController: GameLoopController? = null

    /** Shared signal — bridges notify when they have a pending item, replacing poll loops. */
    val prioritySignal = PrioritySignal()

    /** seat 1 = human. autoKeep driven by config skipMulligan. AI uses its default controller. */
    private val seat1MulliganBridge = MulliganBridge(
        autoKeep = matchConfig.game.skipMulligan,
        timeoutMs = bridgeTimeoutMs,
    )

    /** Action bridge for seat 1 — blocks engine at priority stops. */
    val actionBridge = GameActionBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)

    /** Human player's controller — set during [start]/[startFromPuzzle] for debug observability. */
    var humanController: WebPlayerController? = null
        private set

    /** Prompt bridge for seat 1 — blocks engine on targeting/choice prompts. */
    val promptBridge = InteractivePromptBridge(timeoutMs = bridgeTimeoutMs, prioritySignal = prioritySignal)

    /** AI action playback — captures per-action state diffs via EventBus. Null before start(). */
    var playback: GamePlayback? = null
        private set

    /** Event collector — captures Forge engine events for annotation building. Null before start(). */
    var eventCollector: GameEventCollector? = null
        private set

    /** Phase stop profile — controls which phases the engine stops at per player. Null before start(). */
    var phaseStopProfile: PhaseStopProfile? = null
        private set

    // --- Composed components ---

    /** Card ID mapping (Forge cardId ↔ client instanceId). */
    val ids = InstanceIdRegistry()

    /** Retired instanceId history (Limbo zone). */
    val limbo = LimboTracker()

    /** Zone tracking + state snapshots for diff computation. */
    val diff = DiffSnapshotter(ids)

    /** Monotonic annotation ID counter. Real server starts around 49; we start at 50. */
    private var nextAnnotationId = INITIAL_ANNOTATION_ID

    override fun nextAnnotationId(): Int = nextAnnotationId++

    /** Monotonic persistent annotation ID counter. Real server uses a separate sequence. */
    private var nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID

    override fun nextPersistentAnnotationId(): Int = nextPersistentAnnotationId++

    // --- Persistent annotation store ---
    // Persistent annotations (e.g. Attachment) carry forward across GSMs until explicitly deleted.

    private val activePersistentAnnotations = mutableMapOf<Int, wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
    private val pendingPersistentDeletions = mutableListOf<Int>()

    fun addPersistentAnnotation(ann: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo) {
        activePersistentAnnotations[ann.id] = ann
    }

    fun removePersistentAnnotation(id: Int) {
        activePersistentAnnotations.remove(id)
        pendingPersistentDeletions.add(id)
    }

    fun drainPersistentDeletions(): List<Int> =
        pendingPersistentDeletions.toList().also { pendingPersistentDeletions.clear() }

    fun getAllPersistentAnnotations(): List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> =
        activePersistentAnnotations.values.toList()

    /** Find persistent annotation by type and aura instanceId in affectedIds. */
    fun findPersistentAnnotationByAura(auraIid: Int): Int? =
        activePersistentAnnotations.entries.firstOrNull { (_, ann) ->
            ann.typeList.any { it == wotc.mtgo.gre.external.messaging.Messages.AnnotationType.Attachment } &&
                ann.affectedIdsList.contains(auraIid)
        }?.key

    /** Find persistent Counter annotation for the same instanceId and counter_type. */
    fun findPersistentCounter(instanceId: Int, counterType: Int): Int? =
        activePersistentAnnotations.entries.firstOrNull { (_, ann) ->
            ann.typeList.any { it == wotc.mtgo.gre.external.messaging.Messages.AnnotationType.Counter_803b } &&
                ann.affectedIdsList.contains(instanceId) &&
                ann.detailsList.any { it.key == "counter_type" && it.valueInt32Count > 0 && it.getValueInt32(0) == counterType }
        }?.key

    // --- Interface implementations (IdMapping, PlayerLookup, ZoneTracking, etc.) ---

    override fun getOrAllocInstanceId(forgeCardId: Int): Int = ids.getOrAlloc(forgeCardId)

    override fun reallocInstanceId(forgeCardId: Int): InstanceIdRegistry.IdReallocation = ids.realloc(forgeCardId)

    override fun getForgeCardId(instanceId: Int): Int? = ids.getForgeCardId(instanceId)

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun getInstanceIdMap(): Map<Int, Int> = ids.snapshot()

    /** Read-only snapshot of forgeCardId → current active instanceId. */
    fun getActiveInstanceIdMap(): Map<Int, Int> = ids.activeSnapshot()

    /** Set of instanceIds currently retired to Limbo. */
    fun getLimboSet(): Set<Int> = limbo.all().toSet()

    /** Proto zone tracking — where the protocol last placed each instanceId. */
    fun getProtoZones(): Map<Int, Int> = diff.allZones()

    override fun retireToLimbo(instanceId: Int) = limbo.retire(instanceId)

    override fun getLimboInstanceIds(): List<Int> = limbo.all()

    override fun recordZone(instanceId: Int, zoneId: Int): Int? = diff.recordZone(instanceId, zoneId)

    override fun getPreviousZone(instanceId: Int): Int? = diff.getPreviousZone(instanceId)

    override fun snapshotState(state: GameStateMessage) {
        diff.snapshotState(state)
    }

    override fun getPreviousState(): GameStateMessage? = diff.getPreviousState()

    override fun updateLastSentTurnInfo(gsm: GameStateMessage) {
        diff.updateLastSentTurnInfo(gsm)
    }

    override fun isPhaseChangedFromLastSent(currentTurnInfo: TurnInfo): Boolean =
        diff.isPhaseChangedFromLastSent(currentTurnInfo)

    fun clearPreviousState() = diff.clear()

    override fun drainEvents(): List<GameEvent> = eventCollector?.drainEvents() ?: emptyList()

    companion object {
        /** Fallback grpId for cards not in client DB (renders face-down). */
        const val FALLBACK_GRPID = 0

        /** Fallback deck when no config/decklist is provided (tests, legacy). */
        private const val FALLBACK_DECK = """
20 Llanowar Elves
4 Elvish Mystic
4 Giant Growth
32 Forest
"""

        /** Annotation IDs start at 50 to avoid collision with persistent annotation IDs. */
        private const val INITIAL_ANNOTATION_ID = 50

        /** Persistent annotation IDs start at 1. */
        private const val INITIAL_PERSISTENT_ANNOTATION_ID = 1

        /** Max time to wait for engine to reach mulligan after start/mull. */
        private const val MULLIGAN_WAIT_MS = 10_000L
        private const val DEFAULT_PRIORITY_WAIT_MS = 15_000L

        /** Longer timeout for AI turns (AI plays full turn: lands, spells, combat). */
        const val AI_TURN_WAIT_MS = 30_000L

        /** Short settle delay after detecting pending state — lets engine thread finish
         *  in-flight zone moves before we snapshot. */
        private const val SETTLE_MS = 10L

        /** Poll interval for mulligan ready check (no signal available for mulligan). */
        private const val POLL_INTERVAL_MS = 50L
    }

    /**
     * How long [awaitPriority] waits for the engine to reach a priority stop.
     * Production default: 15s. Tests should use ~2s since the engine responds
     * in <100ms and the extra headroom only delays timeout-based test failures.
     */
    var priorityWaitMs: Long = DEFAULT_PRIORITY_WAIT_MS

    /**
     * Wrap an existing [Game] without starting the engine loop thread.
     *
     * The game should already be at the desired phase (via `devModeSet`).
     * Cards should already be in zones (via `Zone.add`). This method wires
     * only the components needed for proto output: [GameEventCollector] for
     * annotations, [InstanceIdRegistry] for card IDs.
     *
     * No bridges, no threads, no CompletableFuture — fully synchronous.
     * Forge events fire inline when you call `game.action.*` methods.
     *
     * Use in conformance tests where you need [StateMapper] + [BundleBuilder]
     * but don't need the game loop or player interaction.
     */
    fun wrapGame(g: Game) {
        game = g
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
    }

    /**
     * Initialize card DB, create game, start engine loop, wait for mulligan.
     * Blocks caller until engine has dealt hands and is waiting for keep/mull.
     *
     * @param seed if non-null, seeds the RNG for deterministic shuffles (tests/replays)
     * @param deckList1 decklist text for seat 1 (human). Falls back to [deckList] or built-in fallback.
     * @param deckList2 decklist text for seat 2 (AI). Falls back to [deckList1].
     * @param deckList legacy single-deck param for tests — applies to both seats.
     */
    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
    ) {
        log.info("GameBridge: initializing card database")
        GameBootstrap.initializeCardDatabase()

        if (seed != null) {
            log.info("GameBridge: using deterministic seed={}", seed)
            MyRandom.setRandom(Random(seed))
        } else {
            log.info("GameBridge: using random seed")
        }

        val seat1Str = (deckList1 ?: deckList ?: FALLBACK_DECK).trimIndent()
        val seat2Str = (deckList2 ?: deckList ?: seat1Str).trimIndent()
        val deck1 = DeckLoader.parseDeckList(seat1Str)
        val deck2 = DeckLoader.parseDeckList(seat2Str)
        log.info(
            "GameBridge: parsed decks (seat1={} cards, seat2={} cards)",
            deck1.main.countAll(),
            deck2.main.countAll(),
        )

        val g = GameBootstrap.createConstructedGame(deck1, deck2)
        game = g

        // Wire WebPlayerController for seat 1 (human) with mulligan bridge
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        phaseStopProfile = PhaseStopProfile.createDefaults(human.id, aiPlayer.id)
        val controller = WebPlayerController(
            game = g,
            player = human,
            lobbyPlayer = human.lobbyPlayer,
            bridge = this.promptBridge,
            actionBridge = actionBridge,
            mulliganBridge = seat1MulliganBridge,
            phaseStopProfile = phaseStopProfile,
        )
        humanController = controller
        human.addController(Long.MAX_VALUE - 1, human, controller, false)

        // AI keeps its default controller — handles priority, combat, etc. natively

        val loop = GameLoopController(
            g,
            actionBridges = listOf(actionBridge),
            promptBridges = listOf(promptBridge),
            mulliganBridges = listOf(seat1MulliganBridge),
        )
        loopController = loop
        loop.start()
        loop.awaitStarted()

        // Register event collector FIRST — must fire before playback so drainEvents()
        // includes the current event when playback's captureAndPause runs.
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
        log.info("GameBridge: registered GameEventCollector for event-driven annotations")

        // Register AI action playback subscriber (after collector)
        val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
        playback = pb
        g.subscribeToEvents(pb)
        log.info("GameBridge: registered GamePlayback for AI action streaming")

        if (matchConfig.game.skipMulligan) {
            log.info("GameBridge: skipMulligan — engine auto-kept, waiting for priority")
            awaitPriority()
            log.info("GameBridge: engine reached priority after auto-keep")
        } else {
            log.info("GameBridge: game loop started, waiting for mulligan")
            awaitMulliganReady()
            log.info("GameBridge: engine reached mulligan, hand ready")
        }
    }

    /** Get the current hand for a seat as client grpIds. */
    fun getHandGrpIds(seatId: Int): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.map { card ->
            cards.findGrpIdByName(card.name) ?: FALLBACK_GRPID
        }
    }

    /** Full deck as client grpIds (for initial bundle deck message). */
    fun getDeckGrpIds(seatId: Int): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        // Combine library + hand + any other zones to reconstruct full deck
        val allCards = mutableListOf<String>()
        for (zone in listOf(ZoneType.Library, ZoneType.Hand)) {
            player.getZone(zone).cards.forEach { allCards.add(it.name) }
        }
        return allCards.map { cards.findGrpIdByName(it) ?: FALLBACK_GRPID }
    }

    override fun getGame(): Game? = game

    override fun getPlayer(seatId: Int): Player? {
        val g = game ?: return null
        return if (seatId == 1) {
            g.players.firstOrNull { it.lobbyPlayer !is LobbyPlayerAi }
        } else {
            g.players.firstOrNull { it.lobbyPlayer is LobbyPlayerAi }
        }
    }

    /**
     * Block until the engine reaches a priority stop (via [GameActionBridge]).
     * After keep, the engine auto-advances through Beginning → Main1.
     */
    fun awaitPriority() = awaitPriorityWithTimeout(priorityWaitMs)

    /**
     * Block until the engine reaches a priority stop, an interactive prompt
     * is pending, or the game ends.
     *
     * Uses [PrioritySignal] (semaphore-based) instead of polling — both
     * [GameActionBridge] and [InteractivePromptBridge] signal when they post
     * a pending item, so we wake up immediately with no 50ms poll latency.
     *
     * The prompt check is needed because targeted spells (e.g. Giant Growth)
     * block the engine thread in [InteractivePromptBridge.requestChoice] before
     * the next action-bridge priority stop is reached. Without this, casting a
     * targeted spell would appear to time out.
     *
     * @param timeoutMs max wait time (use longer values for AI turns)
     * @return true if priority was reached, false if timed out or game over
     */
    fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // Check conditions first (handles already-pending case)
            val g = game
            if (g != null && g.isGameOver) {
                log.info("GameBridge: game over detected while waiting for priority")
                return false
            }
            if (actionBridge.getPending() != null) {
                // Let engine thread finish in-flight zone moves before we snapshot state
                Thread.sleep(SETTLE_MS)
                return true
            }
            if (promptBridge.getPendingPrompt() != null) {
                Thread.sleep(SETTLE_MS)
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                log.warn("GameBridge: timed out waiting for priority ({}ms)", timeoutMs)
                return false
            }

            // Wait for signal from either bridge (or timeout)
            prioritySignal.awaitSignal(remaining)
        }
    }

    /** Submit keep decision for seat. */
    fun submitKeep(seatId: Int) {
        log.info("GameBridge: seat {} keeps hand", seatId)
        if (seatId == 1) seat1MulliganBridge.submitKeep()
    }

    /**
     * Submit mulligan decision for seat.
     * Blocks until engine re-deals and reaches mulligan again.
     *
     * London mulligan: after mull, the engine draws 7 then calls
     * [tuckCardsViaMulligan] which blocks on [MulliganPhase.WaitingTuck].
     * We auto-tuck first N cards (same as forge-web) to unblock the engine,
     * then wait for the next [MulliganPhase.WaitingKeep].
     */
    fun submitMull(seatId: Int) {
        log.info("GameBridge: seat {} mulligans", seatId)
        if (seatId == 1) {
            // Capture current prompt sequence BEFORE submitting —
            // avoids race where we see the stale WaitingKeep from the current round.
            val seqBefore = seat1MulliganBridge.promptSequence
            seat1MulliganBridge.submitMull()
            // London: engine draws 7 then calls tuckCardsViaMulligan() → WaitingTuck.
            // Wait for a NEW prompt (higher sequence) that's either WaitingTuck or WaitingKeep.
            val deadline = System.currentTimeMillis() + MULLIGAN_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val phase = seat1MulliganBridge.pendingPhase
                val seqNow = seat1MulliganBridge.promptSequence
                if (seqNow > seqBefore && phase != null) {
                    when (phase) {
                        MulliganPhase.WaitingKeep -> {
                            log.info("GameBridge: engine re-dealt hand after mulligan (no tuck)")
                            return
                        }
                        MulliganPhase.WaitingTuck -> {
                            val n = seat1MulliganBridge.pendingCardsToTuck
                            val hand = getHandCards(1)
                            log.info("GameBridge: auto-tucking {} cards (London mulligan)", n)
                            seat1MulliganBridge.submitTuck(hand.take(n))
                            // After tuck, engine continues → next WaitingKeep
                            awaitMulliganReady()
                            log.info("GameBridge: engine re-dealt hand after mulligan+tuck")
                            return
                        }
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            log.warn("GameBridge: timed out waiting for engine after mull+tuck")
        }
    }

    /**
     * Block until the engine reaches the tuck phase after a kept mulligan.
     * The engine calls [MulliganBridge.awaitTuckDecision] on the game thread,
     * setting pendingPhase to [MulliganPhase.WaitingTuck].
     */
    fun awaitTuckReady() {
        val deadline = System.currentTimeMillis() + MULLIGAN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (seat1MulliganBridge.pendingPhase == MulliganPhase.WaitingTuck) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach tuck phase")
    }

    /** How many cards the player must put on bottom (London mulligan). */
    fun getTuckCount(): Int = seat1MulliganBridge.pendingCardsToTuck

    /** Get the current hand as Card objects for a seat. */
    fun getHandCards(seatId: Int): List<forge.game.card.Card> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.toList()
    }

    /** Submit tuck decision — cards to put on bottom of library. */
    fun submitTuck(seatId: Int, cards: List<forge.game.card.Card>) {
        log.info("GameBridge: seat {} tucking {} cards", seatId, cards.size)
        if (seatId == 1) seat1MulliganBridge.submitTuck(cards)
    }

    /** True when this bridge is running a puzzle game. */
    val isPuzzle: Boolean
        get() = game?.rules?.gameType == GameType.Puzzle

    /**
     * Initialize card DB, create a puzzle game, apply puzzle state, finalize,
     * start the game loop from current state (no mulligan), and wait for priority.
     *
     * @param puzzle the parsed [Puzzle] object to apply
     */
    fun startPuzzle(puzzle: Puzzle) {
        log.info("GameBridge: starting puzzle mode")
        GameBootstrap.initializeCardDatabase()

        val g = GameBootstrap.createPuzzleGame()
        game = g

        // Apply puzzle state via reflection (applyGameOnThread is protected).
        // Install temp WebPlayerControllers with autoKeep + zero-timeout bridges
        // to handle any SBAs/triggers during setup (forge-web pattern).
        applyPuzzleSafely(puzzle, g)

        // Finalize: set age=Play, position at MAIN1 turn 1
        GameBootstrap.finalizeForPuzzle(g)
        log.info("GameBridge: puzzle applied, game at {} turn {}", g.phaseHandler.phase, g.phaseHandler.turn)

        // Register all puzzle cards in CardRepository and InstanceIdRegistry.
        // Puzzle.applyGameOnThread creates cards via Card.fromPaperCard — they
        // need synthetic grpIds and instanceId mappings for proto output.
        registerPuzzleCards(g)

        // Wire WebPlayerController for seat 1 (human) — same as constructed
        // but no mulligan bridge needed (autoKeep=true, unused).
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        phaseStopProfile = PhaseStopProfile.createDefaults(human.id, aiPlayer.id)
        val controller = WebPlayerController(
            game = g,
            player = human,
            lobbyPlayer = human.lobbyPlayer,
            bridge = this.promptBridge,
            actionBridge = actionBridge,
            mulliganBridge = seat1MulliganBridge,
            phaseStopProfile = phaseStopProfile,
        )
        humanController = controller
        human.addController(Long.MAX_VALUE - 1, human, controller, false)

        // Start game loop from current state (skip Match.startGame/mulligan)
        val loop = GameLoopController(
            g,
            actionBridges = listOf(actionBridge),
            promptBridges = listOf(promptBridge),
            mulliganBridges = listOf(seat1MulliganBridge),
        )
        loopController = loop
        loop.startFromCurrentState()
        loop.awaitStarted()

        // Register event collector and playback (same as constructed)
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)

        val pb = GamePlayback(this, "forge-match-1", 1, messageCounter, matchConfig.aiDelayMultiplier)
        playback = pb
        g.subscribeToEvents(pb)

        log.info("GameBridge: puzzle loop started, waiting for priority")
        awaitPriority()
        log.info("GameBridge: puzzle engine reached priority, ready")
    }

    /**
     * Tear down the current game and start a new puzzle in-place.
     * Clears all bridge state (instanceIds, limbo, zones, snapshots, annotations)
     * so the new puzzle gets a clean slate. The client receives a Full GSM after.
     */
    fun resetForPuzzle(puzzle: Puzzle) {
        log.info("GameBridge: resetting for new puzzle")
        shutdown()

        // Clear all mapping/tracking state from the previous game
        ids.resetAll()
        limbo.clear()
        diff.resetAll()
        activePersistentAnnotations.clear()
        pendingPersistentDeletions.clear()
        nextAnnotationId = INITIAL_ANNOTATION_ID
        nextPersistentAnnotationId = INITIAL_PERSISTENT_ANNOTATION_ID

        // Swap to InMemoryCardRepository for puzzle (puzzle cards use synthetic grpIds
        // that don't exist in ExposedCardRepository / client SQLite)
        val memRepo = InMemoryCardRepository()
        cards = memRepo
        cardProto = CardProtoBuilder(memRepo)

        startPuzzle(puzzle)
        log.info("GameBridge: puzzle hot-swap complete")
    }

    fun shutdown() {
        log.info("GameBridge: shutting down")
        loopController?.shutdown()
        loopController = null
        playback = null
        eventCollector = null
        game = null
    }

    // --- Puzzle internals ---

    /**
     * Apply puzzle state to the game via reflection.
     * Installs temp [WebPlayerController]s with autoKeep/zero-timeout during
     * application to handle any SBAs or triggers that fire during setup.
     */
    private fun applyPuzzleSafely(puzzle: Puzzle, game: Game) {
        val method = puzzle.javaClass.superclass.getDeclaredMethod("applyGameOnThread", Game::class.java)
        method.isAccessible = true
        runWithTempControllers(game.players.filter { it.controller is PlayerControllerHuman }) {
            try {
                method.invoke(puzzle, game)
            } catch (e: InvocationTargetException) {
                throw RuntimeException("Puzzle application failed", e.targetException)
            }
        }
    }

    /**
     * Recursively install temp [WebPlayerController]s with zero-timeout bridges
     * on each human-controlled player during [block]. Removed automatically after.
     */
    private fun runWithTempControllers(players: List<Player>, block: () -> Unit) {
        val player = players.firstOrNull() ?: run {
            block()
            return
        }
        val tempPrompt = InteractivePromptBridge(timeoutMs = 0)
        val tempAction = GameActionBridge(timeoutMs = 0)
        val tempMulligan = MulliganBridge(autoKeep = true, timeoutMs = 0)
        val tempController = WebPlayerController(
            game = player.game,
            player = player,
            lobbyPlayer = player.lobbyPlayer,
            bridge = tempPrompt,
            actionBridge = tempAction,
            mulliganBridge = tempMulligan,
        )
        player.runWithController(
            { runWithTempControllers(players.drop(1), block) },
            tempController,
        )
    }

    /**
     * After puzzle application: iterate all cards in all zones, register them
     * in [CardRepository] (via PuzzleCardRegistrar) and [InstanceIdRegistry].
     *
     * Uses `ensureCardRegisteredByName` because puzzle-applied cards may have
     * null `rules` — the by-name path creates a fresh temp Card from the paper DB
     * where rules are guaranteed loaded.
     */
    private fun registerPuzzleCards(game: Game) {
        val repo = cards as? InMemoryCardRepository ?: run {
            log.warn("GameBridge: puzzle card registration requires InMemoryCardRepository")
            return
        }
        val registrar = PuzzleCardRegistrar(repo)
        val allZones = listOf(
            ZoneType.Hand,
            ZoneType.Battlefield,
            ZoneType.Library,
            ZoneType.Graveyard,
            ZoneType.Exile,
            ZoneType.Command,
        )
        var registered = 0
        for (player in game.players) {
            for (zone in allZones) {
                for (card in player.getZone(zone).cards) {
                    registrar.ensureCardRegisteredByName(card.name)
                    ids.getOrAlloc(card.id)
                    registered++
                }
            }
        }
        log.info("GameBridge: registered {} puzzle cards in CardRepository + InstanceIdRegistry", registered)
    }

    // --- Internal ---

    /**
     * Poll until seat 1's mulligan bridge is in "waiting_keep" state,
     * meaning the engine has dealt the hand and is blocking.
     */
    private fun awaitMulliganReady() {
        val deadline = System.currentTimeMillis() + MULLIGAN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (seat1MulliganBridge.pendingPhase == MulliganPhase.WaitingKeep) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach mulligan")
    }
}
