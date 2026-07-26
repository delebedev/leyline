package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Deadlined poll for an engine-thread pending — sleep is poll interval, not a race. */
@Suppress("NoThreadSleepInTests")
private fun pollForPending(bridge: GameActionBridge): GameActionBridge.PendingAction? {
    repeat(50) {
        bridge.getPending()?.let { return it }
        Thread.sleep(10)
    }
    return null
}

class GameActionBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("pending action view exposes no completion primitive") {
            GameActionBridge.PendingAction::class.java.declaredFields
                .map { it.name }
                .toSet() shouldBe setOf("actionId", "state", "publishedCatalog")
        }

        test("getPending returns null when future is already completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            // Simulate engine thread blocking on awaitAction
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()

            // Submit action — future completes, but engine thread hasn't cleared pending yet
            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)

            // getPending should filter out the completed future
            bridge.getPending().shouldBeNull()

            engineThread.join(2000)
        }

        test("getPending returns action when future is not completed") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()

            // Future not yet completed — should be visible
            bridge.getPending().shouldNotBeNull()
            bridge.getPending()!!.state.phase shouldBe "Main1"

            // Clean up: complete so engine thread unblocks
            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("null timeout waits until explicit response") {
            val bridge = GameActionBridge(timeoutMs = null)
            val result = AtomicReference<PlayerAction?>()
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    result.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            result.get().shouldBeNull()

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
            result.get() shouldBe PlayerAction.PassPriority
        }

        test("response gsId must match emitted prompt gsId") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)

            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            val passOffer =
                bridge.registerActionOffer(
                    Action.newBuilder().setActionType(ActionType.Pass).build(),
                    PlayerAction.PassPriority,
                )
            assertSoftly {
                bridge.acceptsResponse(pending, 12) shouldBe false
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(passOffer),
                ) shouldBe true
                bridge.acceptsResponse(pending, 11) shouldBe false
                bridge.acceptsResponse(pending, 12) shouldBe true
            }

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("catalog resolves client payment detail against the offered selector") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            val cast =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(14)
                    .setGrpId(91)
                    .setAbilityGrpId(44)
                    .setAlternativeGrpId(0)
                    .build()
            val castOffer = bridge.registerActionOffer(cast, PlayerAction.PassPriority)
            bridge.bindActionCatalog(pending.actionId, 12, listOf(castOffer)) shouldBe true
            val response = cast.toBuilder().addManaCost(ManaRequirement.newBuilder().setCount(1)).build()
            assertSoftly {
                bridge.resolveOfferedAction(pending, 12, response)?.token shouldBe castOffer.token
                bridge.resolveOfferedAction(pending, 11, response).shouldBeNull()
                bridge.resolveOfferedAction(pending, 12, Action.newBuilder().setActionType(ActionType.FloatMana).build()).shouldBeNull()
            }

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("catalog coalesces payment variants and supersedes an earlier prompt") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            val pass = Action.newBuilder().setActionType(ActionType.Pass).build()
            val firstPassOffer = bridge.registerActionOffer(pass, PlayerAction.PassPriority)
            val secondPassOffer = bridge.registerActionOffer(pass, PlayerAction.PassPriority)
            assertSoftly {
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(firstPassOffer, secondPassOffer),
                ) shouldBe true
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(firstPassOffer),
                ) shouldBe
                    true
                bridge.bindActionCatalog(
                    pending.actionId,
                    13,
                    listOf(firstPassOffer),
                ) shouldBe
                    true
                bridge.resolveOfferedAction(pending, 12, pass).shouldBeNull()
                bridge.resolveOfferedAction(pending, 13, pass)?.token shouldBe firstPassOffer.token
                firstPassOffer.token shouldBe secondPassOffer.token
            }

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("catalog resolves distinct executable variants by exact action payload") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            val activate =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Activate_add3)
                    .setInstanceId(14)
                    .setAbilityGrpId(44)
                    .build()
            val first = activate.toBuilder().addManaCost(ManaRequirement.newBuilder().setCount(1)).build()
            val second = activate.toBuilder().addManaCost(ManaRequirement.newBuilder().setCount(2)).build()
            val firstOffer = bridge.registerActionOffer(first, PlayerAction.PlayLand(ForgeCardId(1)))
            val secondOffer = bridge.registerActionOffer(second, PlayerAction.PlayLand(ForgeCardId(2)))
            bridge.bindActionCatalog(
                pending.actionId,
                12,
                listOf(firstOffer, secondOffer),
            ) shouldBe true

            assertSoftly {
                bridge.resolveOfferedAction(pending, 12, first)?.token shouldBe firstOffer.token
                bridge.resolveOfferedAction(pending, 12, second)?.token shouldBe secondOffer.token
                bridge.resolveOfferedAction(pending, 12, activate).shouldBeNull()
            }

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("catalog cannot bind an action request without a live priority wait") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val pass = Action.newBuilder().setActionType(ActionType.Pass).build()

            bridge.bindActionCatalog(
                actionId = "missing",
                gameStateId = 12,
                offers = listOf(GameActionBridge.ActionOffer(pass, ActionToken("unknown"))),
            ) shouldBe false
        }

        test("catalog rejects duplicate response keys with distinct commands") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val ready = CountDownLatch(1)
            val engineThread =
                Thread {
                    ready.countDown()
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val pending = pollForPending(bridge).shouldNotBeNull()
            val play =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(14)
                    .build()
            val firstOffer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(1)))
            val secondOffer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(2)))
            bridge.bindActionCatalog(
                pending.actionId,
                12,
                listOf(firstOffer, secondOffer),
            ) shouldBe false
            bridge.submitActionToken(pending.actionId, firstOffer.token) shouldBe false

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }

        test("unknown and duplicate tokens are rejected without substituting pass") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val result = AtomicReference<PlayerAction?>()
            val engineThread =
                Thread {
                    result.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPending(bridge).shouldNotBeNull()
            val play =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(14)
                    .build()
            val offer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(7)))
            bridge.bindActionCatalog(pending.actionId, 12, listOf(offer)) shouldBe true

            assertSoftly {
                bridge.submitActionToken(pending.actionId, ActionToken("unknown")) shouldBe false
                bridge.getPending()?.actionId shouldBe pending.actionId
                bridge.submitActionToken(pending.actionId, offer.token) shouldBe true
                bridge.submitActionToken(pending.actionId, offer.token) shouldBe false
            }
            engineThread.join(2000)
            result.get() shouldBe PlayerAction.PlayLand(ForgeCardId(7))
        }

        test("superseding a priority catalog invalidates its earlier tokens") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val result = AtomicReference<PlayerAction?>()
            val engineThread =
                Thread {
                    result.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPending(bridge).shouldNotBeNull()
            val play =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(14)
                    .build()
            val earlier = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(1)))
            bridge.bindActionCatalog(pending.actionId, 12, listOf(earlier)) shouldBe true
            val earlierPublication = bridge.getPending()?.publishedCatalog.shouldNotBeNull()
            val replacement = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(2)))
            bridge.bindActionCatalog(pending.actionId, 13, listOf(replacement)) shouldBe true
            val replacementPublication = bridge.getPending()?.publishedCatalog.shouldNotBeNull()

            assertSoftly {
                earlierPublication.gameStateId shouldBe 12
                earlierPublication.catalog.values
                    .flatten()
                    .single()
                    .token shouldBe earlier.token
                replacementPublication.gameStateId shouldBe 13
                replacementPublication.catalog.values
                    .flatten()
                    .single()
                    .token shouldBe replacement.token
                bridge.submitActionToken(pending.actionId, earlier.token) shouldBe false
                bridge.submitActionToken(pending.actionId, replacement.token) shouldBe true
            }
            engineThread.join(2000)
            result.get() shouldBe PlayerAction.PlayLand(ForgeCardId(2))
        }

        test("late deferred submission cannot mutate or resolve its successor window") {
            val bridge = GameActionBridge(timeoutMs = 5000)
            val firstResult = AtomicReference<PlayerAction?>()
            val firstEngineThread =
                Thread {
                    firstResult.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            firstEngineThread.isDaemon = true
            firstEngineThread.start()

            val firstPending = pollForPending(bridge).shouldNotBeNull()
            val play =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(14)
                    .build()
            val firstOffer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(1)))
            bridge.bindActionCatalog(firstPending.actionId, 12, listOf(firstOffer)) shouldBe true
            bridge.submitActionToken(firstPending.actionId, firstOffer.token) shouldBe true
            firstEngineThread.join(2000)
            firstResult.get() shouldBe PlayerAction.PlayLand(ForgeCardId(1))

            val secondResult = AtomicReference<PlayerAction?>()
            val secondEngineThread =
                Thread {
                    secondResult.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            secondEngineThread.isDaemon = true
            secondEngineThread.start()

            val secondPending = pollForPending(bridge).shouldNotBeNull()
            val secondOffer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(2)))
            bridge.bindActionCatalog(secondPending.actionId, 13, listOf(secondOffer)) shouldBe true
            val staleSideEffect = AtomicBoolean(false)

            assertSoftly {
                secondPending.actionId shouldNotBe firstPending.actionId
                bridge.submitActionToken(
                    firstPending.actionId,
                    firstOffer.token,
                    onAccepted = { staleSideEffect.set(true) },
                ) shouldBe false
                staleSideEffect.get() shouldBe false
                bridge.getPending()?.actionId shouldBe secondPending.actionId
                bridge.submitActionToken(secondPending.actionId, secondOffer.token) shouldBe true
            }
            secondEngineThread.join(2000)
            secondResult.get() shouldBe PlayerAction.PlayLand(ForgeCardId(2))
        }

        test("action registration and cancellation share one lifecycle transaction") {
            val registerEntered = CountDownLatch(1)
            val releaseRegister = CountDownLatch(1)
            val completionOrder = ConcurrentLinkedQueue<String>()
            val bridge =
                GameActionBridge(timeoutMs = null, prioritySignal = null) {
                    registerEntered.countDown()
                    releaseRegister.await(2, TimeUnit.SECONDS)
                    completionOrder.add("registered")
                    ActionToken("registered")
                }
            val engineThread =
                Thread {
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPending(bridge).shouldNotBeNull()
            val pass = Action.newBuilder().setActionType(ActionType.Pass).build()
            val entrants = Executors.newFixedThreadPool(2)
            val registration =
                entrants.submit<GameActionBridge.ActionOffer> {
                    bridge.registerActionOffer(pass, PlayerAction.PassPriority)
                }
            registerEntered.await(2, TimeUnit.SECONDS) shouldBe true
            val cancellation =
                entrants.submit {
                    bridge.cancelPending()
                    completionOrder.add("cancelled")
                }
            releaseRegister.countDown()
            val offer = registration.get(2, TimeUnit.SECONDS)
            cancellation.get(2, TimeUnit.SECONDS)
            engineThread.join(2000)
            entrants.shutdownNow()

            assertSoftly {
                completionOrder.toList() shouldBe listOf("registered", "cancelled")
                bridge.getPending().shouldBeNull()
                bridge.submitActionToken(pending.actionId, offer.token) shouldBe false
            }
        }

        test("accepted submission cannot lose its command to concurrent cleanup") {
            val bridge = GameActionBridge(timeoutMs = null)
            val result = AtomicReference<PlayerAction?>()
            val engineThread =
                Thread {
                    result.set(
                        bridge.awaitAction(
                            PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPending(bridge).shouldNotBeNull()
            val play =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Play_add3)
                    .setInstanceId(14)
                    .build()
            val offer = bridge.registerActionOffer(play, PlayerAction.PlayLand(ForgeCardId(7)))
            bridge.bindActionCatalog(pending.actionId, 12, listOf(offer)) shouldBe true
            val submissionAccepted = CountDownLatch(1)
            val releaseSubmission = CountDownLatch(1)
            val completionOrder = ConcurrentLinkedQueue<String>()
            val entrants = Executors.newFixedThreadPool(2)
            val submission =
                entrants.submit<Boolean> {
                    bridge
                        .submitActionToken(
                            pending.actionId,
                            offer.token,
                            onAccepted = {
                                submissionAccepted.countDown()
                                releaseSubmission.await(2, TimeUnit.SECONDS)
                                completionOrder.add("submitted")
                            },
                        )
                }
            submissionAccepted.await(2, TimeUnit.SECONDS) shouldBe true
            val cleanup =
                entrants.submit {
                    bridge.cancelPending()
                    completionOrder.add("cleanup")
                }
            releaseSubmission.countDown()
            cleanup.get(2, TimeUnit.SECONDS)

            assertSoftly {
                submission.get(2, TimeUnit.SECONDS) shouldBe true
                completionOrder.toList() shouldBe listOf("submitted", "cleanup")
            }
            engineThread.join(2000)
            entrants.shutdownNow()
            result.get() shouldBe PlayerAction.PlayLand(ForgeCardId(7))
        }

        test("a failed priority wait clears its action tokens") {
            val bridge = GameActionBridge(timeoutMs = 250)
            val engineThread =
                Thread {
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPending(bridge).shouldNotBeNull()
            val pass = Action.newBuilder().setActionType(ActionType.Pass).build()
            val offer = bridge.registerActionOffer(pass, PlayerAction.PassPriority)
            bridge.bindActionCatalog(pending.actionId, 12, listOf(offer)) shouldBe true
            engineThread.join(2000)

            assertSoftly {
                bridge.getPending().shouldBeNull()
                bridge.submitActionToken(pending.actionId, offer.token) shouldBe false
            }
        }
    })
