package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

class PromptJournalTest :
    FunSpec({

        tags(UnitTag)

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

        test("drainChoiceResults returns only choice results and drains once") {
            val j = PromptJournal()
            val result =
                PromptSideEffect.ChoiceResult(
                    sourceForgeCardId = ForgeCardId(42),
                    chooserSeatId = SeatId(1),
                    choiceValue = 176,
                    choiceDomain = 5,
                )
            j.record(PromptSideEffect.LegendVictim(ForgeCardId(99)))
            j.record(result)

            assertSoftly {
                j.drainChoiceResults() shouldBe listOf(result)
                j.drainChoiceResults() shouldBe emptyList()
                j.consumeLegendVictim(ForgeCardId(99)) shouldBe true
            }
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

        test("KeywordCostStash peek is non-draining (Forge calls chooseNumberForKeywordCost more than once during cost-prep)") {
            val j = PromptJournal()
            j.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            assertSoftly {
                j.peekKeywordCostDecision("Offspring") shouldBe true
                j.peekKeywordCostDecision("Offspring") shouldBe true
                j.peekKeywordCostDecision("Casualty") shouldBe null
            }
        }

        test("KeywordCostStash is last-writer-wins (a second cast's stash overwrites the prior)") {
            val j = PromptJournal()
            j.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to false)))
            j.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true, "Casualty" to false)))
            assertSoftly {
                j.peekKeywordCostDecision("Offspring") shouldBe true
                j.peekKeywordCostDecision("Casualty") shouldBe false
            }
        }

        test("clearKeywordCostStash drops the entire stash so a stale pay/decline doesn't leak") {
            val j = PromptJournal()
            j.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            j.clearKeywordCostStash()
            j.peekKeywordCostDecision("Offspring") shouldBe null
        }

        test("ConvokePayments remain active until source is cleared") {
            val j = PromptJournal()
            val source = ForgeCardId(100)
            val payment = PromptSideEffect.ConvokePayment(ForgeCardId(200), color = 7)
            j.record(PromptSideEffect.ConvokePayments(source, listOf(payment)))

            assertSoftly {
                j.activeConvokePayments(source) shouldBe listOf(payment)
                j.activeConvokePayments()[source] shouldBe listOf(payment)
            }

            j.clearConvokePayments(source)
            j.activeConvokePayments(source) shouldBe emptyList()
        }

        test("resetForPuzzle clears KeywordCostStash") {
            val j = PromptJournal()
            j.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            j.resetForPuzzle()
            j.peekKeywordCostDecision("Offspring") shouldBe null
        }
    })
