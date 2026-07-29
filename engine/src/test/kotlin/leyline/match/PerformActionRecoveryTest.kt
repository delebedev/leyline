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
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Regression: stale duplicate PerformActionResp packets can arrive after the
 * original action already consumed the pending bridge action. Recovery must
 * resync the client with a single state-only bundle, not expose unbound actions or re-enter the
 * auto-pass loop (which would spin through phases and emit many messages).
 */
class PerformActionRecoveryTest :
    BoardTest({

        test("missing pending action emits exactly one state-only resync bundle") {
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

            session.connection.owner.reduce {
                session.connection.owner.observeEngine(
                    bridge.materializeEngineObservation(checkNotNull(bridge.getGame())),
                )
                session.connection.owner.observeOutbound(
                    listOf(
                        GREToClientMessage
                            .newBuilder()
                            .setType(GREMessageType.ActionsAvailableReq_695e)
                            .setGameStateId(1)
                            .setMsgId(7)
                            .build(),
                    ),
                )
            }
            session.onPerformAction(
                performAction { actionType = wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass }
                    .toBuilder()
                    .setRespId(7)
                    .build(),
            )

            // The state-only resync emits content + echo GSMs and no action request.
            // autoPassAndAdvance would iterate phases, emitting multiple bundles.
            val gsms = sink.messages.filter { it.hasGameStateMessage() }
            val aarCount = sink.messages.count { it.hasActionsAvailableReq() }
            val content = gsms.first().gameStateMessage
            assertSoftly {
                gsms.size shouldBe 2
                aarCount shouldBe 0
                content.pendingMessageCount shouldBe 0
            }
        }
    })
