package leyline.testkit

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.mapping.StateMapper
import leyline.game.seedDiffBaseline
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Board-tier engine behind [BoardTest]: starts deterministic games, plays
 * actions, and builds outbound GRE message bundles via [BundleBuilder].
 *
 * [BoardTest] owns one shared instance per spec — extend that for ordinary
 * board-tier tests. Direct construction is reserved for tests that need an
 * isolated instance outside the shared one, e.g. driving two independent
 * bridges (live + replay) within a single test body, each needing its own
 * teardown (see `PureDiffReplayTest`).
 */
open class BoardTestBase {
    var bridge: GameBridge? = null

    /** Shared counter for the current test. Reset per test via [startGameAtMain1]. */
    var testCounter: MessageCounter = MessageCounter()

    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()
    }

    fun tearDown() {
        bridge?.shutdown()
        bridge = null
        testCounter = MessageCounter()
    }

    /**
     * Start a deterministic game, keep hand, advance to Main1.
     *
     * @param seed RNG seed for deterministic shuffles
     * @param deckList custom deck list (e.g. "30 Plains\n30 Forest"); null uses default mono-green
     * @param variant game variant (e.g. "brawl" for commander tax + command zone); null = Constructed
     */
    fun startGameAtMain1(
        seed: Long = 42L,
        deckList: String? = null,
        variant: String? = null,
    ): Board {
        val result = Board.startGameAtMain1(seed, deckList, variant)
        bridge = result.bridge
        testCounter = result.counter
        return result
    }

    /**
     * Start a game from an inline puzzle definition — no mulligan, no turn advancement.
     *
     * Much faster than [startGameAtMain1] (~0.3s vs ~1.5s) because it skips:
     * deck shuffle, mulligan keep, and priority-passing through upkeep/draw.
     *
     * Use when the test needs a specific board state (creatures on BF, cards in hand, etc.)
     * rather than the default mono-green deck.
     *
     * @param puzzleText inline `.pzl` content (see `src/test/resources/puzzles/` for format)
     */
    fun startPuzzleAtMain1(puzzleText: String): Board {
        val result = Board.startPuzzleAtMain1(puzzleText)
        bridge = result.bridge
        testCounter = result.counter
        return result
    }

    /** Convenience: load a puzzle from a test resource path (e.g. "puzzles/foo.pzl"). */
    fun startPuzzleAtMain1FromResource(resourcePath: String): Board {
        val result = Board.startPuzzleAtMain1FromResource(resourcePath)
        bridge = result.bridge
        testCounter = result.counter
        return result
    }

    /**
     * Start a game with cards placed directly into zones — no threads, no loop.
     *
     * Uses the upstream `AITest` pattern: empty-deck game, `devModeSet(MAIN1)`,
     * cards added via `Zone.add()`. Fully synchronous — Forge events fire inline
     * when you call `game.action.*`. ~0.01s per test (vs 0.5s for startGameAtMain1).
     *
     * @param board lambda that receives (game, human, ai) to set up zones
     */
    fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit): Board {
        val result = Board.startWithBoard(board)
        bridge = result.bridge
        testCounter = result.counter
        return result
    }

    /**
     * Add a card to a player's zone. Convenience for [startWithBoard] lambdas.
     * Mirrors upstream `AITest.addCardToZone()`.
     *
     * No bridge registration — used before a [Board] exists yet (during
     * [startWithBoard]'s own setup lambda). [Board.addCard] is the
     * bridge-registering counterpart for use after setup.
     */
    fun addCard(
        name: String,
        player: Player,
        zone: ZoneType = ZoneType.Battlefield,
    ): Card = Board.createCard(name, player, zone)

    // ----- Capture helpers -----

    /** Create a [BundleBuilder] with standard test constants. */
    fun bundleBuilder(b: GameBridge): BundleBuilder = BundleBuilder(b, TEST_MATCH_ID, SEAT_ID)

    /** Build a stateOnlyDiff and return the GSM. Fails if no GSM produced. */
    fun stateOnlyDiff(
        game: Game,
        b: GameBridge,
        counter: MessageCounter,
    ): GameStateMessage =
        bundleBuilder(b)
            .stateOnlyDiff(game, counter)
            .gsmOrNull ?: error("stateOnlyDiff returned no GSM")

    /**
     * Snapshot, run [action], build stateOnlyDiff, return GSM.
     * If [checkSba] is true, triggers state-based actions after the action.
     */
    fun captureAfterAction(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
        checkSba: Boolean = false,
        action: () -> Unit,
    ): GameStateMessage {
        b.seedDiffBaseline(game, counter.currentGsId())
        action()
        if (checkSba) game.action.checkStateEffects(true)
        return stateOnlyDiff(game, b, counter)
    }

    fun playLand(b: GameBridge): PlayerAction.PlayLand? {
        val player = b.getPlayer(SeatId(1)) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.PlayLand(ForgeCardId(land.id))
        b.actionBridge(SeatId(1)).submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    fun castCreature(b: GameBridge): PlayerAction.CastSpell? {
        val player = b.getPlayer(SeatId(1)) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.CastSpell(ForgeCardId(creature.id))
        b.actionBridge(SeatId(1)).submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    fun passPriority(b: GameBridge) {
        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge(SeatId(1)).submitAction(pending.actionId, PlayerAction.PassPriority)
        awaitFreshPending(b, pending.actionId)
    }

    companion object {
        const val TEST_MATCH_ID = "test-match"
        const val SEAT_ID = 1
    }

    /** Build a postAction bundle with standard test constants. */
    fun postAction(
        game: Game,
        b: GameBridge,
        counter: MessageCounter,
    ): BundleBuilder.BundleResult {
        val playbackMessages =
            b.playback
                ?.drainQueue()
                .orEmpty()
                .flatten()
        val postAction = bundleBuilder(b).postAction(game, counter)
        if (playbackMessages.isEmpty()) return postAction
        return BundleBuilder.BundleResult(playbackMessages + postAction.messages)
    }

    /** Build a gameStart bundle (phaseTransitionDiff) with standard test constants. */
    fun gameStart(
        game: Game,
        b: GameBridge,
        counter: MessageCounter,
    ): BundleBuilder.BundleResult = bundleBuilder(b).phaseTransitionDiff(game, counter)

    /**
     * Build a Full state GSM simulating the handshake baseline.
     * Accumulator-based tests need this before processing thin Diffs from gameStart.
     */
    fun handshakeFull(
        game: Game,
        b: GameBridge,
        gsId: Int,
    ): GameStateMessage {
        val snap = GsmSnapshot.capture(game, b, TEST_MATCH_ID, gsId)
        return StateMapper.buildFromSnapshot(snap, gsId, TEST_MATCH_ID, b, viewingSeatId = SEAT_ID).gsm
    }

    /** Play a land and capture the resulting GSM. */
    fun playLandAndCapture(): GameStateMessage? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        return postAction(game, b, counter).gsmOrNull
    }

    /**
     * Cast a creature spell and capture the full bundle.
     * Plays a land first for mana. With CastSpell split, this returns the
     * QueuedGSM triplet + ActionsAvailableReq.
     */
    fun castSpellBundle(): BundleBuilder.BundleResult? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.seedDiffBaseline(game)
        castCreature(b) ?: return null
        return postAction(game, b, counter)
    }

    /**
     * Cast a creature spell and capture the on-stack GSM.
     * Plays a land first for mana. Returns merged GSM with all annotations
     * from the QueuedGSM triplet combined.
     */
    fun castSpellAndCapture(): GameStateMessage? = castSpellBundle()?.mergedGsm

    /**
     * Cast a creature and capture GSM + pre/post instanceIds.
     * Returns (gsm, origInstanceId, newInstanceId).
     */
    fun castSpellAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.seedDiffBaseline(game)

        val action = castCreature(b) ?: return null
        // Use mergedGsm to combine QueuedGSM triplet annotations into one GSM
        val gsm = postAction(game, b, counter).mergedGsm
        val origInstanceId = gsm.annotation(AnnotationType.ObjectIdChanged).detailInt("orig_id")
        val newInstanceId = b.getOrAllocInstanceId(action.cardId).value

        return Triple(gsm, origInstanceId, newInstanceId)
    }

    /**
     * Full cast+resolve cycle: play land -> cast creature -> pass priority.
     * Returns the GSM from the resolution step.
     */
    fun resolveAndCapture(): GameStateMessage? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.seedDiffBaseline(game)

        castCreature(b) ?: return null
        postAction(game, b, counter) // capture cast result (advances counter)
        b.seedDiffBaseline(game)

        passPriority(b)
        return postAction(game, b, counter).gsmOrNull
    }
}

/** The human (non-AI) player. Use after any start* method. */
val Game.humanPlayer: Player
    get() = players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }

/** The AI player. Use after any start* method. */
val Game.aiPlayer: Player
    get() = players.first { it.lobbyPlayer is forge.ai.LobbyPlayerAi }
