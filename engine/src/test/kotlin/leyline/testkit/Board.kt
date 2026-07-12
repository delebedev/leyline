package leyline.testkit

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.advanceToMain1
import leyline.game.awaitFreshPending
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.generator.PuzzleSource
import leyline.game.seedDiffBaseline
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Board-tier test context: the bridge, game, and message counter produced by
 * a `start*` setup call, plus the state-needing helpers that act on them.
 *
 * Destructures as `(bridge, game, counter)` so every existing
 * `val (b, game, counter) = start*` call site keeps compiling unchanged.
 */
class Board(
    val bridge: GameBridge,
    val game: Game,
    val counter: MessageCounter,
) {
    operator fun component1(): GameBridge = bridge

    operator fun component2(): Game = game

    operator fun component3(): MessageCounter = counter

    /** The human (non-AI) player. */
    val human: Player get() = game.humanPlayer

    /** The AI player. */
    val ai: Player get() = game.aiPlayer

    // ----- Snapshot + diff -----

    /** Create a [BundleBuilder] with standard test constants. */
    fun bundleBuilder(): BundleBuilder = BundleBuilder(bridge, BoardTestBase.TEST_MATCH_ID, BoardTestBase.SEAT_ID)

    /** Build a stateOnlyDiff and return the GSM. Fails if no GSM produced. */
    fun stateOnlyDiff(): GameStateMessage = bundleBuilder().stateOnlyDiff(game, counter).gsmOrNull ?: error("stateOnlyDiff returned no GSM")

    /**
     * Seed the diff baseline, run [action], build a stateOnlyDiff, return the GSM.
     * If [checkSba] is true, triggers state-based actions after the action.
     */
    fun snapshotDiff(
        checkSba: Boolean = false,
        action: () -> Unit,
    ): GameStateMessage {
        bridge.seedDiffBaseline(game, counter.currentGsId())
        action()
        if (checkSba) game.action.checkStateEffects(true)
        return stateOnlyDiff()
    }

    /** Build a postAction bundle with standard test constants. */
    fun postAction(): BundleBuilder.BundleResult {
        val playbackMessages =
            bridge.playback
                ?.drainQueue()
                .orEmpty()
                .flatten()
        val result = bundleBuilder().postAction(game, counter)
        if (playbackMessages.isEmpty()) return result
        return BundleBuilder.BundleResult(playbackMessages + result.messages)
    }

    /** Build a gameStart bundle (phaseTransitionDiff) with standard test constants. */
    fun gameStart(): BundleBuilder.BundleResult = bundleBuilder().phaseTransitionDiff(game, counter)

    // ----- Board actions -----

    /** Add a card to a player's zone, registered so the bridge can resolve its instanceId. */
    fun addCard(
        name: String,
        player: Player,
        zone: ZoneType = ZoneType.Battlefield,
    ): Card {
        val card = createCard(name, player, zone)
        TestCardRegistry.ensureCardRegistered(card.name)
        bridge.getOrAllocInstanceId(ForgeCardId(card.id))
        return card
    }

    /** Play first land from hand via Forge's full path. Fires GameEventLandPlayed. */
    fun playLandFromHand(): GameStateMessage {
        val land = human.getZone(ZoneType.Hand).cards.first { it.isLand }
        return snapshotDiff { human.playLand(land, null) }
    }

    /** Move card to battlefield — raw zone move, no events, no triggers. For setup. */
    fun moveToBattlefield(card: Card) {
        game.action.moveToPlay(card, null, AbilityKey.newMap())
    }

    fun destroy(card: Card) {
        game.action.destroy(card, null, false, AbilityKey.newMap())
    }

    fun exile(card: Card) {
        game.action.exile(card, null, AbilityKey.newMap())
    }

    /** Find card by name, perform action, assert realloc + Limbo, return (gsm, newInstanceId). */
    fun transferCard(
        cardName: String,
        checkSba: Boolean = false,
        action: (Card, Game) -> Unit,
    ): Pair<GameStateMessage, Int> {
        val card =
            listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile)
                .firstNotNullOf { zone -> human.getZone(zone).cards.firstOrNull { it.name == cardName } }
        val origId = instanceId(card.id)
        val cardId = card.id

        val gsm = snapshotDiff(checkSba = checkSba) { action(card, game) }
        val newId = instanceId(cardId)

        // Every zone transfer reallocates instanceId and retires the old one to Limbo
        check(origId != newId) { "instanceId should change on zone transfer ($cardName): $origId" }
        assertLimboContains(gsm, origId)

        return gsm to newId
    }

    /** Resolve a Forge card.id to its current proto instanceId. */
    fun instanceId(cardId: Int): Int = bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value

    // ----- Threaded action helpers (startGameAtMain1 tier) -----

    fun playLand(): PlayerAction.PlayLand? {
        val player = bridge.getPlayer(SeatId(1)) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(bridge, null) ?: return null
        val action = PlayerAction.PlayLand(ForgeCardId(land.id))
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, action)
        awaitFreshPending(bridge, pending.actionId)
        return action
    }

    fun castCreature(): PlayerAction.CastSpell? {
        val player = bridge.getPlayer(SeatId(1)) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(bridge, null) ?: return null
        val action = PlayerAction.CastSpell(ForgeCardId(creature.id))
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, action)
        awaitFreshPending(bridge, pending.actionId)
        return action
    }

    fun passPriority() {
        val pending = awaitFreshPending(bridge, null) ?: return
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, PlayerAction.PassPriority)
        awaitFreshPending(bridge, pending.actionId)
    }

    // ----- Instance probe DSL -----

    /** Battlefield zone of [this] player as a probe handle. */
    val Player.battlefield: PlayerZone get() = PlayerZone(this, ZoneType.Battlefield)

    /** Hand zone of [this] player as a probe handle. */
    val Player.hand: PlayerZone get() = PlayerZone(this, ZoneType.Hand)

    /** Graveyard zone of [this] player as a probe handle. */
    val Player.graveyard: PlayerZone get() = PlayerZone(this, ZoneType.Graveyard)

    /** Exile zone of [this] player as a probe handle. */
    val Player.exile: PlayerZone get() = PlayerZone(this, ZoneType.Exile)

    /** Library zone of [this] player as a probe handle. */
    val Player.library: PlayerZone get() = PlayerZone(this, ZoneType.Library)

    /**
     * Resolve a card by name within this (player, zone) handle to its
     * instanceId — call sites read like a path: `human.battlefield.iid("Walking Corpse")`.
     */
    fun PlayerZone.iid(cardName: String): Int {
        val card =
            player.getZone(zone).cards.firstOrNull { it.name == cardName }
                ?: error("Card '$cardName' not found in ${player.name} $zone")
        return instanceId(card.id)
    }

    /**
     * Resolve multiple cards by name in one go — `human.battlefield.iids("A", "B", "C")`.
     */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    companion object {
        // Guards Forge's static MyRandom during seed → shuffle → initial-draw.
        // See note in startGameAtMain1 for why.
        private val RNG_LOCK = Any()

        /**
         * Create + place a card in a zone. No bridge registration — used during
         * [startWithBoard] setup, before the board's card-registration pass runs.
         */
        internal fun createCard(
            name: String,
            player: Player,
            zone: ZoneType,
        ): Card {
            val paperCard =
                forge.model.FModel
                    .getMagicDb()
                    .commonCards
                    .getCard(name)
                    ?: run {
                        forge.StaticData.instance().attemptToLoadCard(name)
                        forge.model.FModel
                            .getMagicDb()
                            .commonCards
                            .getCard(name)
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
            val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
            val b = GameBridge(messageCounter = counter, cardRepository = TestCardRegistry.repo)

            val game = GameBootstrap.createGame()
            b.wrapGame(game)

            board(game, game.humanPlayer, game.aiPlayer)

            // Register all cards on the board in CardRepository + InstanceIdRegistry
            for (player in game.players) {
                for (zone in listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library)) {
                    for (card in player.getZone(zone).cards) {
                        TestCardRegistry.ensureCardRegistered(card.name)
                        b.getOrAllocInstanceId(ForgeCardId(card.id))
                    }
                }
            }

            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
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
            // Auto-register CardData for all cards in the deck list
            if (deckList != null) {
                TestCardRegistry.ensureDeckRegistered(deckList)
            }
            val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
            val b = GameBridge(messageCounter = counter, cardRepository = TestCardRegistry.repo)
            // Forge's MyRandom is a static Random. b.start(seed) replaces it via
            // MyRandom.setRandom(Random(seed)), so two concurrent Kotest specs
            // calling this race — one overwrites the other's RNG mid-shuffle and
            // non-deterministic hands result ("No land in hand at seed 42" flake
            // at kotest.framework.parallelism=8).
            //
            // Serialize the seed → shuffle → hand-draw window. After advanceToMain1
            // the library is fixed and further MyRandom writes from other specs
            // don't affect assertion outcomes in tests that don't trigger random
            // mid-game effects.
            val game =
                synchronized(RNG_LOCK) {
                    b.start(seed = seed, deckList = deckList, variant = variant)
                    b.submitKeep(SeatId(1))
                    advanceToMain1(b)
                    b.getGame()!!
                }
            check(game.phaseHandler.phase == PhaseType.MAIN1) {
                "Game should be at Main1 after advanceToMain1 (actual: ${game.phaseHandler.phase})"
            }
            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
        }

        /**
         * Start a game from an inline puzzle definition — no mulligan, no turn advancement.
         *
         * Much faster than [startGameAtMain1] (~0.3s vs ~1.5s) because it skips:
         * deck shuffle, mulligan keep, and priority-passing through upkeep/draw.
         *
         * @param puzzleText inline `.pzl` content (see `src/test/resources/puzzles/` for format)
         */
        fun startPuzzleAtMain1(puzzleText: String): Board = startPuzzleAtMain1(PuzzleSource.loadFromText(puzzleText))

        /** Convenience: load a puzzle from a test resource path (e.g. "puzzles/foo.pzl"). */
        fun startPuzzleAtMain1FromResource(resourcePath: String): Board = startPuzzleAtMain1(PuzzleSource.loadFromResource(resourcePath))

        private fun startPuzzleAtMain1(puzzle: forge.gamemodes.puzzle.Puzzle): Board {
            val counter = MessageCounter(initialGsId = 20, initialMsgId = 0)
            val b = GameBridge(messageCounter = counter, cardRepository = TestCardRegistry.repo)

            b.startPuzzle(puzzle)

            val game = b.getGame()!!
            TestCardRegistry.registerPuzzleCards(game)
            check(game.phaseHandler.phase == PhaseType.MAIN1) {
                "Puzzle game should be at Main1 (actual: ${game.phaseHandler.phase})"
            }
            b.seedDiffBaseline(game, counter.currentGsId())
            return Board(b, game, counter)
        }
    }
}
