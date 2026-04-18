package leyline.bridge

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class PromptJournalTest :
    FunSpec({

        tags(UnitTag)

        test("consumeSearched removes matching entry and returns true exactly once") {
            val j = PromptJournal()
            j.record(PromptSideEffect.SearchedToHand(ForgeCardId(42)))
            j.consumeSearched(ForgeCardId(42)) shouldBe true
            j.consumeSearched(ForgeCardId(42)) shouldBe false
        }

        test("consumeLegendVictim is independent per id") {
            val j = PromptJournal()
            j.record(PromptSideEffect.LegendVictim(ForgeCardId(1)))
            j.record(PromptSideEffect.LegendVictim(ForgeCardId(2)))
            assertSoftly {
                j.consumeLegendVictim(ForgeCardId(2)) shouldBe true
                j.consumeLegendVictim(ForgeCardId(1)) shouldBe true
                j.consumeLegendVictim(ForgeCardId(1)) shouldBe false
            }
        }

        test("activeReveal returns last RevealStarted until RevealEnded") {
            val j = PromptJournal()
            j.activeReveal() shouldBe null
            val r = PromptSideEffect.RevealStarted(listOf(ForgeCardId(7)), SeatId(1))
            j.record(r)
            j.activeReveal() shouldBe r
            j.endActiveReveal()
            j.activeReveal() shouldBe null
        }

        test("consumeOptionalCostStash drains once") {
            val j = PromptJournal()
            j.record(PromptSideEffect.OptionalCostStash(listOf(0, 2)))
            j.consumeOptionalCostStash() shouldBe listOf(0, 2)
            j.consumeOptionalCostStash() shouldBe null
        }

        test("resetForPuzzle clears everything") {
            val j = PromptJournal()
            j.record(PromptSideEffect.LegendVictim(ForgeCardId(1)))
            j.record(PromptSideEffect.OptionalCostStash(listOf(3)))
            j.resetForPuzzle()
            j.consumeLegendVictim(ForgeCardId(1)) shouldBe false
            j.consumeOptionalCostStash() shouldBe null
        }

        test("OptionalCostStash is last-writer-wins — a second record overwrites the first") {
            val j = PromptJournal()
            j.record(PromptSideEffect.OptionalCostStash(listOf(0)))
            j.record(PromptSideEffect.OptionalCostStash(listOf(1, 2)))
            j.consumeOptionalCostStash() shouldBe listOf(1, 2)
            j.consumeOptionalCostStash() shouldBe null
        }

        test("RevealStarted record overwrites previous active reveal (single-slot)") {
            val j = PromptJournal()
            j.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(1)), SeatId(1)))
            j.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(2)), SeatId(2)))
            j.activeReveal()?.ownerSeatId shouldBe SeatId(2)
            j.endActiveReveal()
            j.activeReveal() shouldBe null
        }

        test("drain entries persist across reveal lifecycle (separate storage)") {
            val j = PromptJournal()
            j.record(PromptSideEffect.LegendVictim(ForgeCardId(42)))
            j.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(1)), SeatId(1)))
            j.endActiveReveal()
            // Reveal end must not drain unrelated effects.
            j.consumeLegendVictim(ForgeCardId(42)) shouldBe true
        }

        test("activeReveal peek returns the latest RevealStarted after endActiveReveal + restart") {
            val j = PromptJournal()
            j.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(1)), SeatId(1)))
            j.endActiveReveal()
            j.activeReveal() shouldBe null
            j.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(2)), SeatId(2)))
            j.activeReveal()?.ownerSeatId shouldBe SeatId(2)
        }
    })
