package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.awaitFreshPending
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ViewerProjectionIntent
import leyline.testkit.IsolatedBoardLifecycle
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/** One engine-level agreement proving deterministic replay of compiler inputs. */
class PureDiffReplayTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        data class BundleStep(
            val input: StateFrameInput,
            val prior: ProjectionState,
            val intent: ViewerProjectionIntent,
            val diff: GameStateMessage,
        )

        test("one-turn scripted scenario replays byte-identically") {
            val live = IsolatedBoardLifecycle()
            val replay = IsolatedBoardLifecycle()
            try {
                val (liveBridge, _, _) = live.startGameAtMain1(seed = SCENARIO_SEED)
                val liveRun = mutableListOf<BundleStep>()
                liveBridge.diffListener = { input, prior, intent, diff -> liveRun.add(BundleStep(input, prior, intent, diff)) }

                live.playLand(liveBridge)
                live.castCreature(liveBridge)
                advanceToEndOfTurn(liveBridge)
                liveRun.shouldNotBeEmpty()
                liveBridge.diffListener = null

                val (replayBridge, _, _) = replay.startGameAtMain1(seed = SCENARIO_SEED)
                val replayBytes =
                    liveRun.map { step ->
                        val replayPrior = replayBridge.projectionStateSnapshot()
                        replayPrior shouldBe step.prior
                        val result =
                            StateProjectionCompiler.compileOneViewer(
                                replayBridge.stateProjectionEnvironment,
                                step.input.copy(updateType = step.diff.update),
                                replayPrior,
                                step.intent,
                            )
                        replayBridge.commitProjection(result.transition)
                        result.gsm.toByteArray().toList()
                    }

                replayBytes shouldBe liveRun.map { it.diff.toByteArray().toList() }
            } finally {
                replay.tearDown()
                live.tearDown()
            }
        }
    }) {
    companion object {
        private const val SCENARIO_SEED = 42L
    }
}

private fun advanceToEndOfTurn(bridge: GameBridge) {
    val game = bridge.getGame() ?: return
    val startTurn = game.phaseHandler.turn
    repeat(60) {
        val pending = awaitFreshPending(bridge, null) ?: return
        if (game.phaseHandler.turn != startTurn) return
        bridge.actionBridge(SeatId(1)).submitTestRuntimeAction(pending.actionId, PlayerAction.PassPriority)
    }
}
