package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchTargetingInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:targeting runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(
            opponentEntityId: Int,
            triggered: Boolean = false,
            minTargets: Int = 1,
            maxTargets: Int = 1,
            finishOptionIndex: Int? = null,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_entities",
                message = "Choose target",
                options = if (finishOptionIndex == null) listOf("Opponent") else listOf("Opponent", "Other", "[FINISH TARGETING]"),
                min = minTargets,
                max = maxTargets,
                candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Player, opponentEntityId)),
                targetingFinishOptionIndex = finishOptionIndex,
                route = ResolvedPromptRoute.Targeting(PromptSemantic.TargetSelection),
                isTriggeredAbility = triggered,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedTargetingInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.targeting.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.targeting.current()
            }
            return checkNotNull(published)
        }

        test("initial tap and finish publish before each engine continuation") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    result.set(
                        coordinator
                            .targeting
                            .awaitTargeting(request(board.ai.id), null, null, timeoutMs = 3_000),
                    )
                } catch (ex: Throwable) {
                    failure.set(ex)
                } finally {
                    finished.countDown()
                }
            }.start()

            val initial = awaitPublished(coordinator)
            val initialMessages = coordinator.drain(SeatId(1)).flatten()
            val initialPrompt = initialMessages.single { it.hasSelectTargetsReq() }
            val opponentTarget =
                initialPrompt.selectTargetsReq.targetsList
                    .single()
                    .targetsList
                    .single()

            val tap =
                coordinator
                    .targeting
                    .submitToggle(
                        initial.interactionId,
                        initial.gameStateId,
                        initial.targetIndex,
                        listOf(TargetToggleValue(opponentTarget.targetInstanceId, selected = true)),
                    ).shouldNotBeNull()
            val rePromptMessages = coordinator.drain(SeatId(1)).flatten()
            val rePrompt = rePromptMessages.single { it.hasSelectTargetsReq() }
            assertSoftly {
                finished.count shouldBe 1
                rePrompt.selectTargetsReq.targetsList
                    .single()
                    .targetsList
                    .single()
                    .legalAction shouldBe SelectAction.Unselect
            }
            coordinator.targeting.acknowledgeDelivery(tap.interactionId, checkNotNull(tap.deliveryToken)) shouldBe true

            val latest =
                coordinator.targeting
                    .current()
                    .shouldNotBeNull()
            val completedDeliveryReleased = CountDownLatch(1)
            val allowForgeReturn = CountDownLatch(1)
            coordinator.targeting.afterCompletedDeliveryRelease = {
                completedDeliveryReleased.countDown()
                check(allowForgeReturn.await(3, TimeUnit.SECONDS))
            }
            val done =
                coordinator.targeting
                    .submitTargets(latest.interactionId, latest.gameStateId)
                    .shouldNotBeNull()
            coordinator.drain(SeatId(1)).flatten().single { it.hasSubmitTargetsResp() }
            val acknowledged = AtomicReference<Boolean>()
            val acknowledgementReturned = CountDownLatch(1)
            Thread {
                acknowledged.set(
                    coordinator.targeting.acknowledgeDelivery(done.interactionId, checkNotNull(done.deliveryToken)),
                )
                acknowledgementReturned.countDown()
            }.start()
            try {
                assertSoftly {
                    completedDeliveryReleased.await(3, TimeUnit.SECONDS) shouldBe true
                    acknowledgementReturned.await(3, TimeUnit.SECONDS) shouldBe true
                    acknowledged.get() shouldBe true
                    coordinator.targeting
                        .current()
                        .shouldBeNull()
                    coordinator.targeting
                        .cancel(latest.interactionId, latest.gameStateId)
                        .shouldBeNull()
                    finished.count shouldBe 1
                }
            } finally {
                allowForgeReturn.countDown()
            }
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeNull()
                result.get() shouldContainExactly listOf(0)
                coordinator.targeting
                    .current()
                    .shouldBeNull()
            }
            coordinator.targeting.afterCompletedDeliveryRelease = null
        }

        test("response claim wins the targeting deadline atomically") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            val commandClaimed = CountDownLatch(1)
            coordinator.targeting.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            coordinator.targeting.afterCommandClaim = commandClaimed::countDown
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator
                        .targeting
                        .awaitTargeting(request(board.ai.id), null, null, timeoutMs = 25),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            val responseReceipt = AtomicReference<TargetingCommandReceipt>()
            val response =
                Thread {
                    responseReceipt.set(
                        coordinator.targeting.cancel(published.interactionId, published.gameStateId),
                    )
                }.also { it.start() }
            commandClaimed.await(3, TimeUnit.SECONDS) shouldBe true
            releaseTimeout.countDown()
            response.join(3_000)
            assertSoftly {
                responseReceipt.get().shouldNotBeNull().engineWillResume shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldBe emptyList()
                coordinator.failure().shouldBeNull()
            }
            coordinator.targeting.beforeTimeoutClaim = null
            coordinator.targeting.afterCommandClaim = null
        }

        test("mandatory and optional finish preserve callback identity while cancel stays empty") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))

            fun runChoice(minTargets: Int): Pair<AtomicReference<List<Int>>, CountDownLatch> {
                val result = AtomicReference<List<Int>>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator
                            .targeting
                            .awaitTargeting(
                                request(board.ai.id, minTargets = minTargets, finishOptionIndex = 2),
                                null,
                                null,
                                timeoutMs = 3_000,
                            ),
                    )
                    finished.countDown()
                }.start()
                return result to finished
            }

            val (finishResult, finishFinished) = runChoice(minTargets = 1)
            val finishWindow = awaitPublished(coordinator)
            coordinator.drain(SeatId(1)).flatten().single { it.hasSelectTargetsReq() }
            val finish =
                coordinator.targeting
                    .submitTargets(finishWindow.interactionId, finishWindow.gameStateId)
                    .shouldNotBeNull()
            coordinator.drain(SeatId(1)).flatten().single { it.hasSubmitTargetsResp() }
            assertSoftly {
                coordinator.targeting.acknowledgeDelivery(finish.interactionId, checkNotNull(finish.deliveryToken)) shouldBe true
                finishFinished.await(3, TimeUnit.SECONDS) shouldBe true
                finishResult.get() shouldContainExactly listOf(2)
            }

            val (cancelResult, cancelFinished) = runChoice(minTargets = 0)
            val cancelWindow = awaitPublished(coordinator)
            coordinator.drain(SeatId(1)).flatten().single { it.hasSelectTargetsReq() }
            assertSoftly {
                coordinator.targeting
                    .cancel(cancelWindow.interactionId, cancelWindow.gameStateId)
                    .shouldNotBeNull()
                cancelFinished.await(3, TimeUnit.SECONDS) shouldBe true
                cancelResult.get() shouldBe emptyList()
            }
        }

        test("deadline fallback retires the window and rejects late commands") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            Thread {
                result.set(bridge.requestChoice(request(board.ai.id)))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                result.get() shouldContainExactly listOf(0)
                coordinator.targeting
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
                coordinator.targeting
                    .submitToggle(
                        published.interactionId,
                        published.gameStateId,
                        published.targetIndex,
                        listOf(TargetToggleValue(2, true)),
                    ).shouldBeNull()
            }
        }

        test("initial install failure terminalizes without output or projection advance") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.targeting.beforeInstall = { error("targeting install unavailable") }
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    coordinator
                        .targeting
                        .awaitTargeting(request(board.ai.id), null, null, timeoutMs = 3_000)
                } catch (ex: Throwable) {
                    failure.set(ex)
                } finally {
                    finished.countDown()
                }
            }.start()

            finished.await(3, TimeUnit.SECONDS) shouldBe true
            val terminal = failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
            assertSoftly {
                terminal.cause?.message shouldBe "targeting install unavailable"
                coordinator.failure() shouldBe terminal
                coordinator.targeting
                    .current()
                    .shouldBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
            coordinator.targeting.beforeInstall = null
        }

        test("mandatory single target re-prompts until the client submits") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator
                        .targeting
                        .awaitTargeting(request(board.ai.id, triggered = true), null, null, timeoutMs = 3_000),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val targetId =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
                    .targetsList
                    .single()
                    .targetInstanceId
            val selected =
                coordinator.targeting
                    .submitToggle(
                        published.interactionId,
                        published.gameStateId,
                        published.targetIndex,
                        listOf(TargetToggleValue(targetId, true)),
                    ).shouldNotBeNull()
            val echo = coordinator.drain(SeatId(1)).flatten()
            assertSoftly {
                // The tap echoes as an iterative re-prompt — no auto-submit,
                // no SubmitTargetsResp, window still open.
                echo.count { it.hasSelectTargetsReq() } shouldBe 1
                echo
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .targetsList
                    .single()
                    .targetsList
                    .single()
                    .legalAction shouldBe SelectAction.Unselect
                echo.none { it.hasSubmitTargetsResp() } shouldBe true
                coordinator.targeting.current().shouldNotBeNull()
                finished.count shouldBe 1
            }
            coordinator.targeting.acknowledgeDelivery(selected.interactionId, checkNotNull(selected.deliveryToken)) shouldBe
                true

            val latest = checkNotNull(coordinator.targeting.current())
            val done =
                coordinator.targeting
                    .submitTargets(latest.interactionId, latest.gameStateId)
                    .shouldNotBeNull()
            val completion = coordinator.drain(SeatId(1)).flatten()
            assertSoftly {
                completion.count { it.hasSubmitTargetsResp() } shouldBe 1
                coordinator.targeting.acknowledgeDelivery(done.interactionId, checkNotNull(done.deliveryToken)) shouldBe
                    true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
                coordinator.targeting
                    .current()
                    .shouldBeNull()
                // No tombstone: an orphan SubmitTargetsReq with no open window is rejected.
                coordinator.targeting
                    .submitTargets(null, published.gameStateId)
                    .shouldBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
            }
        }

        test("teardown wakes the engine and rejects later commands") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    coordinator
                        .targeting
                        .awaitTargeting(request(board.ai.id), null, null, timeoutMs = null)
                } catch (ex: Throwable) {
                    failure.set(ex)
                } finally {
                    finished.countDown()
                }
            }.start()

            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val teardownCause = IllegalStateException("match closed")
            coordinator.shutdown(teardownCause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe teardownCause
                coordinator.targeting
                    .current()
                    .shouldBeNull()
            }
            shouldThrow<PlaybackTerminalFailure> {
                coordinator.targeting.submitToggle(
                    published.interactionId,
                    published.gameStateId,
                    published.targetIndex,
                    listOf(TargetToggleValue(2, true)),
                )
            }
        }
    })
