package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.should
import leyline.bridge.types.ForgeCardId
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Regression: naive-mode Cast actions (the ones embedded in GSM frames during
 * the opponent's turn) must display the spell's effective cost after a static
 * cost reduction, not the printed cost.
 *
 * The naive action path historically routed every hand cast through a fallback
 * that serialized the printed `CardData.manaCost`, so any static reduction
 * (`Eddymurk Crab`/`Tolarian Terror` graveyard reducers, Affinity, etc.) stayed
 * invisible in the embedded action until it happened to zero out the generic
 * portion.
 */
class NaiveActionsCostReductionTest :
    BoardTest({

        fun naiveCastAction(
            bridge: GameBridge,
            instanceId: Int,
        ): Action {
            val req = ActionMapper.buildNaiveActions(1, bridge)
            return (req.actionsList + req.inactiveActionsList)
                .first { it.actionType == ActionType.Cast && it.instanceId == instanceId }
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
