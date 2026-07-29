package leyline.bridge.handoff

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import java.util.concurrent.atomic.AtomicReference

/** Deadlined poll for an engine-thread pending — sleep is poll interval, not a race. */
@Suppress("NoThreadSleepInTests")
private fun pollForPreparedPending(bridge: GameActionBridge): GameActionBridge.PendingAction? {
    repeat(50) {
        bridge.getPending()?.let { return it }
        Thread.sleep(10)
    }
    return null
}

class PriorityActionPreparationLifecycleTest :
    FunSpec({

        tags(UnitTag)

        test("prepared command batch replaces one window atomically and preserves command tokens") {
            var nextToken = 0
            val bridge =
                GameActionBridge(timeoutMs = null, prioritySignal = null) {
                    ActionToken("prepared-${++nextToken}")
                }
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

            val pending = pollForPreparedPending(bridge).shouldNotBeNull()
            val play = PlayerAction.PlayLand(ForgeCardId(7))
            val stale = bridge.prepareActionTokens("stale-window", listOf(play))
            val initial =
                bridge
                    .prepareActionTokens(
                        pending.actionId,
                        listOf(PlayerAction.PassPriority, play, PlayerAction.PassPriority),
                    ).shouldNotBeNull()
            val replacement =
                bridge
                    .prepareActionTokens(
                        pending.actionId,
                        listOf(play),
                    ).shouldNotBeNull()
            val accepted = bridge.submitActionToken(pending.actionId, replacement.single())
            engineThread.join(2000)

            assertSoftly {
                stale.shouldBeNull()
                initial.map(ActionToken::value) shouldBe listOf("prepared-1", "prepared-2", "prepared-1")
                replacement.map(ActionToken::value) shouldBe listOf("prepared-2")
                bridge.acceptsActionToken(pending.actionId, initial.first()) shouldBe false
                accepted shouldBe true
                result.get() shouldBe play
            }
        }

        test("prepared command batch is rejected for combat windows") {
            val bridge = GameActionBridge(timeoutMs = null)
            val engineThread =
                Thread {
                    bridge.awaitAction(
                        PendingActionState(
                            phase = "Combat",
                            turn = 1,
                            activePlayerId = 1,
                            priorityPlayerId = 1,
                            kind = PendingActionKind.DECLARE_ATTACKERS,
                        ),
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()

            val pending = pollForPreparedPending(bridge).shouldNotBeNull()
            val prepared = bridge.prepareActionTokens(pending.actionId, listOf(PlayerAction.PassPriority))
            val submitted = bridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            engineThread.join(2000)

            assertSoftly {
                prepared.shouldBeNull()
                submitted shouldBe true
            }
        }

        test("failed command-batch preparation retires the pending window") {
            var calls = 0
            val bridge =
                GameActionBridge(timeoutMs = null, prioritySignal = null) {
                    calls += 1
                    if (calls == 2) error("token preparation failed")
                    ActionToken("prepared-$calls")
                }
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

            val pending = pollForPreparedPending(bridge).shouldNotBeNull()
            shouldThrow<IllegalStateException> {
                bridge.prepareActionTokens(
                    pending.actionId,
                    listOf(PlayerAction.PassPriority, PlayerAction.PlayLand(ForgeCardId(7))),
                )
            }
            engineThread.join(2000)
            assertSoftly {
                bridge.getPending().shouldBeNull()
                result.get() shouldBe PlayerAction.PassPriority
            }
        }

        test("before-wake preparation failure retires the pending window") {
            val signal = PrioritySignal()
            signal.observeBeforeWake { error("readiness preparation failed") }
            val bridge = GameActionBridge(timeoutMs = null, prioritySignal = signal)

            assertSoftly {
                shouldThrow<IllegalStateException> {
                    bridge.awaitAction(
                        PendingActionState(phase = "Main1", turn = 1, activePlayerId = 1, priorityPlayerId = 1),
                    )
                }
                bridge.getPending().shouldBeNull()
            }
        }
    })
