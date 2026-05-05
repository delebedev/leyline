package leyline.session.actions

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import leyline.session.combat.COMBAT_DECK
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * leyline-jxa: LAND_PLAY must emit a standalone Diff GSM (no immediately
 * paired ActionsAvailableReq). The post-land GSM lands with
 * update=SendAndRecord and no trailing action prompt; priority is re-granted
 * by the engine in a subsequent bundle, not in the same post-land bundle.
 *
 * Regression: before the fix in ActionPerformer, the first post-land GSM
 * with update=SendAndRecord was immediately followed by an
 * ActionsAvailableReq, collapsing the two protocol lanes into one bundle.
 */
class LandPlayLaneShapeTest :
    SessionTest({

        test("post-LAND_PLAY SendAndRecord GSM is not immediately followed by ActionsAvailableReq") {
            startGame(deckList = COMBAT_DECK, validating = false)
            harness.advanceToMain1()

            val produced = after { playLand().shouldBeTrue() }.messages

            // Find the first post-land SendAndRecord GSM.
            val firstSarIndex =
                produced.indexOfFirst {
                    it.hasGameStateMessage() &&
                        it.gameStateMessage.update == GameStateUpdate.SendAndRecord
                }
            firstSarIndex shouldBeGreaterThan -1

            // Lane assertion: the immediate successor must NOT be an
            // ActionsAvailableReq. Either there is no successor yet, or it
            // is another GSM / non-AAR message.
            val successor = produced.getOrNull(firstSarIndex + 1)
            val immediateIsAar = successor?.hasActionsAvailableReq() == true
            immediateIsAar.shouldBeFalse()

            // Priority must still be granted later in the stream — the
            // client can't play more lands / cast spells without an AAR.
            val hasLaterAar =
                produced.drop(firstSarIndex + 1).any { it.hasActionsAvailableReq() }
            hasLaterAar.shouldBeTrue()
        }
    })
