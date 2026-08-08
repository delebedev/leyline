package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            assertSoftly {
                bridge.acceptsResponse(pending, 12) shouldBe false
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(
                        GameActionBridge.ActionOffer(Action.newBuilder().setActionType(ActionType.Pass).build(), PlayerAction.PassPriority),
                    ),
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
            bridge.bindActionCatalog(pending.actionId, 12, listOf(GameActionBridge.ActionOffer(cast, PlayerAction.PassPriority))) shouldBe
                true
            val response = cast.toBuilder().addManaCost(ManaRequirement.newBuilder().setCount(1)).build()
            assertSoftly {
                bridge.resolveOfferedAction(pending, 12, response)?.command shouldBe PlayerAction.PassPriority
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
            assertSoftly {
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(
                        GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority),
                        GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority),
                    ),
                ) shouldBe true
                bridge.bindActionCatalog(
                    pending.actionId,
                    12,
                    listOf(GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority)),
                ) shouldBe
                    true
                bridge.bindActionCatalog(
                    pending.actionId,
                    13,
                    listOf(GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority)),
                ) shouldBe
                    true
                bridge.resolveOfferedAction(pending, 12, pass).shouldBeNull()
                bridge.resolveOfferedAction(pending, 13, pass)?.command shouldBe PlayerAction.PassPriority
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
            bridge.bindActionCatalog(
                pending.actionId,
                12,
                listOf(
                    GameActionBridge.ActionOffer(first, PlayerAction.PlayLand(ForgeCardId(1))),
                    GameActionBridge.ActionOffer(second, PlayerAction.PlayLand(ForgeCardId(2))),
                ),
            ) shouldBe true

            assertSoftly {
                bridge.resolveOfferedAction(pending, 12, first)?.command shouldBe PlayerAction.PlayLand(ForgeCardId(1))
                bridge.resolveOfferedAction(pending, 12, second)?.command shouldBe PlayerAction.PlayLand(ForgeCardId(2))
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
                offers = listOf(GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority)),
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
            bridge.bindActionCatalog(
                pending.actionId,
                12,
                listOf(
                    GameActionBridge.ActionOffer(play, PlayerAction.PlayLand(ForgeCardId(1))),
                    GameActionBridge.ActionOffer(play, PlayerAction.PlayLand(ForgeCardId(2))),
                ),
            ) shouldBe false

            bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)
        }
    })
