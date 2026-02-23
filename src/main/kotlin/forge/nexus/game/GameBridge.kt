package forge.nexus.game

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.nexus.config.PlaytestConfig
import forge.util.MyRandom
import forge.web.game.DeckLoader
import forge.web.game.GameActionBridge
import forge.web.game.GameBootstrap
import forge.web.game.GameLoopController
import forge.web.game.InteractivePromptBridge
import forge.web.game.MulliganBridge
import forge.web.game.MulliganPhase
import forge.web.game.PhaseStopProfile
import forge.web.game.WebPlayerController
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
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
 * The engine thread blocks at mulligan via [forge.web.game.MulliganBridge].
 */
class GameBridge(
    /** Timeout for action bridge / prompt bridge / mulligan bridge.
     *  Production: 120s. Tests: ~2-5s (engine responds in <100ms). */
    private val bridgeTimeoutMs: Long = 120_000L,
    /** Playtest config — controls AI speed, die roll, etc. */
    val playtestConfig: PlaytestConfig = PlaytestConfig(),
) : IdMapping,
    PlayerLookup,
    ZoneTracking,
    StateSnapshot,
    AnnotationIds,
    EventDrain {
    private val log = LoggerFactory.getLogger(GameBridge::class.java)

    private var game: Game? = null
    private var loopController: GameLoopController? = null

    /** seat 1 = human (autoKeep=false). AI uses its default controller. */
    private val seat1MulliganBridge = MulliganBridge(autoKeep = false, timeoutMs = bridgeTimeoutMs)

    /** Action bridge for seat 1 — blocks engine at priority stops. */
    val actionBridge = GameActionBridge(timeoutMs = bridgeTimeoutMs)

    /** Prompt bridge for seat 1 — blocks engine on targeting/choice prompts. */
    val promptBridge = InteractivePromptBridge(timeoutMs = bridgeTimeoutMs)

    /** AI action playback — captures per-action state diffs via EventBus. Null before start(). */
    var playback: NexusGamePlayback? = null
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

    // --- Interface implementations (IdMapping, PlayerLookup, ZoneTracking, etc.) ---

    override fun getOrAllocInstanceId(forgeCardId: Int): Int = ids.getOrAlloc(forgeCardId)

    override fun reallocInstanceId(forgeCardId: Int): InstanceIdRegistry.IdReallocation = ids.realloc(forgeCardId)

    override fun getForgeCardId(instanceId: Int): Int? = ids.getForgeCardId(instanceId)

    /** Read-only snapshot of instanceId → forgeCardId (for debug panel). */
    fun getInstanceIdMap(): Map<Int, Int> = ids.snapshot()

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

    override fun drainEvents(): List<NexusGameEvent> = eventCollector?.drainEvents() ?: emptyList()

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
        private const val POLL_INTERVAL_MS = 50L
    }

    /**
     * How long [awaitPriority] waits for the engine to reach a priority stop.
     * Production default: 15s. Tests should use ~2s since the engine responds
     * in <100ms and the extra headroom only delays timeout-based test failures.
     */
    var priorityWaitMs: Long = DEFAULT_PRIORITY_WAIT_MS

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
        human.addController(Long.MAX_VALUE - 1, human, controller, false)

        // AI keeps its default controller — handles priority, combat, etc. natively

        val loop = GameLoopController(
            g,
            actionBridges = listOf(actionBridge),
            mulliganBridges = listOf(seat1MulliganBridge),
        )
        loopController = loop
        loop.start()

        // Register event collector FIRST — must fire before playback so drainEvents()
        // includes the current event when playback's captureAndPause runs.
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
        log.info("GameBridge: registered GameEventCollector for event-driven annotations")

        // Register AI action playback subscriber (after collector)
        val pb = NexusGamePlayback(this, "forge-match-1", 1, playtestConfig.aiDelayMultiplier)
        playback = pb
        g.subscribeToEvents(pb)
        log.info("GameBridge: registered NexusGamePlayback for AI action streaming")

        log.info("GameBridge: game loop started, waiting for mulligan")
        awaitMulliganReady()
        log.info("GameBridge: engine reached mulligan, hand ready")
    }

    /** Get the current hand for a seat as client grpIds. */
    fun getHandGrpIds(seatId: Int): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.map { card ->
            CardDb.lookupByName(card.name) ?: FALLBACK_GRPID
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
        return allCards.map { CardDb.lookupByName(it) ?: FALLBACK_GRPID }
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
        while (System.currentTimeMillis() < deadline) {
            val g = game
            if (g != null && g.isGameOver) {
                log.info("GameBridge: game over detected while waiting for priority")
                return false
            }
            if (actionBridge.getPending() != null) {
                // Let engine thread finish in-flight zone moves before we snapshot state
                Thread.sleep(10)
                return true
            }
            // A pending prompt (targeting, sacrifice, etc.) also counts as
            // "engine waiting for input" — treat it like reaching priority.
            if (promptBridge.getPendingPrompt() != null) {
                Thread.sleep(10)
                return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for priority ({}ms)", timeoutMs)
        return false
    }

    /** Submit keep decision for seat. */
    fun submitKeep(seatId: Int) {
        log.info("GameBridge: seat {} keeps hand", seatId)
        if (seatId == 1) seat1MulliganBridge.submitKeep()
    }

    /**
     * Submit mulligan decision for seat.
     * Blocks until engine re-deals and reaches mulligan again.
     */
    fun submitMull(seatId: Int) {
        log.info("GameBridge: seat {} mulligans", seatId)
        if (seatId == 1) {
            seat1MulliganBridge.submitMull()
            awaitMulliganReady()
            log.info("GameBridge: engine re-dealt hand after mulligan")
        }
    }

    fun shutdown() {
        log.info("GameBridge: shutting down")
        loopController?.shutdown()
        loopController = null
        playback = null
        eventCollector = null
        game = null
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
