package leyline.testkit

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge

/**
 * Base class for board-tier tests (land/mana, combat, stack, etc.).
 *
 * Extends FunSpec — no `val base =` boilerplate. All BoardTestBase
 * helpers available directly. Wires initCardDatabase/tearDown automatically.
 *
 * ```
 * class LandManaTest : BoardTest({
 *     test("Forest — ColorProduction [5]") {
 *         val board = startWithBoard { _, human, _ ->
 *             addCard("Forest", human, ZoneType.Hand)
 *         }
 *         ...
 *     }
 * })
 * ```
 */
// `abstract` keeps Kotest's auto-discovery from trying to instantiate the base
// class directly (no zero-arg constructor — only the `body` lambda variant).
@Suppress("UnnecessaryAbstractClass")
abstract class BoardTest(
    body: BoardTest.() -> Unit,
) : FunSpec() {
    private val base = BoardTestBase()

    init {
        tags(BoardTag)
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }
        body()
    }

    // --- Delegated setup ---

    fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit) = base.startWithBoard(board)

    fun startGameAtMain1(
        seed: Long = 42L,
        deckList: String? = null,
        variant: String? = null,
    ) = base.startGameAtMain1(seed, deckList, variant)

    fun startPuzzleAtMain1(puzzleText: String) = base.startPuzzleAtMain1(puzzleText)

    fun startPuzzleAtMain1FromResource(resourcePath: String) = base.startPuzzleAtMain1FromResource(resourcePath)

    /** Register a manually constructed [GameBridge] so teardown shuts it down after the test. */
    fun useBridge(b: GameBridge) {
        base.bridge = b
    }

    fun addCard(
        name: String,
        player: Player,
        zone: ZoneType = ZoneType.Battlefield,
    ): Card = base.addCard(name, player, zone)

    // --- Board actions ---

    /** Move card to battlefield — raw zone move, no events, no triggers. For setup. */
    fun moveToBattlefield(
        card: Card,
        game: Game,
    ) {
        game.action.moveToPlay(card, null, AbilityKey.newMap())
    }

    // --- ID helpers ---

    /** Resolve a Forge card.id to its current proto instanceId. */
    fun GameBridge.instanceId(cardId: Int): Int = getOrAllocInstanceId(ForgeCardId(cardId)).value

    // --- Game action wrappers (hide Forge internals) ---

    fun destroy(
        card: Card,
        game: Game,
    ) {
        game.action.destroy(card, null, false, AbilityKey.newMap())
    }

    fun exile(
        card: Card,
        game: Game,
    ) {
        game.action.exile(card, null, AbilityKey.newMap())
    }

    // --- Instance probe DSL ---

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
     * instanceId — call sites read like a path: `human.battlefield.iid("Grizzly Bears")`.
     * Resolves through the most recent `start*` board's bridge.
     */
    fun PlayerZone.iid(cardName: String): Int = iidVia(currentBridge(), cardName)

    /** Resolve multiple cards by name in one go — `human.battlefield.iids("A", "B")`. */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    private fun currentBridge(): GameBridge = base.bridge ?: error("Call a start*() method before using the probe DSL")

    // --- Delegated bundle/capture ---

    fun bundleBuilder(b: GameBridge) = base.bundleBuilder(b)

    fun handshakeFull(
        game: Game,
        b: GameBridge,
        gsId: Int,
    ) = base.handshakeFull(game, b, gsId)

    fun playLand(b: GameBridge) = base.playLand(b)

    fun castCreature(b: GameBridge) = base.castCreature(b)

    fun passPriority(b: GameBridge) = base.passPriority(b)

    // --- Cast/resolve convenience captures ---

    fun castSpellBundle() = base.castSpellBundle()

    fun castSpellAndCapture() = base.castSpellAndCapture()

    fun castSpellAndCaptureWithIds() = base.castSpellAndCaptureWithIds()

    fun resolveAndCapture() = base.resolveAndCapture()

    fun playLandAndCapture() = base.playLandAndCapture()

    companion object {
        const val SEAT_ID = Board.SEAT_ID
    }
}
