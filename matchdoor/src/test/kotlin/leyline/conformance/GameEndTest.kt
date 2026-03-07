package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.mapper.PromptIds
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

            (gsmCount >= 3).shouldBeTrue()
            intermission.shouldNotBeNull()

            // First GSM should have GameInfo with stage=GameOver
            val firstGsm = msgs.first { it.hasGameStateMessage() }.gameStateMessage
            firstGsm.hasGameInfo().shouldBeTrue()
            firstGsm.gameInfo.stage shouldBe GameStage.GameOver
            firstGsm.gameInfo.matchState shouldBe MatchState.GameComplete

            // IntermissionReq should have result with winning team + reason
            val req = intermission.intermissionReq
            req.hasResult().shouldBeTrue()
            req.result.result shouldBe ResultType.WinLoss
            (req.result.winningTeamId > 0).shouldBeTrue()
            req.result.reason shouldBe ResultReason.Concede

            // IntermissionReq should have options + intermissionPrompt
            (req.optionsCount > 0).shouldBeTrue()
            (req.optionsCount >= 2).shouldBeTrue()
            req.hasIntermissionPrompt().shouldBeTrue()
            req.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
            (req.intermissionPrompt.parametersCount > 0).shouldBeTrue()

            // prevGameStateId chain: gs1.prev = last-known, gs2.prev = gs1, gs3.prev = gs2
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            (gsms.size >= 3).shouldBeTrue()
            gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
            gsms[2].prevGameStateId shouldBe gsms[1].gameStateId

            // MatchCompleted room state should be in allRawMessages
            val rawMsgs = h.allRawMessages
            val matchCompleted = rawMsgs.firstOrNull {
                it.hasMatchGameRoomStateChangedEvent() &&
                    it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                    MatchGameRoomStateType.MatchCompleted
            }
            matchCompleted.shouldNotBeNull()

            // Verify FinalMatchResult
            val finalResult = matchCompleted.matchGameRoomStateChangedEvent.gameRoomInfo.finalMatchResult
            finalResult.matchCompletedReason shouldBe MatchCompletedReasonType.Success_a26d
            (finalResult.resultListCount > 0).shouldBeTrue()
            finalResult.getResultList(0).result shouldBe ResultType.WinLoss
        }

        // DISABLED: multi-turn loop to reach lethal is slow and flaky — times out
        // at 120s. Needs puzzle-based rewrite.
        xtest("lethal damage produces MatchCompleted room state") {
            val h = MatchFlowHarness(
                seed = 42L,
                deckList = COMBAT_DECK,
                validating = false,
            )
            harness = h
            h.connectAndKeep()

            // AI: passive — play lands, never attack, always pass
            h.installScriptedAi(
                List(60) { i ->
                    when (i % 3) {
                        0 -> ScriptedAction.PlayLand("Mountain")
                        1 -> ScriptedAction.DeclareNoAttackers
                        else -> ScriptedAction.PassPriority
                    }
                },
            )

            // Turn 1: play Mountain + cast Raging Goblin
            h.playLand().shouldBeTrue()
            h.castSpellByName("Raging Goblin").shouldBeTrue()
            h.passPriority() // resolve

            // Game loop: each human turn, play a land + cast another Raging Goblin + attack all
            var lastDaReqSnap = 0
            repeat(500) {
                if (h.isGameOver()) return@repeat

                h.passPriority()
                if (h.isGameOver()) return@repeat

                // On human turns, try to play land + cast another goblin
                if (!h.isAiTurn() && !h.isGameOver()) {
                    h.playLand()
                    if (h.castSpellByName("Raging Goblin")) {
                        h.passPriority() // resolve
                    }
                }

                if (h.isGameOver()) return@repeat

                // Check for new DeclareAttackersReq
                val newMsgs = h.messagesSince(lastDaReqSnap)
                val daReq = newMsgs.lastOrNull { it.hasDeclareAttackersReq() }
                if (daReq != null) {
                    lastDaReqSnap = h.messageSnapshot()
                    val eligible = daReq.declareAttackersReq.attackersList.map { it.attackerInstanceId }
                    if (eligible.isNotEmpty()) {
                        h.declareAttackers(eligible)
                    }
                }
            }

            h.isGameOver().shouldBeTrue()

            // Verify MatchCompleted was sent
            val rawMsgs = h.allRawMessages
            val matchCompleted = rawMsgs.firstOrNull {
                it.hasMatchGameRoomStateChangedEvent() &&
                    it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                    MatchGameRoomStateType.MatchCompleted
            }
            matchCompleted.shouldNotBeNull()

            // Verify IntermissionReq was sent with correct fields
            val intermission = checkNotNull(h.allMessages.firstOrNull { it.hasIntermissionReq() }) {
                "Should have IntermissionReq after lethal damage"
            }
            val req = intermission.intermissionReq
            req.result.reason shouldBe ResultReason.Game_ae0a

            // IntermissionReq should have options + intermissionPrompt
            (req.optionsCount >= 2).shouldBeTrue()
            req.hasIntermissionPrompt().shouldBeTrue()
            req.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
            (req.intermissionPrompt.parametersCount > 0).shouldBeTrue()

            // Game-over GSMs: the 3 GSMs immediately before IntermissionReq
            val allMsgs = h.allMessages
            val intermissionIdx = allMsgs.indexOfFirst { it.hasIntermissionReq() }
            (intermissionIdx >= 3).shouldBeTrue()
            val gameOverGsms = allMsgs.subList(intermissionIdx - 3, intermissionIdx)
                .filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }
            gameOverGsms.size shouldBe 3

            // First game-over GSM should have LossOfGame annotation
            val lossAnnotation = gameOverGsms[0].annotationsList
                .firstOrNull { it.typeList.contains(AnnotationType.LossOfGame_af5a) }
            lossAnnotation.shouldNotBeNull()

            // prevGameStateId chain
            gameOverGsms[1].prevGameStateId shouldBe gameOverGsms[0].gameStateId
            gameOverGsms[2].prevGameStateId shouldBe gameOverGsms[1].gameStateId
        }
    })
