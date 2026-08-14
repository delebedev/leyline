package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class PriorityLoopCoordinatorPolicyTest :
    FunSpec({
        tags(UnitTag)

        test("pass-only priority is skipped unless the client requested the window") {
            assertSoftly {
                mode(fullControl = false, opponentStop = false, meaningful = false) shouldBe PriorityWindowMode.Skip
                mode(fullControl = true, opponentStop = false, meaningful = false) shouldBe PriorityWindowMode.Visible
                mode(fullControl = false, opponentStop = true, meaningful = false) shouldBe PriorityWindowMode.Visible
            }
        }

        test("meaningful priority and state-bearing stops are published") {
            assertSoftly {
                mode(fullControl = false, opponentStop = false, meaningful = true) shouldBe PriorityWindowMode.Visible
                mode(fullControl = false, opponentStop = false, meaningful = false, stackEmpty = false) shouldBe
                    PriorityWindowMode.SyncOnly
                mode(fullControl = false, opponentStop = false, meaningful = false, promptJustResolved = true) shouldBe
                    PriorityWindowMode.SyncOnly
                PriorityLoopCoordinator.priorityWindowMode(
                    fullControl = false,
                    smartPhaseSkip = false,
                    promptJustResolved = false,
                    stackEmpty = true,
                    opponentStop = false,
                    hasMeaningfulAction = false,
                ) shouldBe PriorityWindowMode.SyncOnly
            }
        }
    }) {
    companion object {
        private fun mode(
            fullControl: Boolean,
            opponentStop: Boolean,
            meaningful: Boolean,
            stackEmpty: Boolean = true,
            promptJustResolved: Boolean = false,
        ): PriorityWindowMode =
            PriorityLoopCoordinator.priorityWindowMode(
                fullControl = fullControl,
                smartPhaseSkip = true,
                promptJustResolved = promptJustResolved,
                stackEmpty = stackEmpty,
                opponentStop = opponentStop,
                hasMeaningfulAction = meaningful,
            )
    }
}
