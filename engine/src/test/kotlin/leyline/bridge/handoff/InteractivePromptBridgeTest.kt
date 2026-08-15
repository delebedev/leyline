package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.PrioritySignal

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
                    route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                ),
            ) shouldContainExactly listOf(1)

            bridge.history.single().outcome shouldBe PromptCallStatus.NON_GAME_THREAD
        }

        test("prompt inside a non-interactive scope defaults and records the refusal") {
            val bridge = InteractivePromptBridge(timeoutMs = null)

            NonInteractiveScope.quiet {
                bridge.requestChoice(
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose cards to tap",
                        options = listOf("a", "b"),
                        defaultIndex = 0,
                        route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                    ),
                )
            } shouldContainExactly listOf(0)

            bridge.history.single().outcome shouldBe PromptCallStatus.NON_INTERACTIVE_SCOPE
        }

        test("strict bridge throws on a prompt inside a non-interactive scope") {
            val bridge = InteractivePromptBridge(timeoutMs = null, strict = true)

            shouldThrow<StrictPromptRefusalException> {
                NonInteractiveScope.bestEffort {
                    bridge.requestChoice(
                        PromptRequest(
                            promptType = "choose_one",
                            message = "?",
                            options = listOf("a"),
                            route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                        ),
                    )
                }
            }
        }

        test("strict bridge throws on an off game-loop prompt") {
            val bridge = InteractivePromptBridge(timeoutMs = null, strict = true)
            val diagnosticThread = Thread({ }, "game-loop-test")
            val field = InteractivePromptBridge::class.java.getDeclaredField("diagnosticThread")
            field.isAccessible = true
            field.set(bridge, diagnosticThread)

            shouldThrow<StrictPromptRefusalException> {
                bridge.requestChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = "?",
                        options = listOf("a"),
                        route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                    ),
                )
            }
        }

        test("AutoResolve defaults synchronously without publishing and preserves prompt scheduling") {
            val signal = PrioritySignal()
            val bridge = InteractivePromptBridge(timeoutMs = null, prioritySignal = signal, strict = true)
            val diagnosticThread = Thread({ }, "different-game-loop")
            val field = InteractivePromptBridge::class.java.getDeclaredField("diagnosticThread")
            field.isAccessible = true
            field.set(bridge, diagnosticThread)

            val result =
                bridge.requestChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = "Default this policy choice",
                        options = listOf("first", "second"),
                        defaultIndex = 1,
                    ),
                )

            assertSoftly {
                result shouldContainExactly listOf(1)
                bridge.history.single().outcome shouldBe PromptCallStatus.DEFAULTED_POLICY
                signal.consumePromptResolved() shouldBe true
            }
        }

        test("observed pending target consumption preserves a later equal target") {
            val bridge = InteractivePromptBridge(timeoutMs = null)
            val target =
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = 1,
                    spellName = "Murder",
                    index = 1,
                    affectorInstanceIdAtRecord = 10,
                    affectees = listOf(InteractivePromptBridge.PendingTarget.TargetAffectee(targetForgeCardId = 2)),
                )
            bridge.addPendingTargetSpec(target)
            val observed = bridge.snapshotPendingTargetSpecEntries().single()
            bridge.addPendingTargetSpec(target)

            bridge.consumePendingTargetSpecEntries(listOf(observed))

            bridge.snapshotPendingTargetSpecs() shouldContainExactly listOf(target)
        }
    })
