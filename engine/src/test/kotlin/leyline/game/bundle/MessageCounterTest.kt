package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

/** Pure unit tests for shared gsId/msgId allocation and GSM linkage. */
class MessageCounterTest :
    FunSpec({

        tags(UnitTag)

        test("nextGsId returns monotonically increasing values") {
            val c = MessageCounter()
            assertSoftly {
                c.nextGsId() shouldBe 1
                c.nextGsId() shouldBe 2
                c.nextGsId() shouldBe 3
            }
        }

        test("currentGsId reflects the latest nextGsId") {
            val c = MessageCounter()
            assertSoftly {
                c.currentGsId() shouldBe 0
                c.nextGsId()
                c.nextGsId()
                c.currentGsId() shouldBe 2
            }
        }

        test("lastGameStateGsId tracks only emitted game-state frames") {
            val c = MessageCounter()
            c.nextGsId()
            c.nextGsId()
            assertSoftly {
                c.lastGameStateGsId() shouldBe 0
                c.currentGsId() shouldBe 2
            }

            c.markGameStateGsId(1)
            c.markGameStateGsId(3)
            c.markGameStateGsId(2)

            c.lastGameStateGsId() shouldBe 3
        }

        test("nextGameStateLink allocates gsId with a lower predecessor") {
            val c = MessageCounter(initialGsId = 10)
            val link = c.nextGameStateLink()

            assertSoftly {
                link.gsId shouldBe 11
                link.prevGsId shouldBe 10
                c.currentGsId() shouldBe 11
            }
        }

        test("nextGameStateLink prefers last emitted GameStateMessage") {
            val c = MessageCounter(initialGsId = 20)
            c.markGameStateGsId(12)
            val link = c.nextGameStateLink()

            assertSoftly {
                link.gsId shouldBe 21
                link.prevGsId shouldBe 12
            }
        }

        test("snapshot exposes allocation and game-state linkage") {
            val c = MessageCounter(initialGsId = 2, initialMsgId = 5)
            c.markGameStateGsId(4)

            c.snapshot() shouldBe
                MessageCounter.Snapshot(
                    currentGsId = 2,
                    currentMsgId = 5,
                    lastGameStateGsId = 4,
                )
        }
    })
