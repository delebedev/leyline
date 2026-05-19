package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class DelayedTriggerHolderTrackerTest :
    FunSpec({

        tags(UnitTag)

        fun rec(iid: Int) =
            HolderRecord(
                iid = iid,
                ownerSeat = 1,
                objectSourceGrpId = 188698,
                parentIid = 100,
                cleanupGrpId = 189931,
            )

        test("first batch — every holder is added, nothing removed") {
            val t = DelayedTriggerHolderTracker()
            val batch = t.computeBatch(listOf(rec(119), rec(123)))
            batch.added.map { it.iid } shouldContainExactlyInAnyOrder listOf(119, 123)
            batch.removed.shouldBeEmpty()
            t.apply(batch)
            t.activeSize shouldBe 2
        }

        test("unchanged holders produce empty batch — no re-emit, no deletion") {
            val t = DelayedTriggerHolderTracker()
            t.apply(t.computeBatch(listOf(rec(119))))

            val batch = t.computeBatch(listOf(rec(119)))
            batch.added.shouldBeEmpty()
            batch.removed.shouldBeEmpty()
            t.apply(batch)
        }

        test("removed holder is returned in the batch and apply updates active state") {
            val t = DelayedTriggerHolderTracker()
            t.apply(t.computeBatch(listOf(rec(119))))

            val batch = t.computeBatch(emptyList())
            batch.added.shouldBeEmpty()
            batch.removed shouldContainExactly listOf(119)

            t.apply(batch)
            t.activeSize shouldBe 0
        }

        test("swap one holder for another — same call returns added + removed") {
            val t = DelayedTriggerHolderTracker()
            t.apply(t.computeBatch(listOf(rec(119))))

            val batch = t.computeBatch(listOf(rec(123)))
            batch.added.map { it.iid } shouldContainExactly listOf(123)
            batch.removed shouldContainExactly listOf(119)

            t.apply(batch)
            t.activeSize shouldBe 1
        }

        test("multi-GSM lifecycle — add, hold, hold, remove") {
            val t = DelayedTriggerHolderTracker()
            // GSM 1 — Mobilize fires, holder created.
            val gsm1 = t.computeBatch(listOf(rec(119)))
            gsm1.added.map { it.iid } shouldContainExactly listOf(119)
            t.apply(gsm1)

            // GSM 2 — combat damage, holder still alive.
            val gsm2 = t.computeBatch(listOf(rec(119)))
            gsm2.added.shouldBeEmpty()
            gsm2.removed.shouldBeEmpty()
            t.apply(gsm2)

            // GSM 3 — main2, holder still alive (tokens haven't been sacrificed).
            val gsm3 = t.computeBatch(listOf(rec(119)))
            gsm3.added.shouldBeEmpty()
            gsm3.removed.shouldBeEmpty()
            t.apply(gsm3)

            // GSM 4 — end-step cleanup fires, tokens sacrificed, holder retired.
            val gsm4 = t.computeBatch(emptyList())
            gsm4.removed shouldContainExactly listOf(119)
            t.apply(gsm4)
            t.activeSize shouldBe 0

            // GSM 5 — post-cleanup, nothing happens.
            val gsm5 = t.computeBatch(emptyList())
            gsm5.added.shouldBeEmpty()
            gsm5.removed.shouldBeEmpty()
            t.apply(gsm5)
        }

        test("computeBatch is non-mutating — repeated calls before apply give same result") {
            val t = DelayedTriggerHolderTracker()
            t.apply(t.computeBatch(listOf(rec(119))))

            val first = t.computeBatch(listOf(rec(123)))
            val second = t.computeBatch(listOf(rec(123)))
            assertSoftly("computeBatch idempotency") {
                first.added.map { it.iid } shouldContainExactly second.added.map { it.iid }
                first.removed shouldContainExactly second.removed
                // State unchanged — 119 still active.
                t.activeSize shouldBe 1
            }
        }
    })
