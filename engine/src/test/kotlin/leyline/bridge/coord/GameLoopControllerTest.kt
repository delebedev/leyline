package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.BoardTest
import java.util.concurrent.TimeUnit

class GameLoopControllerTest :
    BoardTest({
        test("puzzle teardown does not record coordinator cancellation as a loop failure") {
            val board = startPuzzleAtMain1FromResource("test-puzzles/lands-only.pzl")
            val controller = checkNotNull(board.bridge.gameLoopControllerForTest())

            board.bridge.teardownResources()

            assertSoftly {
                controller.isRunning shouldBe false
                controller.failure.shouldBeNull()
            }
        }

        test("coordinator failure during play remains a loop failure") {
            val board = startPuzzleAtMain1FromResource("test-puzzles/lands-only.pzl")
            val controller = checkNotNull(board.bridge.gameLoopControllerForTest())
            val cause = IllegalStateException("projection delivery failed")

            shouldThrow<PlaybackTerminalFailure> {
                board.bridge.cutCoordinator.failDelivery(cause)
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (controller.isRunning && System.nanoTime() < deadline) Thread.onSpinWait()
            val failure = controller.failure.shouldBeInstanceOf<PlaybackTerminalFailure>()
            failure.cause shouldBe cause
        }
    })
