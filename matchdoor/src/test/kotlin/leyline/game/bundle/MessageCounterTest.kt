package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Pure unit tests for [MessageCounter] — gsId/msgId sequencing and
 * [MessageCounter.lastPromptGsId] horizon tracking that backs the staleness
 * checks in [leyline.match.ActionPerformer] and
 * [leyline.match.CombatHandler].
 */
class MessageCounterTest :
    FunSpec({

        test("nextGsId returns monotonically increasing values") {
            val c = MessageCounter()
            c.nextGsId() shouldBe 1
            c.nextGsId() shouldBe 2
            c.nextGsId() shouldBe 3
        }

        test("currentGsId reflects the latest nextGsId") {
            val c = MessageCounter()
            c.currentGsId() shouldBe 0
            c.nextGsId()
            c.nextGsId()
            c.currentGsId() shouldBe 2
        }

        test("lastPromptGsId starts at 0") {
            val c = MessageCounter()
            c.lastPromptGsId() shouldBe 0
        }

        test("markPromptGsId moves the horizon forward") {
            val c = MessageCounter()
            c.markPromptGsId(5)
            c.lastPromptGsId() shouldBe 5
            c.markPromptGsId(7)
            c.lastPromptGsId() shouldBe 7
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
            c.lastPromptGsId() shouldBe 0
            c.currentGsId() shouldBe 2
        }

        test("markIfPrompt bumps the horizon for prompt-bearing GRE types") {
            val c = MessageCounter()
            markIfPrompt(c, GREMessageType.ActionsAvailableReq_695e, 5)
            c.lastPromptGsId() shouldBe 5

            markIfPrompt(c, GREMessageType.SelectTargetsReq_695e, 7)
            c.lastPromptGsId() shouldBe 7

            markIfPrompt(c, GREMessageType.DeclareAttackersReq_695e, 9)
            c.lastPromptGsId() shouldBe 9
        }

        test("markIfPrompt is a no-op for non-prompt GRE types") {
            val c = MessageCounter()
            markIfPrompt(c, GREMessageType.GameStateMessage_695e, 5)
            c.lastPromptGsId() shouldBe 0

            markIfPrompt(c, GREMessageType.QueuedGameStateMessage, 7)
            c.lastPromptGsId() shouldBe 0
        }

        test("markIfPrompt covers the full PROMPT_GRE_TYPES set") {
            for (type in PROMPT_GRE_TYPES) {
                val c = MessageCounter()
                markIfPrompt(c, type, 42)
                c.lastPromptGsId() shouldBe 42
            }
        }
    })
