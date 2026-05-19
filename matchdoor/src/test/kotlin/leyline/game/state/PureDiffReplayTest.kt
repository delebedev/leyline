package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.BoardTestBase
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Acceptance forcing function for the diff-pure refactor.
 *
 * Drives deterministic scripted scenarios (play land → cast creature → pass to
 * end of turn), records per-bundle (prev, cur, events, gsId, diff) during the
 * live run, then replays each step through a second bridge (started with the
 * same seed) and asserts byte-equal Diff GSM.
 *
 * Scenarios:
 * - One-turn: minimal baseline — single play/cast/end-of-turn cycle.
 * - Three-turn: multi-turn invariants — cross-turn annotation lifecycle,
 *   cleanup transitions, monotonic counters across bundle boundaries.
 * - Defer-invariant regression: guards the bridge.ids realloc-commit contract
 *   (compute-pure, apply-via-applyMutations).
 *
 * Any drift = impurity surfaced. Passing = pure-output semantics verified for
 * this feature surface (zone transfers, phase changes, combat, persistent
 * annotations tied to the permanents involved).
 *
 * Scenarios are scripted via puzzle fixtures and seeded deterministic runs.
 *
 * Out of scope per spec: EffectTracker layered effects, reveal-choose prompts,
 * steal effects.
 */
