package forge.nexus.conformance

import forge.game.Game
import forge.game.card.Card
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.MessageCounter
import forge.nexus.game.PuzzleSource
import forge.nexus.game.StateMapper
import forge.nexus.game.advanceToMain1
import forge.nexus.game.awaitFreshPending
import forge.nexus.game.snapshotFromGame
import forge.web.game.GameBootstrap
import forge.web.game.PlayerAction
import org.testng.Assert
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Base class for wire conformance tests.
 *
 * Provides helpers to start deterministic games, play actions,
 * and capture outbound GRE messages via BundleBuilder.
 *
 * Each test gets a fresh [MessageCounter] to track protocol sequencing.
 */
abstract class ConformanceTestBase {

    protected var bridge: GameBridge? = null

    /** Shared counter for the current test. Reset per test via [startGameAtMain1]. */
    protected var testCounter: MessageCounter = MessageCounter()

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()
    }

    @AfterMethod
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
     * @return (bridge, game, counter)
     */
    protected fun startGameAtMain1(
        seed: Long = 42L,
        deckList: String? = null,
    ): Triple<GameBridge, Game, MessageCounter> {
        // Auto-register CardData for all cards in the deck list
        if (deckList != null) {
            TestCardRegistry.ensureDeckRegistered(deckList)
        }
        val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
        testCounter = counter
        val b = GameBridge(messageCounter = counter)
        bridge = b
        b.start(seed = seed, deckList = deckList)
        b.submitKeep(1)
        advanceToMain1(b)
        val game = b.getGame()!!
        Assert.assertEquals(
            game.phaseHandler.phase,
            PhaseType.MAIN1,
            "Game should be at Main1 after advanceToMain1 (actual: ${game.phaseHandler.phase})",
        )
        b.snapshotFromGame(game, counter.currentGsId())
        // Seed lastSentTurnInfo so postAction() doesn't inject a spurious
        // PhaseOrStepModified on the first call. ConformanceTestBase bypasses
        // MatchSession (which normally updates this via sendBundledGRE).
        b.updateLastSentTurnInfo(StateMapper.buildFromGame(game, counter.currentGsId(), TEST_MATCH_ID, b))
        return Triple(b, game, counter)
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
     * @return (bridge, game, counter)
     */
    protected fun startPuzzleAtMain1(
        puzzleText: String,
    ): Triple<GameBridge, Game, MessageCounter> {
        val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
        testCounter = counter
        val b = GameBridge(messageCounter = counter)
        bridge = b

        val puzzle = PuzzleSource.loadFromText(puzzleText)
        b.startPuzzle(puzzle)

        val game = b.getGame()!!
        Assert.assertEquals(
            game.phaseHandler.phase,
            PhaseType.MAIN1,
            "Puzzle game should be at Main1 (actual: ${game.phaseHandler.phase})",
        )
        b.snapshotFromGame(game, counter.currentGsId())
        b.updateLastSentTurnInfo(StateMapper.buildFromGame(game, counter.currentGsId(), TEST_MATCH_ID, b))
        return Triple(b, game, counter)
    }

    /**
     * Start a game with cards placed directly into zones — no threads, no loop.
     *
     * Uses the upstream `AITest` pattern: empty-deck game, `devModeSet(MAIN1)`,
     * cards added via `Zone.add()`. Fully synchronous — Forge events fire inline
     * when you call `game.action.*`. ~0.01s per test (vs 0.5s for startGameAtMain1).
     *
     * @param board lambda that receives (game, human, ai) to set up zones
     * @return (bridge, game, counter)
     */
    protected fun startWithBoard(
        board: (game: Game, human: Player, ai: Player) -> Unit,
    ): Triple<GameBridge, Game, MessageCounter> {
        val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
        testCounter = counter
        val b = GameBridge(messageCounter = counter)
        bridge = b

        val game = GameBootstrap.createGame()
        b.wrapGame(game)

        val human = game.players.first { it.lobbyPlayer !is forge.ai.LobbyPlayerAi }
        val ai = game.players.first { it.lobbyPlayer is forge.ai.LobbyPlayerAi }

        board(game, human, ai)

        // Register all cards on the board in CardDb + InstanceIdRegistry
        for (player in game.players) {
            for (zone in listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library)) {
                for (card in player.getZone(zone).cards) {
                    TestCardRegistry.ensureCardRegistered(card.name)
                    b.getOrAllocInstanceId(card.id)
                }
            }
        }

        b.snapshotFromGame(game, counter.currentGsId())
        b.updateLastSentTurnInfo(StateMapper.buildFromGame(game, counter.currentGsId(), TEST_MATCH_ID, b))
        return Triple(b, game, counter)
    }

    /**
     * Add a card to a player's zone. Convenience for [startWithBoard] lambdas.
     * Mirrors upstream `AITest.addCardToZone()`.
     */
    protected fun addCard(name: String, player: Player, zone: ZoneType = ZoneType.Battlefield): Card {
        val paperCard = forge.model.FModel.getMagicDb().commonCards.getCard(name)
            ?: run {
                forge.StaticData.instance().attemptToLoadCard(name)
                forge.model.FModel.getMagicDb().commonCards.getCard(name)
            }
            ?: error("Card not found: $name")
        val card = Card.fromPaperCard(paperCard, player)
        card.setGameTimestamp(player.game.nextTimestamp)
        player.getZone(zone).add(card)
        if (zone == ZoneType.Battlefield) {
            card.setSickness(false)
        }
        return card
    }

    protected fun fingerprint(messages: List<GREToClientMessage>): List<StructuralFingerprint> =
        messages.map { StructuralFingerprint.fromGRE(it) }

    protected fun playLand(b: GameBridge): PlayerAction? {
        val player = b.getPlayer(1) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.PlayLand(land.id)
        b.actionBridge.submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    protected fun castCreature(b: GameBridge): PlayerAction? {
        val player = b.getPlayer(1) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(b, null) ?: return null
        val action = PlayerAction.CastSpell(creature.id)
        b.actionBridge.submitAction(pending.actionId, action)
        awaitFreshPending(b, pending.actionId)
        return action
    }

    protected fun passPriority(b: GameBridge) {
        val pending = awaitFreshPending(b, null) ?: return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        awaitFreshPending(b, pending.actionId)
    }

    // ----- Shared capture helpers -----

    /**
     * Create a [ValidatingMessageSink] seeded with the handshake Full GSM.
     * Use in conformance tests to get automatic invariant coverage on every message.
     */
    protected fun validatingSink(
        game: forge.game.Game,
        b: GameBridge,
        gsId: Int,
        strict: Boolean = true,
    ): ValidatingMessageSink {
        val sink = ValidatingMessageSink(strict = strict)
        sink.seedFull(handshakeFull(game, b, gsId))
        return sink
    }

    companion object {
        const val TEST_MATCH_ID = "test-match"
        const val SEAT_ID = 1
    }

    /** Build a postAction bundle with standard test constants. */
    protected fun postAction(
        game: Game,
        b: GameBridge,
        counter: MessageCounter,
    ): BundleBuilder.BundleResult =
        BundleBuilder.postAction(game, b, TEST_MATCH_ID, SEAT_ID, counter)

    /** Build a gameStart bundle (phaseTransitionDiff) with standard test constants. */
    protected fun gameStart(
        game: Game,
        b: GameBridge,
        counter: MessageCounter,
    ): BundleBuilder.BundleResult =
        BundleBuilder.phaseTransitionDiff(game, b, TEST_MATCH_ID, SEAT_ID, counter)

    /**
     * Build a Full state GSM simulating the handshake baseline.
     * Accumulator-based tests need this before processing thin Diffs from gameStart.
     */
    protected fun handshakeFull(
        game: Game,
        b: GameBridge,
        gsId: Int,
    ): GameStateMessage =
        StateMapper.buildFromGame(game, gsId, TEST_MATCH_ID, b, viewingSeatId = SEAT_ID)

    /** Play a land and capture the resulting GSM. */
    protected fun playLandAndCapture(): GameStateMessage? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        return postAction(game, b, counter).gsmOrNull
    }

    /**
     * Play a land and capture GSM + pre/post instanceIds.
     * Returns (gsm, origInstanceId, newInstanceId).
     */
    protected fun playLandAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val (b, game, counter) = startGameAtMain1()

        val player = b.getPlayer(1) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: return null
        val gsm = postAction(game, b, counter).gsmOrNull ?: return null
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        return Triple(gsm, origInstanceId, newInstanceId)
    }

    /**
     * Cast a creature spell and capture the on-stack GSM.
     * Plays a land first for mana.
     */
    protected fun castSpellAndCapture(): GameStateMessage? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.snapshotFromGame(game)
        castCreature(b) ?: return null
        return postAction(game, b, counter).gsmOrNull
    }

    /**
     * Cast a creature and capture GSM + pre/post instanceIds.
     * Returns (gsm, origInstanceId, newInstanceId).
     */
    protected fun castSpellAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.snapshotFromGame(game)

        val player = b.getPlayer(1) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val origInstanceId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b) ?: return null
        val gsm = postAction(game, b, counter).gsmOrNull ?: return null
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        return Triple(gsm, origInstanceId, newInstanceId)
    }

    /**
     * Full cast+resolve cycle: play land -> cast creature -> pass priority.
     * Returns the GSM from the resolution step.
     */
    protected fun resolveAndCapture(): GameStateMessage? {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: return null
        b.snapshotFromGame(game)

        castCreature(b) ?: return null
        postAction(game, b, counter) // capture cast result (advances counter)
        b.snapshotFromGame(game)

        passPriority(b)
        return postAction(game, b, counter).gsmOrNull
    }
}
