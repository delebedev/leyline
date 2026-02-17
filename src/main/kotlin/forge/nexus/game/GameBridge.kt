package forge.nexus.game

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.util.MyRandom
import forge.web.game.DeckLoader
import forge.web.game.GameActionBridge
import forge.web.game.GameBootstrap
import forge.web.game.GameLoopController
import forge.web.game.InteractivePromptBridge
import forge.web.game.MulliganBridge
import forge.web.game.MulliganPhase
import forge.web.game.WebPlayerController
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import java.util.Random

/**
 * Bridges MTGA's Arena protocol to a real Forge [forge.game.Game] engine.
 *
 * Creates a constructed game (human seat 1 + AI seat 2), starts the game loop,
 * and blocks until the engine reaches mulligan. The Arena handler reads hands
 * and submits keep/mull decisions through this bridge.
 *
 * Internally composed of focused components:
 * - [InstanceIdRegistry] — Forge cardId ↔ Arena instanceId bimap
 * - [LimboTracker] — retired instanceId history
 * - [DiffSnapshotter] — zone tracking + state snapshots for diff computation
 *
 * Threading: [start] blocks the caller (~2-3s first call for card DB, <100ms after).
 * The engine thread blocks at mulligan via [forge.web.game.MulliganBridge].
 */
class GameBridge {
    private val log = LoggerFactory.getLogger(GameBridge::class.java)

    private var game: Game? = null
    private var loopController: GameLoopController? = null

    /** seat 1 = human (autoKeep=false). AI uses its default controller. */
    private val seat1MulliganBridge = MulliganBridge(autoKeep = false, timeoutMs = 120_000)

    /** Action bridge for seat 1 — blocks engine at priority stops. */
    val actionBridge = GameActionBridge(timeoutMs = 120_000)

    /** Prompt bridge for seat 1 — blocks engine on targeting/choice prompts. */
    val promptBridge = InteractivePromptBridge(timeoutMs = 120_000)

    /** AI action playback — captures per-action state diffs via EventBus. Null before start(). */
    var playback: NexusGamePlayback? = null
        private set

    /** Event collector — captures Forge engine events for annotation building. Null before start(). */
    var eventCollector: GameEventCollector? = null
        private set

    // --- Composed components ---

    /** Card ID mapping (Forge cardId ↔ Arena instanceId). */
    val ids = InstanceIdRegistry()

    /** Retired instanceId history (Limbo zone). */
    val limbo = LimboTracker()

    /** Zone tracking + state snapshots for diff computation. */
    val diff = DiffSnapshotter(ids)

    /** Monotonic annotation ID counter. Real server starts around 49; we start at 50. */
    private var nextAnnotationId = 50

    /** Allocate the next sequential annotation ID. */
    fun nextAnnotationId(): Int = nextAnnotationId++

    /** Monotonic persistent annotation ID counter. Real server uses a separate sequence. */
    private var nextPersistentAnnotationId = 1

    /** Allocate the next sequential persistent annotation ID. */
    fun nextPersistentAnnotationId(): Int = nextPersistentAnnotationId++

    // --- Delegate methods (keep public API stable for existing call sites) ---

    /** Allocate or return existing Arena instanceId for a Forge card ID. */
    fun getOrAllocInstanceId(forgeCardId: Int): Int = ids.getOrAlloc(forgeCardId)

    /** Allocate a fresh instanceId for a Forge card that changed zones. */
    fun reallocInstanceId(forgeCardId: Int): InstanceIdRegistry.IdReallocation = ids.realloc(forgeCardId)

    /** Reverse lookup: Arena instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: Int): Int? = ids.getForgeCardId(instanceId)

    /** Read-only snapshot of instanceId → forgeCardId (for debug panel). */
    fun getInstanceIdMap(): Map<Int, Int> = ids.snapshot()

    /** Add an instanceId to the persistent Limbo set. */
    fun retireToLimbo(instanceId: Int) = limbo.retire(instanceId)

    /** Current ordered list of all retired instanceIds. */
    fun getLimboInstanceIds(): List<Int> = limbo.all()

    /** Record current zone for an instance. Returns previous zone or null if new. */
    fun recordZone(instanceId: Int, zoneId: Int): Int? = diff.recordZone(instanceId, zoneId)

    /** Snapshot current zones for annotation building. */
    fun getPreviousZone(instanceId: Int): Int? = diff.getPreviousZone(instanceId)

    /** Previous full GameStateMessage — used to compute diffs. Null before first state. */
    fun snapshotState(game: Game, gameStateId: Int = 0) {
        diff.snapshotState(StateMapper.buildFromGame(game, gameStateId, "", this))
    }

    fun getPreviousState(): GameStateMessage? = diff.getPreviousState()

    fun clearPreviousState() = diff.clear()

