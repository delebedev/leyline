package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameActionBridge
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.StateMapper

/**
 * End-to-end PvP test at the bridge level: two human seats play lands,
 * cast creatures, pass through turns, and verify per-seat visibility.
 *
 * Exercises [GameBridge.startTwoPlayer] + per-seat action bridges +
 * [StateMapper] visibility filtering. Uses bridge-level helpers (no
 * AutoPassEngine, which has a known PvP gap).
 *
 * Seat 2 is configured as synthetic (auto-pass, zero timeout) so the
 * engine advances instantly through seat 2's priority stops. This tests
 * the per-seat wiring without requiring full two-client interleaving.
 *
 * Single long test: engine boot is the expensive part (~0.5s). Each
 * action is <10ms. Sequential assertions at natural game checkpoints
 * read like a game transcript.
 */
class PvpBridgeEndToEndTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        test("two-player game: land, creature, turn rotation, per-seat visibility") {
            // --- Setup ---
            val deck = """
            20 Llanowar Elves
            40 Forest
            """.trimIndent()

            leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()

            val b = GameBridge(
                bridgeTimeoutMs = 5_000L,
                cards = TestCardRegistry.repo,
            )
            bridge = b
            b.priorityWaitMs = 2_000L
            // Seat 2 auto-passes instantly — tests per-seat wiring without full interleave
            b.configureSyntheticSeat(2)
            b.startTwoPlayer(seed = 42L, deckList1 = deck, deckList2 = deck)

            val game = b.getGame()!!
            game.isGameOver.shouldBeFalse()

            // With seed 42, determine who goes first
            val seat1Player = b.getPlayer(SeatId(1))!!
            // Advance seat 1 to Main1 (seat 2 auto-passes through their priority stops)
            val main1 = advanceToSeat(b, 1, "MAIN1")
            main1.state.phase shouldBe "MAIN1"

            // --- Checkpoint 1: seat 1 plays a land ---
            val land = seat1Player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val landForgeId = ForgeCardId(land.id)
            val handBefore = seat1Player.getZone(ZoneType.Hand).cards.size

            b.actionBridge(1).submitAction(main1.actionId, PlayerAction.PlayLand(landForgeId))
                .shouldBeTrue()

            val afterLand = awaitFreshPendingForSeat(b, 1, main1.actionId)!!

            // Land moved from hand to battlefield
            seat1Player.getZone(ZoneType.Hand).cards.size shouldBe (handBefore - 1)
            seat1Player.getZone(ZoneType.Battlefield).cards
                .any { it.id == land.id }.shouldBeTrue()

            // Both seat views see the land
            val gsm1 = StateMapper.buildFromGame(game, 1, "test", b, viewingSeatId = 1)
            val gsm2 = StateMapper.buildFromGame(game, 2, "test", b, viewingSeatId = 2)
            val landIid = b.getOrAllocInstanceId(landForgeId).value
            gsm1.gameObjectsList.any { it.instanceId == landIid }.shouldBeTrue()
            gsm2.gameObjectsList.any { it.instanceId == landIid }.shouldBeTrue()

            // --- Checkpoint 2: seat 1 casts a creature (Llanowar Elves, no targets) ---
            val creature = seat1Player.getZone(ZoneType.Hand).cards.first { it.isCreature }
            val creatureForgeId = ForgeCardId(creature.id)

            b.actionBridge(1).submitAction(afterLand.actionId, PlayerAction.CastSpell(creatureForgeId))
                .shouldBeTrue()

            // Spell on stack — pass priority to let it resolve (seat 2 auto-passes)
            val afterStack = awaitFreshPendingForSeat(b, 1, afterLand.actionId)!!
            b.actionBridge(1).submitAction(afterStack.actionId, PlayerAction.PassPriority)
            awaitFreshPendingForSeat(b, 1, afterStack.actionId)!!

            // Creature resolved onto battlefield
            seat1Player.getZone(ZoneType.Battlefield).cards
                .any { it.id == creature.id }.shouldBeTrue()

            // Both seats see it
            val gsmCast1 = StateMapper.buildFromGame(game, 3, "test", b, viewingSeatId = 1)
            val gsmCast2 = StateMapper.buildFromGame(game, 4, "test", b, viewingSeatId = 2)
            val creatureIid = b.getOrAllocInstanceId(creatureForgeId).value
            gsmCast1.gameObjectsList.any { it.instanceId == creatureIid }.shouldBeTrue()
            gsmCast2.gameObjectsList.any { it.instanceId == creatureIid }.shouldBeTrue()

            // --- Checkpoint 3: seat 2's hand is hidden from seat 1 ---
            val seat2Player = b.getPlayer(SeatId(2))!!
            val seat2HandIds = seat2Player.getZone(ZoneType.Hand).cards.map {
                b.getOrAllocInstanceId(ForgeCardId(it.id)).value
            }.toSet()
            val seat1VisibleIds = gsmCast1.gameObjectsList.map { it.instanceId }.toSet()
            seat2HandIds.intersect(seat1VisibleIds).size shouldBe 0

            // --- Checkpoint 4: gsId chain is monotonic ---
            gsmCast1.gameStateId.shouldBeGreaterThan(gsm1.gameStateId)
        }
    })

// --- Helpers ---

/**
 * Wait for a pending action on a specific seat's bridge.
 */
private fun awaitFreshPendingForSeat(
    b: GameBridge,
    seatId: Int,
    previousId: String?,
    timeoutMs: Long = 5_000,
): GameActionBridge.PendingAction? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = b.actionBridge(seatId).getPending()
        if (p != null && p.actionId != previousId && !p.future.isDone) return p
        Thread.sleep(50)
    }
    return null
}

/**
 * Advance to a target phase for a specific seat by passing priority.
 * Seat 2 is synthetic (auto-passes instantly), so only seat 1's
 * bridge produces actionable pendings.
 */
private fun advanceToSeat(
    b: GameBridge,
    seatId: Int,
    targetPhase: String,
    lastId: String? = null,
    maxPasses: Int = 80,
): GameActionBridge.PendingAction {
    var prevId = lastId
    repeat(maxPasses) {
        val game = b.getGame()
        if (game != null && game.isGameOver) {
            error("Game ended while advancing to $targetPhase for seat $seatId (turn ${game.phaseHandler.turn})")
        }
        val pending = awaitFreshPendingForSeat(b, seatId, prevId)
            ?: error("Timed out waiting for seat $seatId priority (game phase=${b.getGame()?.phaseHandler?.phase})")
        if (pending.state.phase == targetPhase) return pending
        b.actionBridge(seatId).submitAction(pending.actionId, PlayerAction.PassPriority)
        prevId = pending.actionId
    }
    error("Max passes exceeded advancing to $targetPhase for seat $seatId")
}
