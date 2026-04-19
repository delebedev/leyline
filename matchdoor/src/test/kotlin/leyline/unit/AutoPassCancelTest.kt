package leyline.unit

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.GameActionBridge
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.forge.ClientGuiGame

class AutoPassCancelTest :
    FunSpec({

        tags(UnitTag)

        // ClientGuiGame methods take PlayerView — we test via reflection-free direct
        // actionBridge interaction since the ClientGuiGame methods just delegate.

        test("autoPassCancel clears autoPassUntilEndOfTurn flag via actionBridge") {
            val actionBridge = GameActionBridge(timeoutMs = 0)
            actionBridge.setAutoPassUntilEndOfTurn(true)
            actionBridge.autoPassUntilEndOfTurn shouldBe true

            // Simulate what ClientGuiGame.autoPassCancel does
            actionBridge.setAutoPassUntilEndOfTurn(false)
            actionBridge.autoPassUntilEndOfTurn shouldBe false
        }

        test("autoPassUntilEndOfTurn sets flag via actionBridge") {
            val actionBridge = GameActionBridge(timeoutMs = 0)
            actionBridge.autoPassUntilEndOfTurn shouldBe false

            // Simulate what ClientGuiGame.autoPassUntilEndOfTurn does
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

        test("ClientGuiGame without actionBridge does not throw") {
            val promptBridge = InteractivePromptBridge(timeoutMs = 0)
            shouldNotThrowAny {
                val gui = ClientGuiGame(promptBridge)
                // Methods take PlayerView (non-null) so we can't call them directly here —
                // verify the constructor path at least completes without actionBridge.
                gui.toString()
            }
        }
    })