    companion object {
        /** Fallback grpId for cards not in Arena DB (renders face-down). */
        const val FALLBACK_GRPID = 0

        /** Deck shared by both seats (mono-green stompy for testing). */
        private const val DEFAULT_DECK = """
20 Llanowar Elves
4 Elvish Mystic
4 Giant Growth
32 Forest
"""

        /** Max time to wait for engine to reach mulligan after start/mull. */
        private const val MULLIGAN_WAIT_MS = 10_000L
        private const val PRIORITY_WAIT_MS = 15_000L

        /** Longer timeout for AI turns (AI plays full turn: lands, spells, combat). */
        const val AI_TURN_WAIT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 50L
    }

    /**
     * Initialize card DB, create game, start engine loop, wait for mulligan.
     * Blocks caller until engine has dealt hands and is waiting for keep/mull.
     *
     * @param seed if non-null, seeds the RNG for deterministic shuffles (tests/replays)
     */
    fun start(seed: Long? = null) {
        log.info("ArenaGameBridge: initializing card database")
        GameBootstrap.initializeCardDatabase()

        if (seed != null) {
            log.info("ArenaGameBridge: using deterministic seed={}", seed)
            MyRandom.setRandom(Random(seed))
        }

        val deck1 = DeckLoader.parseDeckList(DEFAULT_DECK.trimIndent())
        val deck2 = DeckLoader.parseDeckList(DEFAULT_DECK.trimIndent())
        log.info(
            "ArenaGameBridge: parsed decks (seat1={} cards, seat2={} cards)",
            deck1.main.countAll(),
            deck2.main.countAll(),
        )

        val g = GameBootstrap.createConstructedGame(deck1, deck2)
        game = g

        // Wire WebPlayerController for seat 1 (human) with mulligan bridge
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val controller = WebPlayerController(
            game = g,
            player = human,
            lobbyPlayer = human.lobbyPlayer,
            bridge = this.promptBridge,
            actionBridge = actionBridge,
            mulliganBridge = seat1MulliganBridge,
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

        // Register AI action playback subscriber
        val pb = NexusGamePlayback(this, "forge-match-1", 1)
        playback = pb
        g.subscribeToEvents(pb)
        log.info("ArenaGameBridge: registered NexusGamePlayback for AI action streaming")

        // Register event collector for annotation building
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
        log.info("ArenaGameBridge: registered GameEventCollector for event-driven annotations")

        log.info("ArenaGameBridge: game loop started, waiting for mulligan")
        awaitMulliganReady()
        log.info("ArenaGameBridge: engine reached mulligan, hand ready")
    }

    /** Get the current hand for a seat as Arena grpIds. */
    fun getHandGrpIds(seatId: Int): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.map { card ->
            CardDb.lookupByName(card.name) ?: FALLBACK_GRPID
        }
    }

    /** Full deck as Arena grpIds (for initial bundle deck message). */
    fun getDeckGrpIds(seatId: Int): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        // Combine library + hand + any other zones to reconstruct full deck
        val allCards = mutableListOf<String>()
        for (zone in listOf(ZoneType.Library, ZoneType.Hand)) {
            player.getZone(zone).cards.forEach { allCards.add(it.name) }
        }
        return allCards.map { CardDb.lookupByName(it) ?: FALLBACK_GRPID }
    }

    /** Get the underlying Forge game (null before [start]). */
    fun getGame(): Game? = game

    /** Map seat ID to Forge player. */
    fun getPlayer(seatId: Int): Player? {
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
    fun awaitPriority() = awaitPriorityWithTimeout(PRIORITY_WAIT_MS)

    /**
     * Block until the engine reaches a priority stop or game ends.
     * @param timeoutMs max wait time (use longer values for AI turns)
     * @return true if priority was reached, false if timed out or game over
     */
    fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val g = game
            if (g != null && g.isGameOver) {
                log.info("ArenaGameBridge: game over detected while waiting for priority")
                return false
            }
            if (actionBridge.getPending() != null) {
                // Let engine thread finish in-flight zone moves before we snapshot state
                Thread.sleep(10)
                return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("ArenaGameBridge: timed out waiting for priority ({}ms)", timeoutMs)
        return false
    }

    /** Submit keep decision for seat. */
    fun submitKeep(seatId: Int) {
        log.info("ArenaGameBridge: seat {} keeps hand", seatId)
        if (seatId == 1) seat1MulliganBridge.submitKeep()
    }

    /**
     * Submit mulligan decision for seat.
     * Blocks until engine re-deals and reaches mulligan again.
     */
    fun submitMull(seatId: Int) {
        log.info("ArenaGameBridge: seat {} mulligans", seatId)
        if (seatId == 1) {
            seat1MulliganBridge.submitMull()
            awaitMulliganReady()
            log.info("ArenaGameBridge: engine re-dealt hand after mulligan")
        }
    }

    fun shutdown() {
        log.info("ArenaGameBridge: shutting down")
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
        log.warn("ArenaGameBridge: timed out waiting for engine to reach mulligan")
    }
}
