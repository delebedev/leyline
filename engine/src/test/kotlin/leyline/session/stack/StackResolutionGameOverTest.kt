package leyline.session.stack

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Regression: when stack resolution causes game over (e.g. bolt for lethal),
 * the client never received the game-over bundle because the stack-empty
 * handler returned early before checking isGameOver (#122).
 */
class StackResolutionGameOverTest :
    SessionTest({

        test("spell resolving for lethal sends MatchCompleted") {
            // Bolt-face puzzle: AI at 3 life, human has Lightning Bolt + Mountain
            startPuzzleFile("puzzles/bolt-face.pzl", validating = true)

            // Cast Lightning Bolt — triggers SelectTargetsReq
            castSpellByName("Lightning Bolt").shouldBeTrue()

            // Target opponent (seatId=2)
            selectTargets(listOf(OPPONENT_SEAT))

            // Pass priority to resolve — bolt deals 3 to AI at 3 life = lethal
            passPriority()

            isGameOver().shouldBeTrue()

            // Verify MatchCompleted was sent
            val matchCompleted =
                harness.allRawMessages.firstOrNull {
                    it.hasMatchGameRoomStateChangedEvent() &&
                        it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                        MatchGameRoomStateType.MatchCompleted
                }
            matchCompleted.shouldNotBeNull()

            // Verify IntermissionReq with game result
            val intermission = allMessages.firstOrNull { it.hasIntermissionReq() }
            intermission.shouldNotBeNull()
            intermission.intermissionReq.result.reason shouldBe ResultReason.Game_ae0a
        }
    })
