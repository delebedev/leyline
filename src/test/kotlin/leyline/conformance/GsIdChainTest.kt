package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.BundleBuilder
import leyline.game.snapshotFromGame

/**
 * Validates gsId chain **semantics** that go beyond structural invariants.
 *
 * Structural checks (gsId monotonicity, prevGsId validity, msgId monotonicity,
 * no self-referential gsIds) are now automatic via [ValidatingMessageSink] and
 * run on every message in every conformance test.
 *
 * What remains here: scenario-specific contracts about pendingMessageCount values,
 * phase transition bundle structure, and cross-bundle chain continuity.
 */
class GsIdChainTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("aiActionDiff produces single GSM with no pendingMessageCount") {
            val (b, game, counter) = base.startGameAtMain1()
            b.snapshotFromGame(game, counter.currentGsId())

            val result = BundleBuilder.aiActionDiff(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            result.messages.size shouldBe 1

            val gsm = result.messages[0].gameStateMessage
            gsm.pendingMessageCount shouldBe 0
        }

        test("postAction GSM has pendingMessageCount=1 (AAR follows)") {
            val (b, game, counter) = base.startGameAtMain1()

            base.playLand(b) ?: error("playLand failed at seed 42")
            val result = base.postAction(game, b, counter)
            val gsm = result.gsmOrNull ?: error("No GSM in bundle result")

            gsm.pendingMessageCount shouldBe 1
        }

        test("phaseTransitionDiff produces 5 messages with correct echo chain") {
            val (b, game, counter) = base.startGameAtMain1()

            val result = BundleBuilder.phaseTransitionDiff(game, b, ConformanceTestBase.TEST_MATCH_ID, ConformanceTestBase.SEAT_ID, counter)
            result.messages.size shouldBe 5

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            gsms.size shouldBeGreaterThanOrEqual 3

            // msg2 (echo) should chain from msg1 and have no pendingMessageCount
            val msg1 = gsms[0]
            val echo = gsms[1]

            echo.prevGameStateId shouldBe msg1.gameStateId
            echo.pendingMessageCount shouldBe 0

            // msg3 (commit) should chain from echo
            val commit = gsms[2]
            commit.prevGameStateId shouldBe echo.gameStateId
        }
    })
