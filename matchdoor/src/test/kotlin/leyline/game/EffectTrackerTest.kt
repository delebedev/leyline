package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
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

        test("emitInitEffects returns 3 Created+Destroyed pairs with IDs 7002-7004") {
            val tracker = EffectTracker()
            val result = tracker.emitInitEffects()

            result.created.size shouldBe 3
            result.destroyed.size shouldBe 3
            result.created.map { it.syntheticId } shouldBe listOf(7002, 7003, 7004)
            result.destroyed.map { it.syntheticId } shouldBe listOf(7002, 7003, 7004)

            // Next ID after init should be 7005
            tracker.nextEffectId() shouldBe 7005
        }

        test("emitInitEffectsOnce only fires once") {
            val tracker = EffectTracker()
            val first = tracker.emitInitEffectsOnce()
            first.created.size shouldBe 3

            val second = tracker.emitInitEffectsOnce()
            second.created.shouldBeEmpty()
            second.destroyed.shouldBeEmpty()
        }

        test("resetAll allows init effects to fire again") {
            val tracker = EffectTracker()
            tracker.emitInitEffectsOnce()
            tracker.resetAll()
            val result = tracker.emitInitEffectsOnce()
            result.created.size shouldBe 3
            result.created.map { it.syntheticId } shouldBe listOf(7002, 7003, 7004)
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

        // --- Keyword tracking ---

        test("diffKeywords creates entry on first call") {
            val tracker = EffectTracker()
            val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
            val diff = tracker.diffKeywords(input)
            diff.created.size shouldBe 1
            diff.created[0].keyword shouldBe "Trample"
            diff.created[0].cardInstanceId shouldBe 100
            diff.destroyed.shouldBeEmpty()
        }

        test("diffKeywords returns empty when keyword persists") {
            val tracker = EffectTracker()
            val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
            tracker.diffKeywords(input)
            val diff2 = tracker.diffKeywords(input)
            diff2.created.shouldBeEmpty()
            diff2.destroyed.shouldBeEmpty()
        }

        test("diffKeywords destroys entry when keyword removed") {
            val tracker = EffectTracker()
            val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
            tracker.diffKeywords(input)
            val diff2 = tracker.diffKeywords(emptyMap())
            diff2.destroyed.size shouldBe 1
            diff2.created.shouldBeEmpty()
        }

        test("keyword and boost effects share the same ID counter") {
            val tracker = EffectTracker()
            tracker.nextEffectId() // simulate a boost taking the first ID
            val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
            val diff = tracker.diffKeywords(input)
            diff.created[0].syntheticId shouldBeGreaterThan EffectTracker.INITIAL_EFFECT_ID
        }
    })
