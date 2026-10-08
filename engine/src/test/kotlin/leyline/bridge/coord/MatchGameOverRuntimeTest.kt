package leyline.bridge.coord

import forge.game.GameEndReason
import forge.game.player.GameLossReason
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
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
        for ((lossCause, annotationReason) in listOf(
            GameLossReason.LifeReachedZero to AnnotationLossReason.LifeTotal,
            GameLossReason.Poisoned to AnnotationLossReason.Poison,
            GameLossReason.Milled to AnnotationLossReason.DrawFromEmptyLibrary,
        )) {
            test("$lossCause completion commits one terminal outcome for every viewer exactly once") {
                val board = startWithBoard { _, _, _ -> }
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewers(
                    listOf(
                        ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                        ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                    ),
                )
                val playback = GamePlayback(board.bridge, 1)
                board.ai.loseConditionMet(lossCause, null)
                board.game.setGameOver(GameEndReason.AllOpposingTeamsLost)

                playback.onMainLoopStepCompleted()

                val outcome = coordinator.committedGameOverOutcome().shouldNotBeNull()
                val player = coordinator.drain(SeatId(1)).single()
                val observer = coordinator.drain(SeatId(2)).single()
                assertSoftly {
                    outcome shouldBe GameOverOutcome(ResultType.WinLoss, 1, ResultReason.Game_ae0a, 2, annotationReason)
                    player.last().intermissionReq.result shouldBe observer.last().intermissionReq.result
                    val playerLoss =
                        player
                            .first()
                            .gameStateMessage.persistentAnnotationsList
                            .single()
                    val observerLoss =
                        observer
                            .first()
                            .gameStateMessage.persistentAnnotationsList
                            .single()
                    playerLoss shouldBe observerLoss
                    board.bridge
                        .projectionStateSnapshot()
                        .persistentAnnotations.activeAnnotations[playerLoss.id] shouldBe playerLoss
                    playerLoss.id shouldBeGreaterThan 0
                    playerLoss.affectedIdsList shouldBe listOf(2)
                }

                playback.onMainLoopStepCompleted()

                assertSoftly {
                    coordinator.drain(SeatId(1)).shouldBeEmpty()
                    coordinator.drain(SeatId(2)).shouldBeEmpty()
                    coordinator.committedGameOverOutcome() shouldBe outcome
                }
            }
        }

        test("loss cause mapping keeps life total distinct from other causes") {
            assertSoftly {
                annotationLossReasonFor(GameLossReason.LifeReachedZero) shouldBe AnnotationLossReason.LifeTotal
                annotationLossReasonFor(GameLossReason.Poisoned) shouldBe AnnotationLossReason.Poison
                annotationLossReasonFor(GameLossReason.Milled) shouldBe AnnotationLossReason.DrawFromEmptyLibrary
                annotationLossReasonFor(GameLossReason.Conceded) shouldBe AnnotationLossReason.Concede
                for (reason in listOf(GameLossReason.CommanderDamage, GameLossReason.OpponentWon, GameLossReason.SpellEffect, null)) {
                    annotationLossReasonFor(reason) shouldBe AnnotationLossReason.Unspecified
                }
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
                    GameOverOutcome(ResultType.Draw_a544, 0, ResultReason.Game_ae0a, 0, AnnotationLossReason.Unspecified)
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
