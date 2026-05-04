package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import leyline.IntegrationTag
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * leyline-jxa: LAND_PLAY must emit a standalone Diff GSM (no immediately
 * paired ActionsAvailableReq), so its wire shape is update=SendAndRecord
 * without a trailing action prompt. Priority is re-granted by the engine
 * in a subsequent bundle, not in the same post-land bundle.
 *
 * Regression: before the fix in ActionPerformer, the first post-land GSM
 * with update=SendAndRecord was immediately followed by an
 * ActionsAvailableReq, collapsing the two protocol lanes into one bundle.
 */
class LandPlayLaneShapeTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("post-LAND_PLAY SendAndRecord GSM is not immediately followed by ActionsAvailableReq") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()
            h.advanceToMain1()

            val snap = h.messageSnapshot()
            h.playLand().shouldBeTrue()
            val produced = h.messagesSince(snap)

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
