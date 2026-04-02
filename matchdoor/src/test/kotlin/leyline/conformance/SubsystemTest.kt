package leyline.conformance

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Base class for subsystem tests (land/mana, combat, stack, etc.).
 *
 * Extends FunSpec — no `val base =` boilerplate. All ConformanceTestBase
 * helpers available directly. Wires initCardDatabase/tearDown automatically.
 *
 * ```
 * class LandManaTest : SubsystemTest({
 *     test("Forest — ColorProduction [5]") {
 *         val (b, game, counter) = startWithBoard { _, human, _ ->
 *             addCard("Forest", human, ZoneType.Hand)
 *         }
 *         ...
 *     }
 * })
 * ```
 */
abstract class SubsystemTest(body: SubsystemTest.() -> Unit) : FunSpec() {

    private val base = ConformanceTestBase()

    val humanSeat = SeatId(1)

    init {
        tags(ConformanceTag)
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }
        body()
    }

    // --- Delegated setup ---

    fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit) =
        base.startWithBoard(board)

    fun startGameAtMain1(seed: Long = 42L, deckList: String? = null) =
        base.startGameAtMain1(seed, deckList)

    fun startPuzzleAtMain1(puzzleText: String) =
        base.startPuzzleAtMain1(puzzleText)

    fun addCard(name: String, player: Player, zone: ZoneType = ZoneType.Battlefield): Card =
        base.addCard(name, player, zone)

    // --- Capture ---

    fun capture(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
        checkSba: Boolean = false,
        action: () -> Unit,
    ): GameStateMessage = base.captureAfterAction(b, game, counter, checkSba, action)

    // --- Board actions ---

    /** Move card to battlefield — raw zone move, no events, no triggers. For setup. */
    fun moveToBattlefield(card: Card, game: Game) {
        game.action.moveToPlay(card, null, AbilityKey.newMap())
    }

    /** Play first land from hand via Forge's full path. Fires GameEventLandPlayed. */
    fun playLandFromHand(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
    ): GameStateMessage {
        val player = humanPlayer(b)
        val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
        return capture(b, game, counter) {
            player.playLand(land, true, null)
        }
    }

    // --- Player helpers ---

    fun humanPlayer(b: GameBridge): Player = b.getPlayer(humanSeat)!!

    // --- ID helpers ---

    /** Resolve a Forge card.id to its current proto instanceId. */
    fun GameBridge.instanceId(cardId: Int): Int =
        getOrAllocInstanceId(ForgeCardId(cardId)).value

    // --- Game action wrappers (hide Forge internals) ---

    fun destroy(card: Card, game: Game) {
        game.action.destroy(card, null, false, AbilityKey.newMap())
    }

    fun exile(card: Card, game: Game) {
        game.action.exile(card, null, AbilityKey.newMap())
    }

    /** Find card by name, perform action, assert realloc + Limbo, return (gsm, newInstanceId). */
    fun transferCard(
        b: GameBridge,
        game: Game,
        counter: MessageCounter,
        cardName: String,
        checkSba: Boolean = false,
        action: (Card, Game) -> Unit,
    ): Pair<GameStateMessage, Int> {
        val player = humanPlayer(b)
        val card = listOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile)
            .firstNotNullOf { zone -> player.getZone(zone).cards.firstOrNull { it.name == cardName } }
        val origId = b.instanceId(card.id)
        val cardId = card.id

        val gsm = capture(b, game, counter, checkSba = checkSba) { action(card, game) }
        val newId = b.instanceId(cardId)

        // Every zone transfer reallocates instanceId and retires the old one to Limbo
        check(origId != newId) { "instanceId should change on zone transfer ($cardName): $origId" }
        assertLimboContains(gsm, origId)

        return gsm to newId
    }

    // --- Delegated bundle/capture ---

    fun bundleBuilder(b: GameBridge) = base.bundleBuilder(b)
    fun postAction(game: Game, b: GameBridge, counter: MessageCounter) = base.postAction(game, b, counter)
    fun gameStart(game: Game, b: GameBridge, counter: MessageCounter) = base.gameStart(game, b, counter)
    fun handshakeFull(game: Game, b: GameBridge, gsId: Int) = base.handshakeFull(game, b, gsId)
    fun playLand(b: GameBridge) = base.playLand(b)
    fun castCreature(b: GameBridge) = base.castCreature(b)
    fun passPriority(b: GameBridge) = base.passPriority(b)

    // --- Cast/resolve convenience captures ---

    fun castSpellBundle() = base.castSpellBundle()
    fun castSpellAndCapture() = base.castSpellAndCapture()
    fun castSpellAndCaptureWithIds() = base.castSpellAndCaptureWithIds()
    fun resolveAndCapture() = base.resolveAndCapture()

    companion object {
        const val SEAT_ID = ConformanceTestBase.SEAT_ID
    }
}
