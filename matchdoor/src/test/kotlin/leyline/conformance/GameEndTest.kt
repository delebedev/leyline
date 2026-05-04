package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Tests for game-end protocol: IntermissionReq + MatchCompleted room state.
 *
 * The Arena client requires [MatchGameRoomStateType.MatchCompleted] to trigger
 * the result screen. Without it, the client stays on the game board after the
 * server sends the game-over GRE sequence.
 */
class GameEndTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("concede produces MatchCompleted") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeep()

            // Concede triggers sendGameOver()
            val snap = h.messageSnapshot()
            h.session.onConcede()
            h.drainSink()

            // Verify GRE messages: 3x GSM + IntermissionReq
            val msgs = h.messagesSince(snap)
            val gsmCount = msgs.count { it.hasGameStateMessage() }
            val intermission = msgs.firstOrNull { it.hasIntermissionReq() }

            gsmCount shouldBeGreaterThanOrEqualTo 3
            intermission.shouldNotBeNull()

            // First GSM should have GameInfo with stage=GameOver
            val firstGsm = msgs.first { it.hasGameStateMessage() }.gameStateMessage
            assertSoftly {
                firstGsm.hasGameInfo().shouldBeTrue()
                firstGsm.gameInfo.stage shouldBe GameStage.GameOver
                firstGsm.gameInfo.matchState shouldBe MatchState.GameComplete
            }

            // IntermissionReq should have result with winning team + reason
            val req = intermission.intermissionReq
            assertSoftly {
                req.hasResult().shouldBeTrue()
                req.result.result shouldBe ResultType.WinLoss
                req.result.winningTeamId shouldBeGreaterThan 0
                req.result.reason shouldBe ResultReason.Concede
            }

            // IntermissionReq should have options + intermissionPrompt
            assertSoftly {
                req.optionsCount shouldBeGreaterThan 0
                req.optionsCount shouldBeGreaterThanOrEqualTo 2
                req.hasIntermissionPrompt().shouldBeTrue()
                req.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
                req.intermissionPrompt.parametersCount shouldBeGreaterThan 0
            }

            // prevGameStateId chain: gs1.prev = last-known, gs2.prev = gs1, gs3.prev = gs2
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            assertSoftly {
                gsms.size shouldBeGreaterThanOrEqualTo 3
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
            }

            // MatchCompleted room state should be in allRawMessages
            val rawMsgs = h.allRawMessages
            val matchCompleted =
                rawMsgs.firstOrNull {
                    it.hasMatchGameRoomStateChangedEvent() &&
                        it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                        MatchGameRoomStateType.MatchCompleted
                }
            matchCompleted.shouldNotBeNull()

            // Verify FinalMatchResult
            val finalResult = matchCompleted.matchGameRoomStateChangedEvent.gameRoomInfo.finalMatchResult
            assertSoftly {
                finalResult.matchCompletedReason shouldBe MatchCompletedReasonType.Success_a26d
                finalResult.resultListCount shouldBeGreaterThan 0
                finalResult.getResultList(0).result shouldBe ResultType.WinLoss
            }

            h.registry.getMatch("test-match").shouldBeNull()
            h.registry.getPeer("test-match", SeatId(1)).shouldBeNull()
        }

        test("lethal damage produces MatchCompleted room state") {
            // Puzzle: 3 haste creatures vs AI at 3 life — attack all = lethal
            val pzl =
                """
                [metadata]
                Name:Lethal Attack
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Attack with 3 Raging Goblins for lethal.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=3

                humanbattlefield=Mountain;Mountain;Mountain;Raging Goblin;Raging Goblin;Raging Goblin
                humanhand=Mountain
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            // Advance to combat
            val startTurn = h.turn()
            h.passPriority()

            // Attack all
            h.declareAllAttackers()
            h.submitAttackers()

            // Pass through remaining combat phases
            h.passThroughCombat(startTurn)

            h.isGameOver().shouldBeTrue()

            // Verify MatchCompleted was sent
            val rawMsgs = h.allRawMessages
            val matchCompleted =
                rawMsgs.firstOrNull {
                    it.hasMatchGameRoomStateChangedEvent() &&
                        it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                        MatchGameRoomStateType.MatchCompleted
                }
            matchCompleted.shouldNotBeNull()

            // Verify IntermissionReq with correct fields
            val intermission =
                checkNotNull(h.allMessages.firstOrNull { it.hasIntermissionReq() }) {
                    "Should have IntermissionReq after lethal damage"
                }
            val req = intermission.intermissionReq
            assertSoftly {
                req.result.reason shouldBe ResultReason.Game_ae0a
                req.optionsCount shouldBeGreaterThanOrEqualTo 2
                req.hasIntermissionPrompt().shouldBeTrue()
                req.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
                req.intermissionPrompt.parametersCount shouldBeGreaterThan 0
            }

            // Game-over GSMs: the 3 GSMs immediately before IntermissionReq
            val allMsgs = h.allMessages
            val intermissionIdx = allMsgs.indexOfFirst { it.hasIntermissionReq() }
            intermissionIdx shouldBeGreaterThanOrEqualTo 3
            val gameOverGsms =
                allMsgs
                    .subList(intermissionIdx - 3, intermissionIdx)
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
            gameOverGsms.size shouldBe 3

            // First game-over GSM should have LossOfGame annotation
            val lossAnnotation =
                gameOverGsms[0]
                    .annotationsList
                    .firstOrNull { it.typeList.contains(AnnotationType.LossOfGame_af5a) }
            lossAnnotation.shouldNotBeNull()

            // prevGameStateId chain
            gameOverGsms[1].prevGameStateId shouldBe gameOverGsms[0].gameStateId
            gameOverGsms[2].prevGameStateId shouldBe gameOverGsms[1].gameStateId
        }
    })
