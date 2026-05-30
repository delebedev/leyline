package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.session.combat.COMBAT_DECK
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Land play keeps the acting player at priority. The state delta and the next
 * priority prompt should stay in one bundle so the client sees an
 * ACTIONS_AVAILABLE lane for the actor-perspective LAND_PLAY frame.
 */
class LandPlayLaneShapeTest :
    SessionTest({

        test("post-LAND_PLAY SendAndRecord GSM is immediately followed by ActionsAvailableReq") {
            startGame(deckList = COMBAT_DECK, validating = true)
            harness.advanceToMain1()

            val produced = after { playLand().shouldBeTrue() }.messages

            // Find the first post-land SendAndRecord GSM.
            val firstSarIndex =
                produced.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.update == GameStateUpdate.SendAndRecord
                }
            firstSarIndex shouldBeGreaterThan -1
            val landGsm = produced[firstSarIndex].gameStateMessage

            // Lane assertion: ACTIONS_AVAILABLE is represented by the AAR
            // immediately following the land-play GSM.
            val successor = produced.getOrNull(firstSarIndex + 1)
            val immediateIsAar = successor?.hasActionsAvailableReq() == true
            assertSoftly {
                immediateIsAar.shouldBeTrue()
                successor?.gameStateId shouldBe landGsm.gameStateId
                landGsm.pendingMessageCount shouldBe 1
            }
        }
    })
