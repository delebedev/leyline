package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge

class TargetingHandlerStashJournalTest :
    FunSpec({

        tags(UnitTag)

        test("stashOptionalCostIndices records OptionalCostStash on journal") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingHandler.stashOptionalCostIndices(bridge, listOf(0, 2))
            bridge.journal.consumeOptionalCostStash() shouldBe listOf(0, 2)
        }
    })
