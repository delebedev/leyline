package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Pure unit tests for [MessageCounter] — gsId/msgId sequencing and
 * [MessageCounter.lastPromptGsId] horizon tracking that backs the staleness
 * checks in [leyline.match.ActionPerformer] and
 * [leyline.match.CombatHandler].
 */
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

        test("lastPromptGsId starts at 0") {
            val c = MessageCounter()
            c.lastPromptGsId() shouldBe 0
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

        test("snapshot exposes counter and horizon state") {
            val c = MessageCounter(initialGsId = 2, initialMsgId = 5)
            c.markPromptGsId(7)
            c.markPromptMsgId(9)
            c.markGameStateGsId(4)

            c.snapshot() shouldBe
                MessageCounter.Snapshot(
                    currentGsId = 2,
                    currentMsgId = 5,
                    lastPromptGsId = 7,
                    lastPromptMsgId = 9,
                    lastGameStateGsId = 4,
                )
        }

        test("markPromptGsId moves the horizon forward") {
            val c = MessageCounter()
            assertSoftly {
                c.markPromptGsId(5)
                c.lastPromptGsId() shouldBe 5
                c.markPromptGsId(7)
                c.lastPromptGsId() shouldBe 7
            }
        }

        test("markPromptGsId is monotonic — earlier values do not regress the horizon") {
            val c = MessageCounter()
            c.markPromptGsId(10)
            c.markPromptGsId(3)
            c.lastPromptGsId() shouldBe 10
        }

        test("markPromptGsId with equal value is a no-op") {
            val c = MessageCounter()
            c.markPromptGsId(5)
            c.markPromptGsId(5)
            c.lastPromptGsId() shouldBe 5
        }

        test("lastPromptGsId is independent of currentGsId — counter advances do not bump the horizon") {
            val c = MessageCounter()
            c.nextGsId()
            c.nextGsId()
            assertSoftly {
                c.lastPromptGsId() shouldBe 0
                c.currentGsId() shouldBe 2
            }
        }

        test("markIfPrompt bumps the horizon for prompt-bearing GRE types") {
            val c = MessageCounter()
            assertSoftly {
                markIfPrompt(c, GREMessageType.ActionsAvailableReq_695e, 5, 11)
                c.lastPromptGsId() shouldBe 5
                c.lastPromptMsgId() shouldBe 11

                markIfPrompt(c, GREMessageType.SelectTargetsReq_695e, 7, 13)
                c.lastPromptGsId() shouldBe 7
                c.lastPromptMsgId() shouldBe 13

                markIfPrompt(c, GREMessageType.DeclareAttackersReq_695e, 9, 15)
                c.lastPromptGsId() shouldBe 9
                c.lastPromptMsgId() shouldBe 15
            }
        }

        test("markIfPrompt is a no-op for non-prompt GRE types") {
            val c = MessageCounter()
            assertSoftly {
                markIfPrompt(c, GREMessageType.GameStateMessage_695e, 5, 11)
                c.lastPromptGsId() shouldBe 0
                c.lastPromptMsgId() shouldBe 0

                markIfPrompt(c, GREMessageType.QueuedGameStateMessage, 7, 13)
                c.lastPromptGsId() shouldBe 0
            }
        }

        test("markIfPrompt covers the full PROMPT_GRE_TYPES set") {
            for (type in PROMPT_GRE_TYPES) {
                val c = MessageCounter()
                markIfPrompt(c, type, 42, 84)
                c.lastPromptGsId() shouldBe 42
                c.lastPromptMsgId() shouldBe 84
            }
        }
    })
