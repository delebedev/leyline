package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Regression: when stack resolution causes game over (e.g. bolt for lethal),
 * the client never received the game-over bundle because the stack-empty
 * handler returned early before checking isGameOver (#122).
 */
class StackResolutionGameOverTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("spell resolving for lethal sends MatchCompleted") {
            // Bolt-face puzzle: AI at 3 life, human has Lightning Bolt + Mountain
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/bolt-face.pzl")

            // Cast Lightning Bolt — triggers SelectTargetsReq
            h.castSpellByName("Lightning Bolt").shouldBeTrue()

            // Target opponent (seatId=2)
            h.selectTargets(listOf(2))

            // Pass priority to resolve — bolt deals 3 to AI at 3 life = lethal
            h.passPriority()

            h.isGameOver().shouldBeTrue()

            // Verify MatchCompleted was sent
            val matchCompleted = h.allRawMessages.firstOrNull {
                it.hasMatchGameRoomStateChangedEvent() &&
                    it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                    MatchGameRoomStateType.MatchCompleted
            }
            matchCompleted.shouldNotBeNull()

            // Verify IntermissionReq with game result
            val intermission = h.allMessages.firstOrNull { it.hasIntermissionReq() }
            intermission.shouldNotBeNull()
            intermission.intermissionReq.result.reason shouldBe ResultReason.Game_ae0a
        }
    })
