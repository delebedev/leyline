package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import leyline.BoardTag
import leyline.game.mapping.ActionMapper
import leyline.testkit.BoardTestBase
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Regression test for #32: lands must NOT appear as playable (actions)
 * in naive mode (opponent's turn). They should go to inactiveActions.
 */
class NaiveActionsLandTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("buildNaiveActions puts lands in inactiveActions, not actions") {
            val (bridge, game) =
                base.startWithBoard { game, human, ai ->
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Plains", human, ZoneType.Hand)
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
