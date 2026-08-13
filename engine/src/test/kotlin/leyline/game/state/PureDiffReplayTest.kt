package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.IsolatedBoardLifecycle
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

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
 * - Defer-invariant regression: guards the atomic projection-transition contract.
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
            val input: StateFrameInput,
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
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val liveRun = mutableListOf<BundleStep>()

                liveBridge.diffListener = { input, diff ->
                    liveRun.add(BundleStep(input, diff))
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
                        replayBridge.replaceProjectionStateForTest(step.input.projectionState)
                        val updateType = step.diff.update
                        val replayResult =
                            StateMapper
                                .buildDiff(
                                    input = step.input.copy(updateType = updateType),
                                    matchId = Board.TEST_MATCH_ID,
                                    bridge = replayBridge,
                                ).finalizeAnnotations(step.riders())
                        replayBridge.commitProjection(checkNotNull(replayResult.transition))
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
                liveBridge.diffListener = { input, diff ->
                    liveRun.add(BundleStep(input, diff))
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
                        replayBridge.replaceProjectionStateForTest(step.input.projectionState)
                        val updateType = step.diff.update
                        val replayResult =
                            StateMapper
                                .buildDiff(
                                    input = step.input.copy(updateType = updateType),
                                    matchId = Board.TEST_MATCH_ID,
                                    bridge = replayBridge,
                                ).finalizeAnnotations(step.riders())
                        replayBridge.commitProjection(checkNotNull(replayResult.transition))
                        replayResult.gsm.toByteArray().toList()
                    }
                val liveBytes = liveRun.map { it.diff.toByteArray().toList() }
                replayBytes shouldBe liveBytes
            }
        }

        // Regression guard: buildDiff MUST NOT commit id-realloc map writes during
        // compute. ZoneTransferDetector uses a local overlay; transition install is
        // the only place the forward/reverse maps advance.
        test("buildDiff defers bridge.ids realloc commits until transition install") {
            withBase {
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val observed = mutableListOf<BundleStep>()
                liveBridge.diffListener = { input, diff ->
                    observed.add(BundleStep(input, diff))
                }
                playLand(liveBridge)
                castCreature(liveBridge)
                advanceToEndOfTurn(liveBridge)
                liveBridge.diffListener = null

                observed.shouldNotBeEmpty()

                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)

                // Walk the observed run. At each step with a non-trivial realloc: verify
                // the forward map still reflects the OLD id pre-apply (compute did not
                // commit), then the NEW id post-apply.
                var exercisedRealloc = false
                for (step in observed) {
                    replayBridge.replaceProjectionStateForTest(step.input.projectionState)
                    val draft =
                        StateMapper.buildDiff(
                            input = step.input.copy(updateType = step.diff.update),
                            matchId = Board.TEST_MATCH_ID,
                            bridge = replayBridge,
                        )
                    checkNotNull(draft.annotationFrameDraft)
                    draft.gsm.annotationsList.all { it.id == 0 } shouldBe true
                    val result = draft.finalizeAnnotations(step.riders())
                    val nonTrivial = result.output.idReallocations.filter { it.old != it.new }
                    for (r in nonTrivial) {
                        val fid =
                            replayBridge.getForgeCardId(r.old)
                                ?: error("reverse lookup for realloc.old=${r.old} returned null; bridge state corrupt")
                        // Pre-apply: compute must NOT have moved the forward map.
                        replayBridge.peekInstanceId(fid) shouldBe r.old
                    }
                    replayBridge.commitProjection(checkNotNull(result.transition))
                    for (r in nonTrivial) {
                        val fid =
                            replayBridge.getForgeCardId(r.new)
                                ?: error("reverse lookup for realloc.new=${r.new} returned null after apply")
                        // Post-apply: the map now reflects the new id.
                        replayBridge.peekInstanceId(fid) shouldBe r.new
                    }
                    if (nonTrivial.isNotEmpty()) exercisedRealloc = true
                }
                exercisedRealloc shouldBe true
            }
        }

        test("tentative projection compiles identically without advancing committed identity state") {
            withBase {
                val (replayBridge, game, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val snap = GsmSnapshot.capture(game, replayBridge, Board.TEST_MATCH_ID, 1)
                val before = replayBridge.projectionStateSnapshot()
                val journalBefore = replayBridge.annotationProjectionStateSnapshot()

                fun compile() =
                    StateMapper
                        .buildDiff(
                            prev = snap,
                            cur = snap,
                            events = FrameEventLog.EMPTY,
                            gameStateId = 2,
                            matchId = Board.TEST_MATCH_ID,
                            bridge = replayBridge,
                            updateType = GameStateUpdate.SendAndRecord,
                            viewingSeatId = SEAT_ID,
                            effectFacts = replayBridge.materializeEffectProjectionFacts(),
                            abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                        ).finalizeAnnotations()

                val first = compile()
                val afterFirst = replayBridge.projectionStateSnapshot()
                val journalAfterFirst = replayBridge.annotationProjectionStateSnapshot()
                val second = compile()
                val afterSecond = replayBridge.projectionStateSnapshot()
                val journalAfterSecond = replayBridge.annotationProjectionStateSnapshot()

                assertSoftly {
                    first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                    first.transition?.nextState shouldBe second.transition?.nextState
                    afterFirst shouldBe before
                    afterSecond shouldBe before
                    journalAfterFirst shouldBe journalBefore
                    journalAfterSecond shouldBe journalBefore
                }
            }
        }

        test("buildDiff defers delayed-trigger holder removals until transition install") {
            withBase {
                val (liveBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val observed = mutableListOf<BundleStep>()
                liveBridge.diffListener = { input, diff ->
                    observed.add(BundleStep(input, diff))
                }
                playLand(liveBridge)
                castCreature(liveBridge)
                advanceToEndOfTurn(liveBridge)
                liveBridge.diffListener = null

                observed.shouldNotBeEmpty()

                liveBridge.shutdown()
                bridge = null
                val (replayBridge, _, _) = startGameAtMain1(seed = SCENARIO_SEED)
                val holder = HolderRecord(iid = 777, ownerSeat = 1, objectSourceGrpId = 188698, parentIid = 100, cleanupGrpId = 189931)
                replayBridge.replaceProjectionStateForTest(
                    replayBridge.projectionStateSnapshot().copy(delayedTriggerHolders = mapOf(holder.iid to holder)),
                )
                val activeBefore = replayBridge.projectionStateSnapshot().delayedTriggerHolders.keys
                val step = observed.first()
                val replayResult =
                    StateMapper
                        .buildDiff(
                            input =
                                step.input.copy(
                                    updateType = step.diff.update,
                                    projectionState = replayBridge.projectionStateSnapshot(),
                                ),
                            matchId = Board.TEST_MATCH_ID,
                            bridge = replayBridge,
                        ).finalizeAnnotations(step.riders())

                assertSoftly("holder deletion is compute-time only until mutations apply") {
                    replayBridge.projectionStateSnapshot().delayedTriggerHolders.keys shouldBe activeBefore
                    replayResult.output.holderBatch.removed shouldBe listOf(777)
                    (777 in replayResult.gsm.diffDeletedInstanceIdsList) shouldBe true
                }
                replayBridge.commitProjection(checkNotNull(replayResult.transition))
                replayBridge.projectionStateSnapshot().delayedTriggerHolders.keys shouldBe emptySet()
            }
        }
    }) {
    companion object {
        private const val SCENARIO_SEED = 42L

        private const val SEAT_ID = 1

        private val DELIVERY_RIDER_TYPES =
            setOf(
                AnnotationType.PlayerSelectingTargets,
                AnnotationType.PlayerSubmittedTargets,
                AnnotationType.NewTurnStarted,
            )
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
