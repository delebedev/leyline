package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ClientAutoPassState
import leyline.conformance.settingsMessage
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority

class PriorityDecisionTest :
    FunSpec({

        tags(UnitTag)

        test("default state — shouldAutoPass=false") {
            val state = ClientAutoPassState()
            state.shouldAutoPass() shouldBe false
            state.autoPassOption shouldBe AutoPassOption.None_a465
            state.stackAutoPassOption shouldBe AutoPassOption.None_a465
        }

        test("update from settings — ResolveAll → shouldAutoPass=true") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            state.autoPassOption shouldBe AutoPassOption.ResolveAll
            state.shouldAutoPass() shouldBe true
        }

        test("update from settings — ResolveMyStackEffects → shouldAutoPass=true") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.ResolveMyStackEffects })
            state.autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
            state.shouldAutoPass() shouldBe true
        }

        test("update from settings — FullControl → shouldAutoPass=false") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.FullControl })
            state.autoPassOption shouldBe AutoPassOption.FullControl
            state.shouldAutoPass() shouldBe false
        }

        test("None in settings does not overwrite existing option") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })

            // A settings update with None should not clear the previous value
            state.update(settingsMessage { autoPassOption = AutoPassOption.None_a465 })
            state.autoPassOption shouldBe AutoPassOption.ResolveAll
        }

        test("stackAutoPassOption updated independently") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { stackAutoPassOption = AutoPassOption.ResolveAll })
            state.stackAutoPassOption shouldBe AutoPassOption.ResolveAll
            // autoPassOption unchanged
            state.autoPassOption shouldBe AutoPassOption.None_a465
        }

        test("Clear option → shouldAutoPass=false") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.Clear_a465 })
            state.autoPassOption shouldBe AutoPassOption.Clear_a465
            state.shouldAutoPass() shouldBe false
        }

        // --- autoPassPriority (full control) ---

        test("default autoPassPriority is None") {
            val state = ClientAutoPassState()
            state.autoPassPriority shouldBe AutoPassPriority.None_a099
            state.isFullControl shouldBe false
        }

        test("No_a099 = full control ON → shouldAutoPass=false even with ResolveAll") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })
            state.shouldAutoPass() shouldBe true

            // Client sends full control
            state.updateAutoPassPriority(AutoPassPriority.No_a099)
            state.isFullControl shouldBe true
            state.shouldAutoPass() shouldBe false
        }

        test("Yes_a099 = auto-pass OK → shouldAutoPass follows autoPassOption") {
            val state = ClientAutoPassState()
            state.update(settingsMessage { autoPassOption = AutoPassOption.ResolveAll })

            state.updateAutoPassPriority(AutoPassPriority.Yes_a099)
            state.isFullControl shouldBe false
            state.shouldAutoPass() shouldBe true
        }

        test("None_a099 in updateAutoPassPriority does not overwrite") {
            val state = ClientAutoPassState()
            state.updateAutoPassPriority(AutoPassPriority.No_a099)
            state.isFullControl shouldBe true

            // None should not clear the value
            state.updateAutoPassPriority(AutoPassPriority.None_a099)
            state.isFullControl shouldBe true
        }

        test("full control cleared by sending Yes_a099") {
            val state = ClientAutoPassState()
            state.updateAutoPassPriority(AutoPassPriority.No_a099)
            state.isFullControl shouldBe true

            state.updateAutoPassPriority(AutoPassPriority.Yes_a099)
            state.isFullControl shouldBe false
        }

        // --- opponentStops ---

        test("opponent stops default empty") {
            val state = ClientAutoPassState()
            state.hasOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN) shouldBe false
            state.hasOpponentStop(forge.game.phase.PhaseType.MAIN1) shouldBe false
            state.hasOpponentStop(forge.game.phase.PhaseType.END_OF_TURN) shouldBe false
        }

        test("setOpponentStop enables and disables") {
            val state = ClientAutoPassState()
            state.setOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN, true)
            state.hasOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN) shouldBe true

            state.setOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN, false)
            state.hasOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN) shouldBe false
        }

        test("multiple opponent stops tracked independently") {
            val state = ClientAutoPassState()
            state.setOpponentStop(forge.game.phase.PhaseType.MAIN1, true)
            state.setOpponentStop(forge.game.phase.PhaseType.END_OF_TURN, true)

            state.hasOpponentStop(forge.game.phase.PhaseType.MAIN1) shouldBe true
            state.hasOpponentStop(forge.game.phase.PhaseType.END_OF_TURN) shouldBe true
            state.hasOpponentStop(forge.game.phase.PhaseType.COMBAT_BEGIN) shouldBe false

            // Clear one, other remains
            state.setOpponentStop(forge.game.phase.PhaseType.MAIN1, false)
            state.hasOpponentStop(forge.game.phase.PhaseType.MAIN1) shouldBe false
            state.hasOpponentStop(forge.game.phase.PhaseType.END_OF_TURN) shouldBe true
        }
    })
