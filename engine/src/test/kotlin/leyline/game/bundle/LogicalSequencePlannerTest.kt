package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Pure unit tests for tentative [LogicalSequencePlanner] allocation and horizons.
 */
class LogicalSequencePlannerTest :
    FunSpec({

        tags(UnitTag)

        test("nextGsId returns monotonically increasing values") {
            val c = LogicalSequencePlanner()
            assertSoftly {
                c.nextGsId() shouldBe 1
                c.nextGsId() shouldBe 2
                c.nextGsId() shouldBe 3
            }
        }

        test("currentGsId reflects the latest nextGsId") {
            val c = LogicalSequencePlanner()
            assertSoftly {
                c.currentGsId() shouldBe 0
                c.nextGsId()
                c.nextGsId()
                c.currentGsId() shouldBe 2
            }
        }

        test("nextGameStateLink allocates gsId with a lower predecessor") {
            val c = LogicalSequencePlanner(initialGsId = 10)
            val link = c.nextGameStateLink()

            assertSoftly {
                link.gsId shouldBe 11
                link.prevGsId shouldBe 10
                c.currentGsId() shouldBe 11
            }
        }

        test("nextGameStateLink prefers last emitted GameStateMessage") {
            val c = LogicalSequencePlanner(LogicalSequenceState(currentGsId = 20, lastGameStateGsId = 12))
            val link = c.nextGameStateLink()

            assertSoftly {
                link.gsId shouldBe 21
                link.prevGsId shouldBe 12
            }
        }

        test("a discarded planner consumes no committed identifiers or output order") {
            val committed = LogicalSequenceState(currentGsId = 3, currentMsgId = 7, committedOutputOrdinal = 4)
            val abandoned = LogicalSequencePlanner(committed)

            abandoned.nextGsId()
            abandoned.nextMsgId()
            abandoned.allocateOutputOrdinal()

            committed shouldBe LogicalSequenceState(currentGsId = 3, currentMsgId = 7, committedOutputOrdinal = 4)
        }

        test("observed output advances horizons monotonically") {
            val planner = LogicalSequencePlanner(LogicalSequenceState(committedOutputOrdinal = 9))
            planner.observe(
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ActionsAvailableReq_695e)
                    .setGameStateId(8)
                    .setMsgId(12)
                    .build(),
            )
            planner.observe(
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.GameStateMessage_695e)
                    .setGameStateMessage(GameStateMessage.newBuilder().setGameStateId(7))
                    .build(),
            )
            planner.observe(
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ActionsAvailableReq_695e)
                    .setGameStateId(2)
                    .setMsgId(4)
                    .build(),
            )

            assertSoftly {
                planner.allocateOutputOrdinal() shouldBe 10
                planner.snapshot().lastPromptGsId shouldBe 8
                planner.snapshot().lastPromptMsgId shouldBe 12
                planner.lastGameStateGsId() shouldBe 7
            }
        }
    })
