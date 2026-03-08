package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag

class EffectTrackerTest :
    FunSpec({

        tags(UnitTag)

        test("allocates synthetic IDs starting at 7002") {
            val tracker = EffectTracker()
            tracker.nextEffectId() shouldBe 7002
            tracker.nextEffectId() shouldBe 7003
            tracker.nextEffectId() shouldBe 7004
        }

        test("reset clears ID counter back to 7002") {
            val tracker = EffectTracker()
            tracker.nextEffectId() // 7002
            tracker.nextEffectId() // 7003
            tracker.resetAll()
            tracker.nextEffectId() shouldBe 7002
        }

        test("diffBoosts detects new effect and returns it as created") {
            val tracker = EffectTracker()
            val boosts = mapOf(
                100 to listOf(EffectTracker.BoostEntry(timestamp = 1L, staticId = 0L, power = 3, toughness = 3)),
            )
            val result = tracker.diffBoosts(boosts)
            result.created.size shouldBe 1
            result.created[0].cardInstanceId shouldBe 100
            result.created[0].powerDelta shouldBe 3
            result.created[0].toughnessDelta shouldBe 3
            result.destroyed.shouldBeEmpty()
        }

        test("diffBoosts detects removed effect and returns it as destroyed") {
            val tracker = EffectTracker()
            val boosts1 = mapOf(
                100 to listOf(EffectTracker.BoostEntry(timestamp = 1L, staticId = 0L, power = 3, toughness = 3)),
            )
            tracker.diffBoosts(boosts1)
            val result = tracker.diffBoosts(emptyMap())
            result.created.shouldBeEmpty()
            result.destroyed.size shouldBe 1
            result.destroyed[0].cardInstanceId shouldBe 100
        }

        test("diffBoosts stable effect across two diffs produces no events") {
            val tracker = EffectTracker()
            val boosts = mapOf(
                100 to listOf(EffectTracker.BoostEntry(timestamp = 1L, staticId = 0L, power = 1, toughness = 1)),
            )
            tracker.diffBoosts(boosts)
            val result = tracker.diffBoosts(boosts)
            result.created.shouldBeEmpty()
            result.destroyed.shouldBeEmpty()
        }

        test("diffBoosts handles multiple effects on same card") {
            val tracker = EffectTracker()
            val boosts = mapOf(
                100 to listOf(
                    EffectTracker.BoostEntry(timestamp = 1L, staticId = 0L, power = 3, toughness = 3),
                    EffectTracker.BoostEntry(timestamp = 2L, staticId = 5L, power = 1, toughness = 1),
                ),
            )
            val result = tracker.diffBoosts(boosts)
            result.created.size shouldBe 2
            result.created[0].syntheticId shouldNotBe result.created[1].syntheticId
        }

        test("resetAll clears active effects") {
            val tracker = EffectTracker()
            val boosts = mapOf(
                100 to listOf(EffectTracker.BoostEntry(timestamp = 1L, staticId = 0L, power = 1, toughness = 1)),
            )
            tracker.diffBoosts(boosts)
            tracker.resetAll()
            val result = tracker.diffBoosts(boosts)
            result.created.size shouldBe 1
        }
    })
