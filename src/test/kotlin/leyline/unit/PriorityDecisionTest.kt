package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ClientAutoPassState
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

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
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            state.update(settings)
            state.autoPassOption shouldBe AutoPassOption.ResolveAll
            state.shouldAutoPass() shouldBe true
        }

        test("update from settings — ResolveMyStackEffects → shouldAutoPass=true") {
            val state = ClientAutoPassState()
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                .build()
            state.update(settings)
            state.autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
            state.shouldAutoPass() shouldBe true
        }

        test("update from settings — FullControl → shouldAutoPass=false") {
            val state = ClientAutoPassState()
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.FullControl)
                .build()
            state.update(settings)
            state.autoPassOption shouldBe AutoPassOption.FullControl
            state.shouldAutoPass() shouldBe false
        }

        test("None in settings does not overwrite existing option") {
            val state = ClientAutoPassState()
            val settings1 = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            state.update(settings1)

            // A settings update with None should not clear the previous value
            val settings2 = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.None_a465)
                .build()
            state.update(settings2)
            state.autoPassOption shouldBe AutoPassOption.ResolveAll
        }

        test("stackAutoPassOption updated independently") {
            val state = ClientAutoPassState()
            val settings = SettingsMessage.newBuilder()
                .setStackAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            state.update(settings)
            state.stackAutoPassOption shouldBe AutoPassOption.ResolveAll
            // autoPassOption unchanged
            state.autoPassOption shouldBe AutoPassOption.None_a465
        }

        test("Clear option → shouldAutoPass=false") {
            val state = ClientAutoPassState()
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.Clear_a465)
                .build()
            state.update(settings)
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
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            state.update(settings)
            state.shouldAutoPass() shouldBe true

            // Client sends full control
            state.updateAutoPassPriority(AutoPassPriority.No_a099)
            state.isFullControl shouldBe true
            state.shouldAutoPass() shouldBe false
        }

        test("Yes_a099 = auto-pass OK → shouldAutoPass follows autoPassOption") {
            val state = ClientAutoPassState()
            val settings = SettingsMessage.newBuilder()
                .setAutoPassOption(AutoPassOption.ResolveAll)
                .build()
            state.update(settings)

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
    })
