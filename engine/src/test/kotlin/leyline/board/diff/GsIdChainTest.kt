package leyline.board.diff

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.ValidatingMessageSink
import leyline.testkit.gsm
import leyline.testkit.humanPlayer

/**
 * Validates gsId chain **semantics** beyond structural invariants.
 *
 * Hard checks (gsId monotonicity, uniqueness, and no self-reference) are
 * automatic via [ValidatingMessageSink]. Prev-known and msgId checks remain
 * opt-in diagnostics for focused shape tests.
 *
 * What remains here: scenario-specific contracts about pendingMessageCount,
 * phase transition bundle structure, and cross-bundle chain continuity.
 *
 * Uses [startWithBoard] (~0.01s) — all checks are on synchronous diff builders.
 */
class GsIdChainTest :
    BoardTest({

        test("remoteActionDiff produces content GSM plus echo with chained gsIds and no pendingMessageCount") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }

            val result = bundleBuilder(board.bridge).remoteActionDiff(board.game, board.counter)
            result.messages.size shouldBe 2
            val content = result.messages[0].gameStateMessage
            val echo = result.messages[1].gameStateMessage
            assertSoftly {
                content.pendingMessageCount shouldBe 0
                echo.pendingMessageCount shouldBe 0
                echo.prevGameStateId shouldBe content.gameStateId
                echo.gameStateId shouldBe content.gameStateId + 1
            }
        }

        test("postAction GSM has pendingMessageCount=1 (AAR follows)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }

            val land =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.isLand }
            board.snapshotDiff {
                board.game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val result = board.postAction()
            val gsm = result.gsm
            gsm.pendingMessageCount shouldBe 1
        }

        test("phaseTransitionDiff produces 5 messages with correct echo chain") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }

            val result = bundleBuilder(board.bridge).phaseTransitionDiff(board.game, board.counter)
            result.messages.size shouldBe 5

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            assertSoftly {
                gsms.size shouldBeGreaterThanOrEqual 3
                // msg2 (echo) chains from msg1, no pendingMessageCount
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                gsms[1].pendingMessageCount shouldBe 0

                // msg3 (commit) chains from echo
                gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
            }
        }
    })
