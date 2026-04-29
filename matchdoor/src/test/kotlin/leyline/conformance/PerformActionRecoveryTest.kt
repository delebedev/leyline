package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.types.SeatId
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession

/**
 * Regression: stale duplicate PerformActionResp packets can arrive after the
 * original action already consumed the pending bridge action. Recovery must
 * resync the client with a single postAction bundle, not re-enter the
 * auto-pass loop (which would spin through phases and emit many messages).
 */
class PerformActionRecoveryTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("missing pending action emits exactly one postAction resync bundle") {
            val (bridge, _, _) = base.startWithBoard { _, _, _ -> }

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

            // postAction emits exactly one GSM + one AAR (plus optional timer start).
            // autoPassAndAdvance would iterate phases, emitting multiple GSM/AAR pairs.
            val gsmCount = sink.messages.count { it.hasGameStateMessage() }
            val aarCount = sink.messages.count { it.hasActionsAvailableReq() }
            gsmCount shouldBe 1
            aarCount shouldBe 1
        }
    })
