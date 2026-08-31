package leyline.bridge.coord

import forge.game.GameEndReason
import forge.game.player.GameLossReason
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.annotations.AnnotationLossReason
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import wotc.mtgo.gre.external.messaging.Messages.ResultType

class MatchGameOverRuntimeTest :
    BoardTest({
        test("engine completion commits one terminal outcome for every viewer exactly once") {
            val board = startWithBoard { _, _, _ -> }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            val playback = GamePlayback(board.bridge, 1)
            board.ai.loseConditionMet(GameLossReason.Poisoned, null)
            board.game.setGameOver(GameEndReason.AllOpposingTeamsLost)

            playback.onMainLoopStepCompleted()

            val outcome = coordinator.committedGameOverOutcome().shouldNotBeNull()
            val player = coordinator.drain(SeatId(1)).single()
            val observer = coordinator.drain(SeatId(2)).single()
            assertSoftly {
                outcome shouldBe GameOverOutcome(ResultType.WinLoss, 1, ResultReason.Game_ae0a, 2, AnnotationLossReason.Poison)
                player.last().intermissionReq.result shouldBe observer.last().intermissionReq.result
            }

            playback.onMainLoopStepCompleted()

            assertSoftly {
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.drain(SeatId(2)).shouldBeEmpty()
                coordinator.committedGameOverOutcome() shouldBe outcome
            }
        }

        test("engine completion commits draw semantics without a losing player") {
            val board = startWithBoard { _, _, _ -> }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            val playback = GamePlayback(board.bridge, 1)
            board.human.intentionalDraw()
            board.ai.intentionalDraw()
            board.game.setGameOver(GameEndReason.Draw)

            playback.onMainLoopStepCompleted()

            assertSoftly {
                coordinator.committedGameOverOutcome() shouldBe
                    GameOverOutcome(ResultType.Draw_a544, 0, ResultReason.Game_ae0a, 0, AnnotationLossReason.LifeTotal)
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .first()
                    .gameStateMessage.gameInfo.resultsList
                    .single()
                    .result shouldBe ResultType.Draw_a544
            }
        }
    })
