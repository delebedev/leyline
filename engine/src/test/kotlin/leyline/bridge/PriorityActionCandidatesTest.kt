package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
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
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeTrue()
                projection.actions.ofType(ActionType.Cast).shouldHaveSize(0)
                projection.actions.inactiveActionsList
                    .filter { it.actionType == ActionType.Cast }
                    .shouldHaveSize(1)
            }
        }
        for (manaAvailable in listOf(false, true)) {
            test("opponent cycling stop requires payable mana: $manaAvailable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Shark Typhoon", human, ZoneType.Hand)
                        if (manaAvailable) repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    if (manaAvailable) {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeTrue()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeTrue()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(1)
                    } else {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeFalse()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeFalse()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(0)
                    }
                }
            }
        }
    })
