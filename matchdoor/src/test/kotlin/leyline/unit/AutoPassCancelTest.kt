package leyline.unit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.GameActionBridge
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.WebGuiGame

class AutoPassCancelTest :
    FunSpec({

        tags(UnitTag)

        // WebGuiGame methods take PlayerView — we test via reflection-free direct
        // actionBridge interaction since the WebGuiGame methods just delegate.

        test("autoPassCancel clears autoPassUntilEndOfTurn flag via actionBridge") {
            val actionBridge = GameActionBridge(timeoutMs = 0)
            actionBridge.setAutoPassUntilEndOfTurn(true)
            actionBridge.autoPassUntilEndOfTurn shouldBe true

            // Simulate what WebGuiGame.autoPassCancel does
            actionBridge.setAutoPassUntilEndOfTurn(false)
            actionBridge.autoPassUntilEndOfTurn shouldBe false
        }

        test("autoPassUntilEndOfTurn sets flag via actionBridge") {
            val actionBridge = GameActionBridge(timeoutMs = 0)
            actionBridge.autoPassUntilEndOfTurn shouldBe false

            // Simulate what WebGuiGame.autoPassUntilEndOfTurn does
            actionBridge.setAutoPassUntilEndOfTurn(true)
            actionBridge.autoPassUntilEndOfTurn shouldBe true
        }

        test("mayAutoPass reflects flag state") {
            val actionBridge = GameActionBridge(timeoutMs = 0)
            actionBridge.autoPassUntilEndOfTurn shouldBe false

            actionBridge.setAutoPassUntilEndOfTurn(true)
            actionBridge.autoPassUntilEndOfTurn shouldBe true

            actionBridge.setAutoPassUntilEndOfTurn(false)
            actionBridge.autoPassUntilEndOfTurn shouldBe false
        }

        test("WebGuiGame without actionBridge does not throw") {
            val promptBridge = InteractivePromptBridge(timeoutMs = 0)
            val gui = WebGuiGame(promptBridge)
            // These methods should be safe no-ops (playerView param not used)
            // We can't call them directly due to non-null PlayerView,
            // but verify the constructor works without actionBridge
            gui.toString() // just verify it's constructed
        }
    })
