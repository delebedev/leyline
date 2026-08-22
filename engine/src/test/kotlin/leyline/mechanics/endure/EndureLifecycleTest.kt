package leyline.mechanics.endure

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.mapping.PromptIds
import leyline.testkit.castSpellByName
import leyline.testkit.declineNextOptionalAction
import leyline.testkit.passPriority
import leyline.testkit.phase
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.HeadlessMatchFactory
import leyline.tooling.headless.MatchSpec
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Endure trigger resolution — binary mode pick.
 *
 * Yes → put N +1/+1 counters on the source creature (Mode A).
 * No  → create an N/N white Spirit creature token       (Mode B).
 *
 * Wire surface: OptionalActionMessage with [PromptIds.ENDURE_PUT_COUNTERS]
 * (loc "Put N +1/+1 counters on this creature?"). Routes through the same
 * gate as `confirmTrigger`, but the engine entry point is `confirmAction`
 * with `sa.api == ApiType.Endure` (not `confirmTrigger`).
 *
 * Card under test: Kin-Tree Nurturer — `Endure 1` ETB trigger; the 1/1
 * Spirit token shape matches the existing fixture, no token-fixture
 * collision with sibling tests.
 */
class EndureLifecycleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: HeadlessMatch? = null
        afterEach {
            harness?.close()
            harness = null
        }

        fun puzzleText() =
            """
            [metadata]
            Name:Endure ETB
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Test endure mode pick on ETB trigger.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Kin-Tree Nurturer
            humanlibrary=Forest;Forest;Forest
            humanbattlefield=Swamp;Swamp;Plains
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        // The headless seam auto-responds AllowYes by default (`observe` →
        // `autoRespondToOptionalAction`). For Mode A we rely on that default; for
        // Mode B we pre-seed `declineNextOptionalAction()` so the auto-responder
        // sends `CancelNo` instead. Tests assert post-resolution state.

        test("Mode A — Yes puts a +1/+1 counter on the source") {
            val h = HeadlessMatchFactory.create(MatchSpec(seed = 42L, puzzleText = puzzleText()))
            harness = h
            h.start()

            h.phase() shouldBe "MAIN1"

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority() // resolve the spell, ETB trigger lands on stack
            h.passPriority() // resolve the trigger → confirmAction → auto-Yes

            val endureOams =
                h.observe().messages.filter {
                    it.type == GREMessageType.OptionalActionMessage_695e &&
                        it.prompt.promptId == PromptIds.ENDURE_PUT_COUNTERS
                }
            endureOams.size shouldBe 1
            val oam = endureOams.single()

            val nurturer = h.observe().cards.firstOrNull { it.seat == 1 && it.zone == "Battlefield" && it.name == "Kin-Tree Nurturer" }
            checkNotNull(nurturer) { "Kin-Tree Nurturer should be on battlefield" }
            nurturer.counters.values.sum() shouldBe 1

            val tokens = h.observe().cards.filter { it.seat == 1 && it.zone == "Battlefield" && it.isToken }
            tokens.none { it.name.contains("Spirit", ignoreCase = true) } shouldBe true
        }

        test("Mode B — No creates a 1/1 Spirit token") {
            val h = HeadlessMatchFactory.create(MatchSpec(seed = 42L, puzzleText = puzzleText()))
            harness = h
            h.start()

            // Pre-seed: next OAM gets declined. Cleared after the auto-responder fires.
            h.declineNextOptionalAction()

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority()
            h.passPriority()

            val endureOams =
                h.observe().messages.filter {
                    it.type == GREMessageType.OptionalActionMessage_695e &&
                        it.prompt.promptId == PromptIds.ENDURE_PUT_COUNTERS
                }
            endureOams.size shouldBe 1
            val oam = endureOams.single()

            val nurturer = h.observe().cards.firstOrNull { it.seat == 1 && it.zone == "Battlefield" && it.name == "Kin-Tree Nurturer" }
            checkNotNull(nurturer) { "Kin-Tree Nurturer should be on battlefield" }
            nurturer.counters.values.sum() shouldBe 0

            val spirits =
                h.observe().cards.filter {
                    it.seat == 1 &&
                        it.zone == "Battlefield" &&
                        it.isToken &&
                        it.name.contains("Spirit", ignoreCase = true)
                }
            spirits.size shouldBe 1
            val spirit = spirits.single()
            assertSoftly(spirit) {
                power shouldBe 1
                toughness shouldBe 1
                cardTypes shouldContain "Creature"
                colors shouldContain "White"
            }
        }

        test("OAM envelope — promptId, sourceId, parameters, allowCancel") {
            val h = HeadlessMatchFactory.create(MatchSpec(seed = 42L, puzzleText = puzzleText()))
            harness = h
            h.start()

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority()
            h.passPriority()

            val oam =
                h.observe().messages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected OptionalActionMessage for Endure trigger" }

            assertSoftly(oam) {
                prompt.promptId shouldBe PromptIds.ENDURE_PUT_COUNTERS
                prompt.parametersList.size shouldBe 1
                prompt.parametersList[0].parameterName shouldBe "CardId"
                // sendOptionalActionMessage emits sourceId = host card iid for both
                // the outer prompt's CardId param and optionalActionMessage.sourceId.
                // Pins they're consistent until the trigger-ability iid path is added.
                optionalActionMessage.sourceId shouldBe prompt.parametersList[0].numberValue
                allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.No_a526
            }
        }
    })
