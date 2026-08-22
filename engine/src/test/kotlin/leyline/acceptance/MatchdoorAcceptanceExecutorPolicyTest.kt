package leyline.acceptance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PendingActionKind

class MatchdoorAcceptanceExecutorPolicyTest :
    FunSpec({
        tags(UnitTag)

        test("an exact Visible post-action horizon proves empty-stack resolution completed") {
            stackResolutionNeedsAdvance(
                passCount = 0,
                stackEmpty = true,
                pendingKind = PendingActionKind.PRIORITY,
            ) shouldBe false
        }

        test("an entry-empty stack without a published horizon still advances once") {
            assertSoftly {
                stackResolutionNeedsAdvance(passCount = 0, stackEmpty = true, pendingKind = null) shouldBe true
                stackResolutionNeedsAdvance(
                    passCount = 0,
                    stackEmpty = true,
                    pendingKind = PendingActionKind.SYNC_ONLY,
                ) shouldBe true
                stackResolutionNeedsAdvance(passCount = 1, stackEmpty = true, pendingKind = null) shouldBe false
            }
        }

        test("stack resolution stops at a client combat decision") {
            assertSoftly {
                stackResolutionNeedsAdvance(0, stackEmpty = false, PendingActionKind.DECLARE_ATTACKERS) shouldBe false
                stackResolutionNeedsAdvance(0, stackEmpty = false, PendingActionKind.DECLARE_BLOCKERS) shouldBe false
            }
        }
    })
