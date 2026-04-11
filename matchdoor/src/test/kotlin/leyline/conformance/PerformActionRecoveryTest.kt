package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import leyline.ConformanceTag
import leyline.infra.ListMessageSink
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import kotlin.system.measureTimeMillis

/**
 * Regression: stale duplicate PerformActionResp packets can arrive after the
 * original action already consumed the pending bridge action. Recovery must
 * resync the client, not re-enter auto-pass.
 */
class PerformActionRecoveryTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("missing pending action resyncs immediately instead of entering auto-pass loop") {
            val (bridge, _, _) = base.startWithBoard { _, _, _ -> }
            bridge.priorityWaitMs = 20L

            val sink = ListMessageSink()
            val session = MatchSession(
                seatId = leyline.bridge.SeatId(1),
                matchId = "test-missing-pending",
                sink = sink,
                registry = MatchRegistry(),
                paceDelayMs = 0,
            )
            session.connectBridge(bridge)

            val durationMs = measureTimeMillis {
                session.onPerformAction(performAction { actionType = wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass })
            }

            (durationMs < 200).shouldBeTrue()
            sink.messages.any { it.hasGameStateMessage() }.shouldBeTrue()
            sink.messages.size shouldBeLessThan 4
        }
    })
