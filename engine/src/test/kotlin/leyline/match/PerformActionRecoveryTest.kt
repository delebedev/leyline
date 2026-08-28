package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.testkit.BoardTest
import leyline.testkit.performAction

/**
 * Regression: stale duplicate PerformActionResp packets can arrive after the
 * original action already consumed the pending bridge action. Recovery drains
 * the coordinator's committed state-only bundle, without exposing unbound actions or re-entering the
 * runtime continuation (which would otherwise spin through phases and emit many messages).
 */
class PerformActionRecoveryTest :
    BoardTest({

        test("missing pending action drains exactly one committed state-only resync bundle") {
            val board = startWithBoard { _, _, _ -> }
            val bridge = board.bridge

            val sink = ListMessageSink()
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "test-missing-pending",
                            sink = sink,
                            registry = MatchRegistry(),
                        ),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )

            bridge.cutCoordinator.registerViewer(SeatId(1))
            val committed = board.bundleBuilder().stateOnlyDiff(board.game, board.counter).messages
            bridge.cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), committed)
            val committedRevision = bridge.projectionStateSnapshot().revision
            val nextGameStateId = board.counter.currentGsId()

            session.counter.markPromptMsgId(7)
            session.onPerformAction(
                performAction { actionType = wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass }
                    .toBuilder()
                    .setRespId(7)
                    .build(),
            )

            // The state-only resync emits content + echo GSMs and no action request.
            // Runtime continuation emits only the committed state-only bundle.
            val gsms = sink.messages.filter { it.hasGameStateMessage() }
            val aarCount = sink.messages.count { it.hasActionsAvailableReq() }
            val content = gsms.first().gameStateMessage
            assertSoftly {
                gsms.size shouldBe 2
                aarCount shouldBe 0
                content.pendingMessageCount shouldBe 0
                bridge.cutCoordinator.hasCommittedBatches(SeatId(1)) shouldBe false
                bridge.projectionStateSnapshot().revision shouldBe committedRevision
                board.counter.currentGsId() shouldBe nextGameStateId
            }
        }
    })