class PureDiffReplayTest :
    FunSpec({

        tags(BoardTag)

        beforeSpec { BoardTestBase().initCardDatabase() }

        fun <T> withBase(block: BoardTestBase.() -> T): T {
            val base = BoardTestBase()
            return try {
                base.block()
            } finally {
                base.tearDown()
            }
        }

        data class BundleStep(
            val prev: GsmSnapshot?,
            val cur: GsmSnapshot,
            val events: List<GameEvent>,
            val gameStateId: Int,
            val diff: GameStateMessage,
        )

        test("one-turn scripted scenario — snap-vs-snap diff byte-equal across replay") {
            withBase {
                // LIVE: drive the scenario, capture per-bundle records.
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val liveRun = mutableListOf<BundleStep>()

                liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                    liveRun.add(BundleStep(prev, cur, events.toList(), gsId, diff))
                }

                // Scripted scenario: play land → cast creature (if possible) → pass to end of turn.
                playLand(liveBridge)
                castCreature(liveBridge) // null-safe: skipped if no creature in hand
                advanceToEndOfTurn(liveBridge)

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
                            StateMapper.buildDiff(
                                prev = step.prev,
                                cur = step.cur,
                                events = FrameEventLog(step.events),
                                gameStateId = step.gameStateId,
                                matchId = BoardTestBase.TEST_MATCH_ID,
                                bridge = replayBridge,
                                updateType = updateType,
                                viewingSeatId = SEAT_ID,
                            )
                        replayBridge.applyMutations(replayResult.mutations)
                        replayResult.gsm.toByteArray().toList()
                    }
                val liveBytes = liveRun.map { it.diff.toByteArray().toList() }
                replayBytes shouldBe liveBytes
            }
        }

        test("three-turn scripted scenario — snap-vs-snap diff byte-equal across replay") {
            withBase {
                // Same shape as the one-turn test but drives three turns to exercise
                // multi-turn invariants: cross-turn annotation lifecycle, cleanup
                // transitions, monotonic counters across bundle boundaries.
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val liveRun = mutableListOf<BundleStep>()
                liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                    liveRun.add(BundleStep(prev, cur, events.toList(), gsId, diff))
                }

                repeat(3) {
                    playLand(liveBridge)
                    castCreature(liveBridge)
                    advanceToEndOfTurn(liveBridge)
                }

                liveRun.shouldNotBeEmpty()
                liveBridge.diffListener = null

                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)

                val replayBytes =
                    liveRun.map { step ->
                        val updateType = step.diff.update
                        val replayResult =
                            StateMapper.buildDiff(
                                prev = step.prev,
                                cur = step.cur,
                                events = FrameEventLog(step.events),
                                gameStateId = step.gameStateId,
                                matchId = BoardTestBase.TEST_MATCH_ID,
                                bridge = replayBridge,
                                updateType = updateType,
                                viewingSeatId = SEAT_ID,
                            )
                        replayBridge.applyMutations(replayResult.mutations)
                        replayResult.gsm.toByteArray().toList()
                    }
                val liveBytes = liveRun.map { it.diff.toByteArray().toList() }
                replayBytes shouldBe liveBytes
            }
        }

        // Regression guard: buildDiff MUST NOT commit id-realloc map writes during
        // compute. ZoneTransferDetector uses a local overlay; applyMutations is the
        // only place the forward/reverse maps advance.
        test("buildDiff defers bridge.ids realloc commits to applyMutations") {
            withBase {
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val captured = mutableListOf<BundleStep>()
                liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                    captured.add(BundleStep(prev, cur, events.toList(), gsId, diff))
                }
                playLand(liveBridge)
                castCreature(liveBridge)
                advanceToEndOfTurn(liveBridge)
                liveBridge.diffListener = null

                captured.shouldNotBeEmpty()

                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)

                // Walk the captured run. At each step with a non-trivial realloc: verify
                // the forward map still reflects the OLD id pre-apply (compute did not
                // commit), then the NEW id post-apply.
                var exercisedRealloc = false
                for (step in captured) {
                    val result =
                        StateMapper.buildDiff(
                            prev = step.prev,
                            cur = step.cur,
                            events = FrameEventLog(step.events),
                            gameStateId = step.gameStateId,
                            matchId = BoardTestBase.TEST_MATCH_ID,
                            bridge = replayBridge,
                            updateType = step.diff.update,
                            viewingSeatId = SEAT_ID,
                        )
                    val nonTrivial = result.mutations.idReallocations.filter { it.old != it.new }
                    for (r in nonTrivial) {
                        val fid =
                            replayBridge.getForgeCardId(r.old)
                                ?: error("reverse lookup for realloc.old=${r.old} returned null; bridge state corrupt")
                        // Pre-apply: compute must NOT have moved the forward map.
                        replayBridge.ids.peek(fid) shouldBe r.old
                    }
                    replayBridge.applyMutations(result.mutations)
                    for (r in nonTrivial) {
                        val fid =
                            replayBridge.getForgeCardId(r.new)
                                ?: error("reverse lookup for realloc.new=${r.new} returned null after apply")
                        // Post-apply: the map now reflects the new id.
                        replayBridge.ids.peek(fid) shouldBe r.new
                    }
                    if (nonTrivial.isNotEmpty()) exercisedRealloc = true
                }
                exercisedRealloc shouldBe true
            }
        }

        test("buildDiff defers delayed-trigger holder removals to applyMutations") {
            withBase {
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val captured = mutableListOf<BundleStep>()
                liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                    captured.add(BundleStep(prev, cur, events.toList(), gsId, diff))
                }
                playLand(liveBridge)
                castCreature(liveBridge)
                advanceToEndOfTurn(liveBridge)
                liveBridge.diffListener = null

                captured.shouldNotBeEmpty()

                liveBridge.shutdown()
                bridge = null
                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val holder = HolderRecord(iid = 777, ownerSeat = 1, objectSourceGrpId = 188698, parentIid = 100, cleanupGrpId = 189931)
                replayBridge.delayedTriggerHolders.apply(HolderBatch(added = listOf(holder), removed = emptyList()))
                val activeBefore = replayBridge.delayedTriggerHolders.activeIids()
                val step = captured.first()
                val replayResult =
                    StateMapper.buildDiff(
                        prev = step.prev,
                        cur = step.cur,
                        events = FrameEventLog(step.events),
                        gameStateId = step.gameStateId,
                        matchId = BoardTestBase.TEST_MATCH_ID,
                        bridge = replayBridge,
                        updateType = step.diff.update,
                        viewingSeatId = SEAT_ID,
                    )

                replayBridge.delayedTriggerHolders.activeIids() shouldBe activeBefore
                replayResult.mutations.holderBatch.removed shouldBe listOf(777)
                (777 in replayResult.gsm.diffDeletedInstanceIdsList) shouldBe true
                replayBridge.applyMutations(replayResult.mutations)
                replayBridge.delayedTriggerHolders.activeIids() shouldBe emptySet()
            }
        }
    }) {
    companion object {
        private const val SCENARIO_SEED = 42L
        private const val SEAT_ID = 1
    }
}

/**
 * Pass priority until the turn number changes. Covers all end-of-turn phases.
 * Capped at 60 passes to prevent runaway loops.
 */
private fun advanceToEndOfTurn(bridge: GameBridge) {
    val game = bridge.getGame() ?: return
    val startTurn = game.phaseHandler.turn
    repeat(60) {
        val pending = awaitFreshPending(bridge, null) ?: return
        val nowTurn = game.phaseHandler.turn
        if (nowTurn != startTurn) return
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, PlayerAction.PassPriority)
    }
}
