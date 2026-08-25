package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.SynchronizationContinuation

class PriorityLoopCoordinatorPolicyTest :
    FunSpec({
        tags(UnitTag)

        test("synchronization continuation is frozen from engine policy") {
            assertSoftly {
                PriorityLoopCoordinator.synchronizationContinuation(
                    PriorityWindowMode.Visible,
                    stackEmpty = false,
                    autoResolve = false,
                ) shouldBe
                    SynchronizationContinuation.Reevaluate
                PriorityLoopCoordinator.synchronizationContinuation(
                    PriorityWindowMode.SyncOnly,
                    stackEmpty = true,
                    autoResolve = false,
                ) shouldBe
                    SynchronizationContinuation.Reevaluate
                PriorityLoopCoordinator.synchronizationContinuation(
                    PriorityWindowMode.SyncOnly,
                    stackEmpty = false,
                    autoResolve = false,
                ) shouldBe
                    SynchronizationContinuation.RequireVisible
                PriorityLoopCoordinator.synchronizationContinuation(
                    PriorityWindowMode.SyncOnly,
                    stackEmpty = false,
                    autoResolve = true,
                ) shouldBe
                    SynchronizationContinuation.AllowSyncOnly
            }
        }
    })
