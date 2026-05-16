package leyline.bridge.handoff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import kotlin.system.measureTimeMillis

class InteractivePromptBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("off game-loop prompt defaults immediately") {
            val bridge = InteractivePromptBridge(timeoutMs = null)
            val diagnosticThread = Thread({ }, "game-loop-test")
            val field = InteractivePromptBridge::class.java.getDeclaredField("diagnosticThread")
            field.isAccessible = true
            field.set(bridge, diagnosticThread)

            val elapsed =
                measureTimeMillis {
                    bridge.requestChoice(
                        PromptRequest(
                            promptType = "choose_one",
                            message = "Delve how many cards?",
                            options = listOf("0", "1"),
                            defaultIndex = 1,
                        ),
                    ) shouldContainExactly listOf(1)
                }

            elapsed shouldBeLessThan 200L
            bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.NON_GAME_THREAD
        }
    })
