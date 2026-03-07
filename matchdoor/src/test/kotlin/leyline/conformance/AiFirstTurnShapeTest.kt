package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Wire conformance: AI-first turn message shape.
 *
 * Runs a game with [AI_FIRST_SEED] so Sparky goes first.
 * Captures messages through MatchSession/MatchFlowHarness (production code path)
 * and asserts properties matching the real server:
 *
 * 1. **No post-handshake Full** — gameStart uses thin Diffs (phaseTransitionDiff),
 *    not a Full state with all zones/objects.
 * 2. **Phase transition pattern** — first 3 GSMs match phaseTransitionDiff shape.
 * 3. **Human actions in AI-turn GSMs** — GSMs during AI turn (activePlayer=2)
 *    should embed the human's available actions, not the AI's.
 * 4. **PhaseOrStepModified** — every phase/step transition must have annotations.
 */
class AiFirstTurnShapeTest :
    FunSpec({

        tags(ConformanceTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun runAiFirstGame(): MatchFlowHarness {
            val h = MatchFlowHarness(seed = AI_FIRST_SEED)
            harness = h
            h.connectAndKeep()
            h.isGameOver().shouldBeFalse()
            return h
        }

        test("at most one post-handshake Full GSM with zones") {
            val h = runAiFirstGame()

            val fullWithZones = h.allMessages
                .filter { it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full }
                .map { it.gameStateMessage }
                .filter { it.zonesCount > 0 }

            (fullWithZones.size <= 1).shouldBeTrue()
        }

        test("gameStart has phaseTransitionDiff pattern") {
            val h = runAiFirstGame()

            val gsms = h.allMessages
                .filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }

            val ptStart = gsms.indexOfFirst { it.type == GameStateType.Diff && it.hasGameInfo() }
            ptStart shouldBeGreaterThanOrEqual 0
            (gsms.size >= ptStart + 3).shouldBeTrue()

            // GSM N+0: SendHiFi with 2+ PhaseOrStepModified + gameInfo
            val gsm0 = gsms[ptStart]
            gsm0.type shouldBe GameStateType.Diff
            gsm0.update shouldBe GameStateUpdate.SendHiFi
            gsm0.hasGameInfo().shouldBeTrue()
            val phaseAnns0 = gsm0.annotationsList.flatMap { it.typeList }
                .count { it == AnnotationType.PhaseOrStepModified }
            phaseAnns0 shouldBeGreaterThanOrEqual 2

            // GSM N+1: SendHiFi echo (turnInfo + actions)
            val gsm1 = gsms[ptStart + 1]
            gsm1.type shouldBe GameStateType.Diff
            gsm1.update shouldBe GameStateUpdate.SendHiFi
            gsm1.hasTurnInfo().shouldBeTrue()

            // GSM N+2: SendAndRecord with 1x PhaseOrStepModified
            val gsm2 = gsms[ptStart + 2]
            gsm2.type shouldBe GameStateType.Diff
            gsm2.update shouldBe GameStateUpdate.SendAndRecord
            val phaseAnns2 = gsm2.annotationsList.flatMap { it.typeList }
                .count { it == AnnotationType.PhaseOrStepModified }
            phaseAnns2 shouldBe 1
        }

        test("AI-turn GSMs embed human's actions, not AI's") {
            val h = runAiFirstGame()

            val aiTurnGsms = h.allMessages
                .filter {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.hasTurnInfo() &&
                        it.gameStateMessage.turnInfo.activePlayer == 2 &&
                        it.gameStateMessage.actionsCount > 0
                }
                .map { it.gameStateMessage }

            if (aiTurnGsms.isEmpty()) return@test

            val wrongSeatActions = aiTurnGsms.flatMap { gsm ->
                gsm.actionsList.filter { it.seatId == 2 }
            }

            if (wrongSeatActions.isNotEmpty()) {
                val report = buildString {
                    appendLine("Found ${wrongSeatActions.size} actions embedded for AI seat (2) during AI turn")
                    appendLine("Actions should be for human seat (1)")
                    aiTurnGsms.take(3).forEachIndexed { i, gsm ->
                        val seats = gsm.actionsList.map { "seat=${it.seatId} type=${it.action.actionType.name}" }
                        appendLine("  [$i] gsId=${gsm.gameStateId} phase=${gsm.turnInfo.phase} actions=$seats")
                    }
                }
                error("AI-turn GSMs should embed human's (seat 1) actions, not AI's (seat 2):\n$report")
            }
        }

        test("phase transitions have PhaseOrStepModified annotations") {
            val h = runAiFirstGame()

            val gsms = h.allMessages
                .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
                .map { it.gameStateMessage }

            val phaseChanges = mutableListOf<GameStateMessage>()
            for (i in 1 until gsms.size) {
                val prev = gsms[i - 1]
                val curr = gsms[i]
                if (curr.turnInfo.phase != prev.turnInfo.phase ||
                    curr.turnInfo.step != prev.turnInfo.step
                ) {
                    phaseChanges.add(curr)
                }
            }

            phaseChanges.isNotEmpty().shouldBeTrue()

            val missing = phaseChanges.filter { gsm ->
                gsm.annotationsList.none { ann ->
                    AnnotationType.PhaseOrStepModified in ann.typeList
                }
            }

            if (missing.isNotEmpty()) {
                val report = buildString {
                    appendLine("${missing.size}/${phaseChanges.size} phase transitions missing PhaseOrStepModified:")
                    missing.forEachIndexed { i, gsm ->
                        appendLine(
                            "  [$i] gsId=${gsm.gameStateId} " +
                                "phase=${gsm.turnInfo.phase}/${gsm.turnInfo.step} " +
                                "update=${gsm.update} " +
                                "annotations=${gsm.annotationsList.flatMap { it.typeList }.map { it.name }}",
                        )
                    }
                }
                error("Phase transitions must have PhaseOrStepModified annotations:\n$report")
            }
        }
    })
