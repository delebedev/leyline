package leyline.bridge.handoff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

class InteractivePromptBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("off game-loop prompt defaults immediately") {
            val bridge = InteractivePromptBridge(timeoutMs = null)
            val diagnosticThread = Thread({ }, "game-loop-test")
            val field = InteractivePromptBridge::class.java.getDeclaredField("diagnosticThread")
            field.isAccessible = true
            field.set(bridge, diagnosticThread)

            bridge.requestChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = "Delve how many cards?",
                    options = listOf("0", "1"),
                    defaultIndex = 1,
                ),
            ) shouldContainExactly listOf(1)

            bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.NON_GAME_THREAD
        }

        test("puzzle reset clears staged order zone moves") {
            val bridge = InteractivePromptBridge(timeoutMs = null)
            val cardIds = listOf(ForgeCardId(10))

            bridge.recordPendingOrderZoneMove(
                InteractivePromptBridge.PendingOrderZoneMove(
                    seatId = SeatId(1),
                    forgeCardIds = cardIds,
                    putOnTop = true,
                ),
            )

            bridge.resetForPuzzle()

            bridge.pollPendingOrderZoneMove(SeatId(1), cardIds) shouldBe null
        }
    })
