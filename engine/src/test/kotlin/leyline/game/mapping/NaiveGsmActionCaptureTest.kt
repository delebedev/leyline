package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest

class NaiveGsmActionCaptureTest :
    BoardTest({

        test("value materialization preserves embedded action bytes") {
            val (bridge, _, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Eddymurk Crab", human, ZoneType.Hand)
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                    addCard("Esika, God of the Tree", human, ZoneType.Hand)
                    repeat(3) { addCard("Lightning Bolt", human, ZoneType.Graveyard) }
                }

            val legacy =
                ActionMapper
                    .buildNaiveActions(1, bridge)
                    .actionsList
                    .map(ActionMapper::stripActionForGsm)
            val materialized =
                NaiveGsmActionCapture
                    .materialize(1, bridge)
                    .map { ActionMapper.buildNaiveGsmAction(it, bridge::getOrAllocInstanceId) }

            materialized.map { it.toByteString() } shouldBe legacy.map { it.toByteString() }
        }
    })
