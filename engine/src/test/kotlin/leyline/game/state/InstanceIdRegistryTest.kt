package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.game.state.InstanceIdRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Unit tests for [leyline.game.state.InstanceIdRegistry]. */
class InstanceIdRegistryTest :
    FunSpec({

        tags(UnitTag)

        test("resetAll returns old IDs and clears state") {
            val reg = InstanceIdRegistry(startId = 100)
            val id1 = reg.getOrAlloc(ForgeCardId(1))
            val id2 = reg.getOrAlloc(ForgeCardId(2))
            val id3 = reg.getOrAlloc(ForgeCardId(3))

            val deleted = reg.resetAll()

            assertSoftly {
                deleted.shouldContainExactlyInAnyOrder(id1, id2, id3)
                reg.getOrAlloc(ForgeCardId(1)) shouldNotBe id1
                reg.getOrAlloc(ForgeCardId(2)) shouldNotBe id2
            }
        }

        test("resetAll clears reverse map") {
            val reg = InstanceIdRegistry(startId = 100)
            val oldId = reg.getOrAlloc(ForgeCardId(42))

            reg.resetAll()

            // Old reverse lookup should return null
            reg.getForgeCardId(oldId).shouldBeNull()
            // New allocation should be resolvable
            val newId = reg.getOrAlloc(ForgeCardId(42))
            reg.getForgeCardId(newId).shouldNotBeNull()
        }

        test("concurrent first allocations get unique instanceIds") {
            val reg = InstanceIdRegistry(startId = 100)
            val executor = Executors.newFixedThreadPool(8)
            val ids = ConcurrentHashMap.newKeySet<Int>()
            val taskCount = 1_000

            repeat(taskCount) { index ->
                executor.submit {
                    ids += reg.getOrAlloc(ForgeCardId(index + 1)).value
                }
            }

            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
            ids.size shouldBe taskCount
        }

        test("tentative allocations leave committed identity state unchanged") {
            val reg = InstanceIdRegistry(startId = 100)
            val before = reg.committedState()

            val tentative =
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1))
                    reg.reserveNextInstanceId()
                    reg.getOrAlloc(ForgeCardId(2))
                    reg.allocSynthetic()
                    reg.tentativeTransition()
                }

            reg.committedState() shouldBe before

            val replay =
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1))
                    reg.reserveNextInstanceId()
                    reg.getOrAlloc(ForgeCardId(2))
                    reg.allocSynthetic()
                    reg.tentativeTransition()
                }
            replay.nextState shouldBe tentative.nextState
        }

        test("discard then commit advances identity allocation exactly once") {
            val reg = InstanceIdRegistry(startId = 100)

            reg.withTentativeState {
                reg.getOrAlloc(ForgeCardId(1))
                reg.tentativeTransition()
            }
            val committedPlan =
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1))
                    reg.tentativeTransition()
                }

            assertSoftly {
                reg.commit(committedPlan) shouldBe true
                reg.getOrAlloc(ForgeCardId(1)).value shouldBe 100
                reg.getOrAlloc(ForgeCardId(2)).value shouldBe 101
                reg.committedState().state.nextInstanceId shouldBe 102
            }
        }

        test("failed tentative compilation leaves identity state unchanged") {
            val reg = InstanceIdRegistry(startId = 100)
            val before = reg.committedState()

            shouldThrow<IllegalStateException> {
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1))
                    error("compile failed")
                }
            }

            reg.committedState() shouldBe before
        }

        test("stale transition rejects interleaved allocation without collision") {
            val reg = InstanceIdRegistry(startId = 100)
            val planned =
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1))
                    reg.tentativeTransition()
                }

            assertSoftly {
                reg.getOrAlloc(ForgeCardId(2)).value shouldBe 100
                reg.commit(planned) shouldBe false
                reg.peek(ForgeCardId(1)) shouldBe null
            }

            val retry =
                reg.withTentativeState {
                    reg.getOrAlloc(ForgeCardId(1)).value shouldBe 101
                    reg.tentativeTransition()
                }
            reg.commit(retry) shouldBe true
            reg.getOrAlloc(ForgeCardId(3)).value shouldBe 102
        }
    })
