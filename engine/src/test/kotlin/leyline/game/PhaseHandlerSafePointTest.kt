package leyline.game

import forge.game.GameStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import java.util.concurrent.atomic.AtomicInteger

class PhaseHandlerSafePointTest :
    BoardTest({
        test("completed early-return step invokes hook exactly once") {
            val (_, game, _) = startWithBoard { _, _, _ -> }
            val calls = AtomicInteger()
            game.phaseHandler.setMainLoopStepCompletionHook { calls.incrementAndGet() }
            game.age = GameStage.GameOver

            game.phaseHandler.mainLoopStep()

            calls.get() shouldBe 1
        }

        test("completion hook exception propagates") {
            val (_, game, _) = startWithBoard { _, _, _ -> }
            game.age = GameStage.GameOver
            val failure = IllegalStateException("safe-point failure")
            game.phaseHandler.setMainLoopStepCompletionHook { throw failure }

            shouldThrow<IllegalStateException> { game.phaseHandler.mainLoopStep() } shouldBe failure
        }

        test("main-loop start hook runs before a zero-step loop") {
            val (_, game, _) = startWithBoard { _, _, _ -> }
            val calls = AtomicInteger()
            game.phaseHandler.setMainGameLoopStartedHook { calls.incrementAndGet() }
            game.age = GameStage.GameOver

            game.phaseHandler.mainGameLoop()

            calls.get() shouldBe 1
        }
    })
