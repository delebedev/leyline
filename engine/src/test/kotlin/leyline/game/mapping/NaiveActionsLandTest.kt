package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.game.mapping.ActionMapper
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Regression test for #32: lands must NOT appear as playable (actions)
 * in naive mode (opponent's turn). They should go to inactiveActions.
 */
class NaiveActionsLandTest :
    BoardTest({

        test("buildNaiveActions puts lands in inactiveActions, not actions") {
            val (bridge, game) =
                startWithBoard { game, human, ai ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Plains", human, ZoneType.Hand)
                }

            val req = ActionMapper.buildNaiveActions(1, bridge)

            val activeLands =
                req.actionsList.filter {
                    it.actionType == ActionType.Play_add3
                }
            val inactiveLands =
                req.inactiveActionsList.filter {
                    it.actionType == ActionType.Play_add3
                }

            activeLands.shouldBeEmpty()
            inactiveLands.shouldNotBeEmpty()
        }
    })
