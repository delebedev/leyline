package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import leyline.BoardTag
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.conformance.BoardTestBase
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType

/**
 * Regression guard for leyline-f5mw: persistent annotations must be emitted
 * exactly once on the Diff GSM that adds them, not re-asserted on every
 * subsequent Diff GSM.
 *
 * Wire contract (protocol spec): `persistentAnnotations[]` on a Diff GSM
 * carries only *newly added* entries since the last GSM. The client accumulates
 * them across diffs; removal is signaled separately via
 * `diffDeletedPersistentAnnotationIds`. A Full GSM carries the complete list.
 *
 * Before this guard: `StateMapper.buildDiff` embedded
 * `current.persistentAnnotationsList` verbatim on every Diff — every subsequent
 * diff re-emitted the same IDs, visible in fixture traces as `EZTT, CP` on
 * every post-entry frame.
 */
class PersistentAnnotationDiffTest :
    FunSpec({
        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("persistent annotation IDs appear on exactly one Diff GSM in a scripted scenario") {
            val (bridge, _, _) = base.startGameAtMain1(seed = 42L)
            val diffs = mutableListOf<GameStateMessage>()
            bridge.diffListener = { _, _, _, _, diff -> diffs.add(diff) }

            // Scenario: play a land (creates EZTT + ColorProduction persistent
            // annotations), then pass through the full turn so we accumulate
            // many Diff GSMs after the persistent state has changed.
            base.playLand(bridge) ?: error("scenario requires a playable land in opening hand")
            advanceToEndOfTurnLocal(bridge)

            bridge.diffListener = null

            diffs.shouldNotBeEmpty()

            // Collect persistent annotation IDs per Diff GSM. Full GSMs carry
            // the complete list by design — they're baseline, not delta.
            val idsByDiff: List<Pair<Int, Set<Int>>> =
                diffs
                    .filter { it.type == GameStateType.Diff }
                    .map { gsm ->
                        gsm.gameStateId to gsm.persistentAnnotationsList.map { it.id }.toSet()
                    }

            // Invariant: each persistent annotation ID must appear in the
            // `persistentAnnotations[]` list of AT MOST ONE Diff GSM.
            val seen = mutableMapOf<Int, Int>() // annId → first gsId that emitted it
            val offenders = mutableListOf<Triple<Int, Int, Int>>() // (annId, firstGsId, repeatGsId)
            for ((gsId, annIds) in idsByDiff) {
                for (annId in annIds) {
                    val first = seen[annId]
                    if (first == null) {
                        seen[annId] = gsId
                    } else {
                        offenders.add(Triple(annId, first, gsId))
                    }
                }
            }

            assertSoftly {
                // We expect AT LEAST one persistent annotation to have been
                // emitted — otherwise the scenario didn't exercise the bug.
                seen.shouldNotBeEmpty()
                offenders.shouldBeEmpty()
            }
        }
    })

// Local copy of PureDiffReplayTest's helper — pass priority until the turn
// number changes, covering all end-of-turn phases. Capped at 60 passes.
private fun advanceToEndOfTurnLocal(bridge: GameBridge) {
    val game = bridge.getGame() ?: return
    val startTurn = game.phaseHandler.turn
    repeat(60) {
        val pending = awaitFreshPending(bridge, null) ?: return
        val nowTurn = game.phaseHandler.turn
        if (nowTurn != startTurn) return
        bridge.actionBridge(SeatId(1)).submitAction(pending.actionId, PlayerAction.PassPriority)
    }
}
