package leyline.testkit

import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge

/**
 * A standalone bridge/counter lifecycle for tests that need more than one
 * independent [Board] instance within a single test body — e.g. driving a
 * live bridge and a separately-seeded replay bridge side by side (see
 * `PureDiffReplayTest`). [BoardTest] owns one shared bridge per spec; use
 * this seam instead of a second `BoardTest` when a test body needs an extra,
 * fully independent instance with its own teardown.
 *
 * Card-database initialization is spec-level (wired by [BoardTest]'s
 * `beforeSpec`), so this class only tracks the bridge/counter pair — no
 * `initCardDatabase` of its own.
 */
class IsolatedBoardLifecycle {
    var bridge: GameBridge? = null
    var testCounter: MessageCounter = MessageCounter()

    fun tearDown() {
        bridge?.shutdown()
        bridge = null
        testCounter = MessageCounter()
    }

    fun startWithBoard(board: (game: Game, human: Player, ai: Player) -> Unit): Board = trackResult(Board.startWithBoard(board))

    fun startGameAtMain1(
        seed: Long = 42L,
        deckList: String? = null,
        variant: String? = null,
    ): Board = trackResult(Board.startGameAtMain1(seed, deckList, variant))

    fun startPuzzleAtMain1(puzzleText: String): Board = trackResult(Board.startPuzzleAtMain1(puzzleText))

    fun startPuzzleAtMain1FromResource(resourcePath: String): Board = trackResult(Board.startPuzzleAtMain1FromResource(resourcePath))

    fun playLand(bridge: GameBridge): PlayerAction.PlayLand? {
        val player = bridge.getPlayer(SeatId(1)) ?: return null
        val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val pending = awaitFreshPending(bridge, null) ?: return null
        val action = PlayerAction.PlayLand(ForgeCardId(land.id))
        bridge.actionBridge(SeatId(1)).submitTestRuntimeAction(pending.actionId, action)
        awaitFreshPending(bridge, pending.actionId)
        return action
    }

    fun castCreature(bridge: GameBridge): PlayerAction.CastSpell? {
        val player = bridge.getPlayer(SeatId(1)) ?: return null
        val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val pending = awaitFreshPending(bridge, null) ?: return null
        val action = PlayerAction.CastSpell(ForgeCardId(creature.id))
        bridge.actionBridge(SeatId(1)).submitTestRuntimeAction(pending.actionId, action)
        awaitFreshPending(bridge, pending.actionId)
        return action
    }

    private fun trackResult(result: Board): Board {
        bridge = result.bridge
        testCounter = result.counter
        return result
    }
}
