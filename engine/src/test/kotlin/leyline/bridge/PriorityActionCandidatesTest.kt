package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import leyline.bridge.types.SeatId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class PriorityActionCandidatesTest :
    BoardTest({
        test("legal but unaffordable cast remains phase-blocking and inactive") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val actions =
                ActionMapper.buildFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeTrue()
                candidates.facts(board.human).hasLegalNonManaAction.shouldBeTrue()
                board.bridge
                    .priorityActionFacts(SeatId(1))
                    .hasLegalNonManaAction
                    .shouldBeTrue()
                actions.ofType(ActionType.Cast).shouldHaveSize(0)
                actions.inactiveActionsList
                    .filter { it.actionType == ActionType.Cast }
                    .shouldHaveSize(1)
            }
        }
    })
