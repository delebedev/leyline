package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Acceptance forcing function for the diff-pure refactor.
 *
 * Drives a deterministic one-turn scenario (play land → cast creature → pass to
 * end of turn), records per-bundle (prev, cur, events, gsId, diff) during the
 * live run, then replays each step through a second bridge (started with the
 * same seed) and asserts byte-equal Diff GSM.
 *
 * Any drift = impurity surfaced. Passing = pure-output semantics verified for
 * this feature surface (zone transfers, phase changes, combat, persistent
 * annotations tied to the permanents involved).
 *
 * Out of scope per spec: EffectTracker layered effects, reveal-choose prompts,
 * steal effects.
 */
class PureDiffReplayTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        data class BundleStep(
            val prev: GsmSnapshot?,
            val cur: GsmSnapshot,
            val events: List<GameEvent>,
            val gameStateId: Int,
            val diff: GameStateMessage,
        )

        test("one-turn scripted scenario — snap-vs-snap diff byte-equal across replay") {
            // LIVE: drive the scenario, capture per-bundle records.
            val (liveBridge, _, _) = base.startGameAtMain1(seed = SCENARIO_SEED)
            val liveRun = mutableListOf<BundleStep>()

            liveBridge.diffListener = { prev, cur, events, gsId, diff ->
                liveRun.add(BundleStep(prev, cur, events.toList(), gsId, diff))
            }

            // Scripted scenario: play land → cast creature (if possible) → pass to end of turn.
            base.playLand(liveBridge)
            base.castCreature(liveBridge) // null-safe: skipped if no creature in hand
            advanceToEndOfTurn(liveBridge)

            liveRun.shouldNotBeEmpty()

            // Disarm listener so replay bundles don't re-record.
            liveBridge.diffListener = null

            // REPLAY: second bridge, same seed → identical initial state (same card IDs,
            // same zone assignments, same annotation counters after seedDiffBaseline).
            val (replayBridge, _, _) = base.startGameAtMain1(seed = SCENARIO_SEED)

            for ((i, step) in liveRun.withIndex()) {
                // Use the live run's updateType (embedded in the diff GSM) to match exactly.
                val updateType = step.diff.update
                val replayResult = StateMapper.buildDiff(
                    prev = step.prev,
                    cur = step.cur,
                    events = step.events,
                    gameStateId = step.gameStateId,
                    matchId = ConformanceTestBase.TEST_MATCH_ID,
                    bridge = replayBridge,
                    updateType = updateType,
                    viewingSeatId = SEAT_ID,
                )
                replayBridge.applyMutations(replayResult.mutations)
                replayBridge.lastSent = step.cur

                if (!replayResult.gsm.toByteArray().contentEquals(step.diff.toByteArray())) {
                    error(
                        "Replay drift at step $i (gsId=${step.gameStateId}):\n" +
                            " live=${step.diff}\n" +
                            " replay=${replayResult.gsm}",
                    )
                }
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
    for (i in 0 until 60) {
        val pending = awaitFreshPending(bridge, null) ?: return
        val nowTurn = game.phaseHandler.turn
        if (nowTurn != startTurn) return
        bridge.actionBridge(1).submitAction(pending.actionId, leyline.bridge.PlayerAction.PassPriority)
    }
}
