package leyline.game.state

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.IsolatedBoardLifecycle
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Replay sentinel for deterministic diff projection.
 *
 * Drives one seeded play/cast/turn scenario, collects each immutable projection
 * input, then replays the inputs through a second bridge and requires byte-equal
 * Diff GSMs. A direct zone-transfer case pins the deferred identity-commit seam.
 *
 * Mechanic-specific lifecycle and projection shapes stay in their pipeline and
 * mechanics suites.
 */
class PureDiffReplayTest :
    BoardTest({

        // Each test drives its own isolated lifecycle (not the BoardTest-level
        // shared one) so live/replay bridge pairs stay independent within a test.
        fun <T> withBase(block: IsolatedBoardLifecycle.() -> T): T {
            val base = IsolatedBoardLifecycle()
            return try {
                base.block()
            } finally {
                base.tearDown()
            }
        }

        data class BundleStep(
            val prev: GsmSnapshot?,
            val cur: GsmSnapshot,
            val events: FrameEventLog,
            val gameStateId: Int,
            val diff: GameStateMessage,
        ) {
            fun riders(): List<AnnotationInfo> =
                diff.annotationsList
                    .filter { annotation -> annotation.typeList.any { it in DELIVERY_RIDER_TYPES } }
                    .map { it.toBuilder().clearId().build() }
        }

        test("one-turn scripted scenario — snap-vs-snap diff byte-equal across replay") {
            withBase {
                // LIVE: drive the scenario, capture per-bundle records.
                val liveBoard = startGameAtMain1(seed = SCENARIO_SEED)
                val liveBridge = liveBoard.bridge
                val liveRun = mutableListOf<BundleStep>()

                liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                    liveRun.add(BundleStep(prev, cur, events.toList(), gsId, diff))
                }

                playLand(liveBridge) ?: error("seeded replay scenario requires a playable land")
                castCreature(liveBridge) ?: error("seeded replay scenario requires a castable creature")
                advanceToEndOfTurn(liveBridge)
                liveBoard.drainPlayback()

                liveRun.shouldNotBeEmpty()

                // Disarm listener so replay bundles don't re-record.
                liveBridge.diffListener = null

                // REPLAY: second bridge, same seed → identical initial state (same card IDs,
                // same zone assignments, same annotation counters after seedDiffBaseline).
                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)

                val replayBytes =
                    liveRun.map { step ->
                        val updateType = step.diff.update
                        val replayResult =
                            StateMapper
                                .buildDiff(
                                    prev = step.prev,
                                    cur = step.cur,
                                    events = step.events,
                                    gameStateId = step.gameStateId,
                                    matchId = Board.TEST_MATCH_ID,
                                    bridge = replayBridge,
                                    updateType = updateType,
                                    viewingSeatId = SEAT_ID,
                                ).finalizeAnnotations(step.riders())
                        replayBridge.applyMutations(replayResult.mutations)
                        replayResult.gsm.toByteArray().toList()
                    }
                val liveBytes = liveRun.map { it.diff.toByteArray().toList() }
                replayBytes shouldBe liveBytes
            }
        }

        test("zone-transfer identity changes commit after diff projection") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }
            val land =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val forgeCardId = ForgeCardId(land.id)
            val oldId = checkNotNull(board.bridge.ids.peek(forgeCardId))
            board.bridge.closeBundleFrame()
            val previous = GsmSnapshot.capture(board.game, board.bridge, Board.TEST_MATCH_ID, 1)

            board.game.action.moveToPlay(land, null, AbilityKey.newMap())
            val current = GsmSnapshot.capture(board.game, board.bridge, Board.TEST_MATCH_ID, 2)
            val events = board.bridge.closeBundleFrame()
            val projected =
                StateMapper
                    .buildDiff(
                        prev = previous,
                        cur = current,
                        events = events,
                        gameStateId = 2,
                        matchId = Board.TEST_MATCH_ID,
                        bridge = board.bridge,
                        viewingSeatId = SEAT_ID,
                    ).finalizeAnnotations()
            val reallocation = projected.mutations.idReallocations.single { it.old == oldId }

            board.bridge.ids.peek(forgeCardId) shouldBe oldId
            board.bridge.getForgeCardId(reallocation.new) shouldBe null

            board.bridge.applyMutations(projected.mutations)

            board.bridge.ids.peek(forgeCardId) shouldBe reallocation.new
            board.bridge.getForgeCardId(reallocation.new) shouldBe forgeCardId
        }

        test("holder removals commit after diff projection") {
            val board = startWithBoard { _, _, _ -> }
            val holder =
                HolderRecord(
                    iid = 777,
                    ownerSeat = 1,
                    objectSourceGrpId = 188698,
                    parentIid = 100,
                    cleanupGrpId = 189931,
                )
            board.bridge.delayedTriggerHolders.apply(HolderBatch(added = listOf(holder), removed = emptyList()))
            board.bridge.closeBundleFrame()
            val previous = GsmSnapshot.capture(board.game, board.bridge, Board.TEST_MATCH_ID, 1)
            val current = GsmSnapshot.capture(board.game, board.bridge, Board.TEST_MATCH_ID, 2)

            val projected =
                StateMapper
                    .buildDiff(
                        prev = previous,
                        cur = current,
                        events = FrameEventLog.EMPTY,
                        gameStateId = 2,
                        matchId = Board.TEST_MATCH_ID,
                        bridge = board.bridge,
                        viewingSeatId = SEAT_ID,
                    ).finalizeAnnotations()

            assertSoftly {
                board.bridge.delayedTriggerHolders.activeIids() shouldBe setOf(777)
                projected.mutations.holderBatch.removed shouldBe listOf(777)
                (777 in projected.gsm.diffDeletedInstanceIdsList) shouldBe true
            }

            board.bridge.applyMutations(projected.mutations)

            board.bridge.delayedTriggerHolders.activeIids() shouldBe emptySet()
        }
    }) {
    companion object {
        private const val SCENARIO_SEED = 42L

        private fun FrameEventLog.toList(): FrameEventLog = this

        private const val SEAT_ID = 1

        private val DELIVERY_RIDER_TYPES =
            setOf(
                AnnotationType.PlayerSelectingTargets,
                AnnotationType.PlayerSubmittedTargets,
                AnnotationType.NewTurnStarted,
            )
    }
}

/** Pass priority until the turn changes, with a bounded runaway guard. */
private fun advanceToEndOfTurn(bridge: GameBridge) {
    val game = checkNotNull(bridge.getGame())
    val startTurn = game.phaseHandler.turn
    repeat(60) {
        if (game.phaseHandler.turn != startTurn) return
        val pending =
            checkNotNull(awaitFreshPending(bridge, null)) {
                "replay scenario lost priority before the turn changed"
            }
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, PlayerAction.PassPriority)
    }
    check(game.phaseHandler.turn != startTurn) {
        "replay scenario did not reach the next turn after 60 priority passes"
    }
}
