package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * `ActionMapper.buildNaiveActions` — the action list embedded in GSM frames
 * during the opponent's turn, built without a legality pass.
 *
 * Two things the naive path gets wrong if left unguarded: it can offer actions
 * the turn structure forbids, and it can serialize a card's printed cost rather
 * than its effective one, because the fallback it used to route hand casts
 * through read `CardData.manaCost` directly.
 */
class NaiveActionsTest :
    BoardTest({

        fun naiveCastAction(
            bridge: GameBridge,
            instanceId: Int,
        ): Action {
            val req = ActionMapper.buildNaiveActions(1, bridge)
            return (req.actionsList + req.inactiveActionsList)
                .first { it.actionType == ActionType.Cast && it.instanceId == instanceId }
        }

        test("lands are offered as inactive, never as playable actions") {
            val (bridge, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Plains", human, ZoneType.Hand)
                }

            val req = ActionMapper.buildNaiveActions(1, bridge)

            assertSoftly {
                req.actionsList.filter { it.actionType == ActionType.Play_add3 }.shouldBeEmpty()
                req.inactiveActionsList.filter { it.actionType == ActionType.Play_add3 }.shouldNotBeEmpty()
            }
        }

        test("naive cast and embedded GSM actions reflect graveyard cost reduction") {
            var crabId = 0
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    val crab = addCard("Eddymurk Crab", human, ZoneType.Hand)
                    crabId = crab.id
                    repeat(5) { addCard("Lightning Bolt", human, ZoneType.Graveyard) }
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                }

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(crabId)).value
            val legalActions = bundleBuilder(bridge).buildActions()
            val legalAction =
                (legalActions.actionsList + legalActions.inactiveActionsList)
                    .first { it.actionType == ActionType.Cast && it.instanceId == instanceId }
            val naiveAction = naiveCastAction(bridge, instanceId)
            val embeddedGsmAction =
                bundleBuilder(bridge)
                    .remoteActionDiff(game, counter)
                    .messages
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage
                    .actionsList
                    .first { it.action.actionType == ActionType.Cast && it.action.instanceId == instanceId }
                    .action

            assertSoftly {
                // Printed {5}{U}{U}; five graveyard instants reduce it to {U}{U}.
                legalAction should haveManaCost(blue = 2)
                naiveAction should haveManaCost(blue = 2)
                embeddedGsmAction should haveManaCost(blue = 2)
            }
        }

        test("naive cast shows printed cost with no graveyard reducer") {
            var crabId = 0
            val (bridge, _, _) =
                startWithBoard { _, human, _ ->
                    val crab = addCard("Eddymurk Crab", human, ZoneType.Hand)
                    crabId = crab.id
                }

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(crabId)).value

            naiveCastAction(bridge, instanceId) should haveManaCost(generic = 5, blue = 2)
        }
    })
