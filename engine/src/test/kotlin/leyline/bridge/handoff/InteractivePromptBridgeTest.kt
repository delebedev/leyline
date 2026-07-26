package leyline.bridge.handoff

import forge.game.Game
import forge.game.card.Card
import forge.game.cost.Cost
import forge.game.spellability.AbilityActivated
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.NonInteractiveScope
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InteractivePromptBridgeTest :
    FunSpec({

        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeLocalization()
        }

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

        test("prompt inside a non-interactive scope defaults and records the refusal") {
            val bridge = InteractivePromptBridge(timeoutMs = null)

            NonInteractiveScope.quiet {
                bridge.requestChoice(
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose cards to tap",
                        options = listOf("a", "b"),
                        defaultIndex = 0,
                    ),
                )
            } shouldContainExactly listOf(0)

            bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.NON_INTERACTIVE_SCOPE
        }

        test("strict bridge throws on a prompt inside a non-interactive scope") {
            val bridge = InteractivePromptBridge(timeoutMs = null, strict = true)

            shouldThrow<StrictPromptRefusalException> {
                NonInteractiveScope.bestEffort {
                    bridge.requestChoice(
                        PromptRequest(promptType = "choose_one", message = "?", options = listOf("a")),
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
                    PromptRequest(promptType = "choose_one", message = "?", options = listOf("a")),
                )
            }
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

        test("cancelled prompt yields to a successor") {
            val bridge = InteractivePromptBridge(timeoutMs = 5_000)
            val firstRequest =
                PromptRequest(
                    promptType = "choose_one",
                    message = "First?",
                    options = listOf("a", "b"),
                    defaultIndex = 0,
                )
            val secondRequest = firstRequest.copy(message = "Second?", defaultIndex = 1)
            val first = CompletableFuture.supplyAsync { bridge.requestChoice(firstRequest) }

            val firstPending = bridge.awaitPendingPrompt()
            firstPending.request.message shouldBe "First?"
            bridge.cancelPending()

            assertSoftly {
                bridge.getPendingPrompt() shouldBe null
                bridge.submitResponse(firstPending.promptId, listOf(1)) shouldBe false
                first.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(0)
            }
            val second = CompletableFuture.supplyAsync { bridge.requestChoice(secondRequest) }
            val pending = bridge.awaitPendingPrompt()

            assertSoftly {
                pending.request.message shouldBe "Second?"
                bridge.submitResponse(pending.promptId, listOf(0)) shouldBe true
                second.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(0)
            }
        }

        test("timeout retires the prompt before diagnostics and rejects a late response") {
            val bridge = InteractivePromptBridge(timeoutMs = 250)
            val retiredAtListener = CompletableFuture<Boolean>()
            val releaseListener = CountDownLatch(1)
            bridge.timeoutListener = {
                retiredAtListener.complete(bridge.getPendingPrompt() == null)
                releaseListener.await(5, TimeUnit.SECONDS)
            }
            val request = testRequest()
            val choice = CompletableFuture.supplyAsync { bridge.requestChoice(request) }
            val prompt = bridge.awaitPendingPrompt()
            val journalEffects = AtomicInteger()
            val paymentEffects = AtomicInteger()
            val advances = AtomicInteger()

            try {
                assertSoftly {
                    retiredAtListener.get(5, TimeUnit.SECONDS) shouldBe true
                    bridge.submitResponse(prompt.promptId, listOf(0)) {
                        journalEffects.incrementAndGet()
                        paymentEffects.incrementAndGet()
                        advances.incrementAndGet()
                    } shouldBe false
                    bridge.requestPromptAdvice(
                        prompt.promptId,
                        PromptAdviceRequest.ModalChoice(listOf(0)),
                    ) shouldBe null
                    journalEffects.get() shouldBe 0
                    paymentEffects.get() shouldBe 0
                    advances.get() shouldBe 0
                }
            } finally {
                releaseListener.countDown()
            }

            assertSoftly {
                choice.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(request.defaultIndex)
                bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.TIMEOUT
                bridge.getPendingPrompt() shouldBe null
            }
        }

        test("timeout releases an in-flight advice waiter") {
            val bridge = InteractivePromptBridge(timeoutMs = 100)
            val ability = testAbility()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            bridge.promptAbilityAdvisor =
                PromptAbilityAdvisor { _, _, _ ->
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    listOf(0)
                }
            val request = testRequest()
            val choice = CompletableFuture.supplyAsync { bridge.requestChoice(request, ability) }
            val prompt = bridge.awaitPendingPrompt()
            val advice =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(1)))
                }

            entered.await(5, TimeUnit.SECONDS) shouldBe true
            advice.get(5, TimeUnit.SECONDS) shouldBe null
            release.countDown()

            assertSoftly {
                choice.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(request.defaultIndex)
                bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.TIMEOUT
                bridge.getPendingPrompt() shouldBe null
            }
        }

        test("accepted response remains terminal when in-flight advice fails") {
            val bridge = InteractivePromptBridge(timeoutMs = 5_000)
            val ability = testAbility()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            bridge.promptAbilityAdvisor =
                PromptAbilityAdvisor { _, _, _ ->
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    error("advisor failed after response")
                }
            val request = testRequest()
            val choice = CompletableFuture.supplyAsync { bridge.requestChoice(request, ability) }
            val prompt = bridge.awaitPendingPrompt()
            val journalEffects = AtomicInteger()
            val paymentEffects = AtomicInteger()
            val advances = AtomicInteger()
            val advice =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(1)))
                }

            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                bridge.submitResponse(prompt.promptId, listOf(0)) {
                    journalEffects.incrementAndGet()
                    paymentEffects.incrementAndGet()
                    advances.incrementAndGet()
                } shouldBe true
                bridge.submitResponse(prompt.promptId, listOf(1)) {
                    journalEffects.incrementAndGet()
                    paymentEffects.incrementAndGet()
                    advances.incrementAndGet()
                } shouldBe false
            } finally {
                release.countDown()
            }

            assertSoftly {
                advice.get(5, TimeUnit.SECONDS) shouldBe null
                choice.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(0)
                bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.RESPONDED
                bridge.getPendingPrompt() shouldBe null
                journalEffects.get() shouldBe 1
                paymentEffects.get() shouldBe 1
                advances.get() shouldBe 1
            }
        }

        test("advisor error drains queued command waiters") {
            val bridge = InteractivePromptBridge(timeoutMs = 5_000)
            val ability = testAbility()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            bridge.promptAbilityAdvisor =
                PromptAbilityAdvisor { _, _, _ ->
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    error("advisor failed")
                }
            val request = testRequest()
            val choice = CompletableFuture.supplyAsync { bridge.requestChoice(request, ability) }
            val prompt = bridge.awaitPendingPrompt()
            val first =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(1)))
                }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val queued =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(2)))
                }

            release.countDown()

            assertSoftly {
                first.get(5, TimeUnit.SECONDS) shouldBe null
                queued.get(5, TimeUnit.SECONDS) shouldBe null
                choice.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(request.defaultIndex)
                bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.ERROR
            }
        }

        test("superseding an in-flight prompt releases every command waiter") {
            val bridge = InteractivePromptBridge(timeoutMs = 5_000)
            val ability = testAbility()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            bridge.promptAbilityAdvisor =
                PromptAbilityAdvisor { _, _, _ ->
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    listOf(0)
                }
            val request = testRequest()
            val choice = CompletableFuture.supplyAsync { bridge.requestChoice(request, ability) }
            val prompt = bridge.awaitPendingPrompt()
            val inFlight =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(1)))
                }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val queued =
                CompletableFuture.supplyAsync {
                    bridge.requestPromptAdvice(prompt.promptId, PromptAdviceRequest.ModalChoice(listOf(2)))
                }

            bridge.cancelPending()

            assertSoftly {
                inFlight.get(5, TimeUnit.SECONDS) shouldBe null
                queued.get(5, TimeUnit.SECONDS) shouldBe null
            }
            release.countDown()
            choice.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(request.defaultIndex)
        }
    })

private fun testRequest() =
    PromptRequest(
        promptType = "choose_one",
        message = "Choose?",
        options = listOf("a", "b"),
        defaultIndex = 1,
    )

private fun testAbility() =
    object : AbilityActivated(Card(999, null as Game?), Cost("1", true), null) {
        override fun resolve() = Unit
    }
