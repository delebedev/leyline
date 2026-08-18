package leyline.mechanics.endure

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
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

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
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

        // The harness auto-responds AllowYes by default (`MatchFlowHarness.drainSink` →
        // `autoRespondToOptionalAction`). For Mode A we rely on that default; for
        // Mode B we pre-seed `declineNextOptionalAction()` so the auto-responder
        // sends `CancelNo` instead. Tests assert post-resolution state.

        test("Mode A — Yes puts a +1/+1 counter on the source") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText())

            val human = h.bridge.getPlayer(SeatId(1))!!
            h.phase() shouldBe "MAIN1"

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority() // resolve the spell, ETB trigger lands on stack
            h.passPriority() // resolve the trigger → confirmAction → auto-Yes

            val endureOams =
                h.allMessages.filter {
                    it.type == GREMessageType.OptionalActionMessage_695e &&
                        it.prompt.promptId == PromptIds.ENDURE_PUT_COUNTERS
                }
            endureOams.size shouldBe 1
            val oam = endureOams.single()

            val nurturer =
                human.getZone(ZoneType.Battlefield).cards.firstOrNull { it.name == "Kin-Tree Nurturer" }
            checkNotNull(nurturer) { "Kin-Tree Nurturer should be on battlefield" }
            nurturer.getCounters(CounterEnumType.P1P1) shouldBe 1

            val tokens = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
            tokens.none { it.name.contains("Spirit", ignoreCase = true) } shouldBe true
        }

        test("Mode B — No creates a 1/1 Spirit token") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText())

            val human = h.bridge.getPlayer(SeatId(1))!!

            // Pre-seed: next OAM gets declined. Cleared after the auto-responder fires.
            h.declineNextOptionalAction()

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority()
            h.passPriority()

            val endureOams =
                h.allMessages.filter {
                    it.type == GREMessageType.OptionalActionMessage_695e &&
                        it.prompt.promptId == PromptIds.ENDURE_PUT_COUNTERS
                }
            endureOams.size shouldBe 1
            val oam = endureOams.single()

            val nurturer =
                human.getZone(ZoneType.Battlefield).cards.firstOrNull { it.name == "Kin-Tree Nurturer" }
            checkNotNull(nurturer) { "Kin-Tree Nurturer should be on battlefield" }
            nurturer.getCounters(CounterEnumType.P1P1) shouldBe 0

            val spirits =
                human.getZone(ZoneType.Battlefield).cards.filter {
                    it.isToken && it.name.contains("Spirit", ignoreCase = true)
                }
            spirits.size shouldBe 1
            val spirit = spirits.single()
            assertSoftly(spirit) {
                currentPower shouldBe 1
                currentToughness shouldBe 1
                isCreature shouldBe true
                isWhite shouldBe true
            }
        }

        test("OAM envelope — promptId, sourceId, parameters, allowCancel") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText())

            check(h.castSpellByName("Kin-Tree Nurturer"))
            h.passPriority()
            h.passPriority()

            val oam =
                h.allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
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
