package leyline.session.flow

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Tests for game-end protocol: IntermissionReq + MatchCompleted room state.
 *
 * The Arena client requires [MatchGameRoomStateType.MatchCompleted] to trigger
 * the result screen. Without it, the client stays on the game board after the
 * server sends the game-over GRE sequence.
 */
class GameEndTest :
    SessionTest({

        session("concede produces MatchCompleted", validating = true) {
            // Concede triggers sendGameOver()
            val concede =
                after {
                    session.onConcede()
                    drainSink()
                }

            // Verify GRE messages: 3x GSM + IntermissionReq
            val msgs = concede.messages
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
            val rawMsgs = allRawMessages
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
                matchCompleted.hasMatchGameRoomStateChangedEvent().shouldBeTrue()
                finalResult.matchCompletedReason shouldBe MatchCompletedReasonType.Success_a26d
                finalResult.resultListCount shouldBeGreaterThan 0
                finalResult.getResultList(0).result shouldBe ResultType.WinLoss
            }

            assertSoftly {
                registry.getMatch("test-match").shouldBeNull()
                registry.getPeer("test-match", SeatId(1)).shouldBeNull()
            }
        }

        session(
            "lethal damage produces MatchCompleted room state",
            // Puzzle: 3 haste creatures vs AI at 3 life — attack all = lethal
            puzzle =
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
                """.trimIndent(),
            validating = true,
        ) {
            // Advance to combat
            val startTurn = turn()
            passPriority()

            // Attack all
            declareAllAttackers()
            submitAttackers()

            // Pass through remaining combat phases
            passThroughCombat(startTurn)

            isGameOver().shouldBeTrue()

            // Verify MatchCompleted was sent
            val rawMsgs = allRawMessages
            val matchCompleted =
                rawMsgs.firstOrNull {
                    it.hasMatchGameRoomStateChangedEvent() &&
                        it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                        MatchGameRoomStateType.MatchCompleted
                }
            matchCompleted.shouldNotBeNull()

            // Verify IntermissionReq with correct fields
            val intermission =
                checkNotNull(allMessages.firstOrNull { it.hasIntermissionReq() }) {
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
            val intermissionIdx = allMessages.indexOfFirst { it.hasIntermissionReq() }
            intermissionIdx shouldBeGreaterThanOrEqualTo 3
            val gameOverGsms =
                allMessages
                    .subList(intermissionIdx - 3, intermissionIdx)
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
            gameOverGsms.size shouldBe 3

            // First game-over GSM should have LossOfGame annotation
            val lossAnnotation =
                gameOverGsms[0]
                    .annotationsList
                    .firstOrNull { it.typeList.contains(AnnotationType.LossOfGame_af5a) }
            assertSoftly {
                lossAnnotation.shouldNotBeNull()
                gameOverGsms[1].prevGameStateId shouldBe gameOverGsms[0].gameStateId
                gameOverGsms[2].prevGameStateId shouldBe gameOverGsms[1].gameStateId
            }
        }

        session(
            "spell resolving for lethal produces MatchCompleted room state",
            puzzleFile = "puzzles/bolt-face.pzl",
            validating = true,
        ) {
            // Combat damage and stack resolution reach game-over through
            // different handlers; the stack-empty path must check isGameOver
            // rather than returning as soon as the stack drains.
            castSpellByName("Lightning Bolt").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passPriority()

            isGameOver().shouldBeTrue()

            val matchCompleted =
                allRawMessages.firstOrNull {
                    it.hasMatchGameRoomStateChangedEvent() &&
                        it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                        MatchGameRoomStateType.MatchCompleted
                }
            matchCompleted.shouldNotBeNull()

            val intermission = allMessages.firstOrNull { it.hasIntermissionReq() }
            intermission.shouldNotBeNull()
            intermission.intermissionReq.result.reason shouldBe ResultReason.Game_ae0a
        }

        session(
            "lethal poison produces poison loss annotation",
            puzzle =
                """
                [metadata]
                Name:Poison Lethal Swing
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Attack with ten toxic creatures to win by poison before life total reaches zero.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus;Crawling Chorus
                humanlibrary=Forest
                ailibrary=Forest
                """.trimIndent(),
            validating = true,
        ) {
            val startTurn = turn()
            passPriority()
            declareAllAttackers()
            submitAttackers()
            passThroughCombat(startTurn)

            assertSoftly {
                isGameOver().shouldBeTrue()
                ai.life shouldBe 10
                ai.poisonCounters shouldBe 10
            }

            val counterAdded = allMessages.annotationsOfType(AnnotationType.CounterAdded).single()
            assertSoftly {
                counterAdded.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                counterAdded.detailInt("counter_type") shouldBe 3
                counterAdded.detailInt("transaction_amount") shouldBe 10
            }

            val counterState = allMessages.persistentAnnotationsOfType(AnnotationType.Counter_803b).single()
            assertSoftly {
                counterState.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                counterState.detailInt("count") shouldBe 10
                counterState.detailInt("counter_type") shouldBe 3
            }

            val lossAnnotation = allMessages.annotationsOfType(AnnotationType.LossOfGame_af5a).single()
            assertSoftly {
                lossAnnotation.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                lossAnnotation.detailString("reason") shouldBe "SBA_Poison"
            }

            val intermission = allMessages.first { it.hasIntermissionReq() }.intermissionReq
            intermission.result.reason shouldBe ResultReason.Game_ae0a
        }
    })
