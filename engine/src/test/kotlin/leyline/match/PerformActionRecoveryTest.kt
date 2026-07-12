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
 * original action already consumed the pending bridge action. Recovery must
 * resync the client with a single postAction bundle, not re-enter the
 * auto-pass loop (which would spin through phases and emit many messages).
 */
class PerformActionRecoveryTest :
    BoardTest({

        test("missing pending action emits exactly one postAction resync bundle") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }

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

            session.onPerformAction(performAction { actionType = wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass })

            // postAction resync emits one content GSM + one AAR. autoPassAndAdvance
            // would iterate phases, emitting multiple GSM/AAR pairs.
            val gsms = sink.messages.filter { it.hasGameStateMessage() }
            val aarCount = sink.messages.count { it.hasActionsAvailableReq() }
            val content = gsms[0].gameStateMessage
            assertSoftly {
                gsms.size shouldBe 1
                aarCount shouldBe 1
                content.pendingMessageCount shouldBe 1
            }
        }
    })
