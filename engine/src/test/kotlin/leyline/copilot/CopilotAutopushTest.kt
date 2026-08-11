package leyline.copilot

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Push path end to end without the client: stand up a stub bridge HTTP server,
 * point [CopilotAutopush] at it, and feed it a real prompt from a live harness
 * game. Asserts the copilot computes the response on the LIVE game (no snapshot)
 * and POSTs response bytes to the bridge.
 */
@Suppress("SleepInsteadOfDelay", "NoThreadSleepInTests")
class CopilotAutopushTest :
    SessionTest({

        test("autopush applies a pass on ActionsAvailableReq server-side (no client inject)") {
            // A pass on an empty priority window is invisible; autopush must apply it
            // directly to the action bridge and kick the async drive, NOT round-trip
            // through the client — otherwise rapid phase bursts (a castable instant
            // makes every phase stop) outrun the HTTP injector and the game-loop parks.
            val injectCount = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond") { ex ->
                injectCount.incrementAndGet()
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.close()
            }
            server.executor = null
            server.start()
            val url = "http://127.0.0.1:${server.address.port}"

            try {
                startPuzzleRaw(
                    """
                    [metadata]
                    Name:Autopush Pass
                    Goal:Win
                    Turns:5

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Lightning Bolt
                    humanbattlefield=Mountain
                    humanlibrary=Mountain;Mountain;Mountain
                    ailibrary=Mountain;Mountain;Mountain
                    """.trimIndent(),
                )
                // The last priority window the copilot faces here is a pass (it holds
                // the burn rather than casting it), which is exactly the empty-window
                // case the server-side path must handle.
                val aar = allMessages.last { it.type == GREMessageType.ActionsAvailableReq_695e }
                val drove = CountDownLatch(1)
                harness.bridge.autoAdvanceRequester = { drove.countDown() }

                val autopush = CopilotAutopush(harness.bridge, SeatId(1), url)
                autopush.onPrompt(aar)

                // Server-side path: the async drive was kicked and no response inject happened.
                drove.await(10, TimeUnit.SECONDS).shouldBeTrue()
                Thread.sleep(500) // allow any (incorrect) client inject to arrive
                autopush.shutdown()
                injectCount.get() shouldBe 0
            } finally {
                server.stop(0)
            }
        }

        test("autopush skips a prompt already superseded at dequeue (burst coalescing)") {
            // During a prompt burst the single-thread exec backs up; a prompt that
            // is already superseded when dequeued can never land (stale respId), so
            // autopush must skip it outright rather than burn re-injects that let
            // the queue back up further.
            val injectCount = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond") { ex ->
                injectCount.incrementAndGet()
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.close()
            }
            server.executor = null
            server.start()
            val url = "http://127.0.0.1:${server.address.port}"

            try {
                startPuzzleRaw(
                    """
                    [metadata]
                    Name:Autopush Coalesce
                    Goal:Win
                    Turns:5

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Lightning Bolt
                    humanbattlefield=Mountain
                    humanlibrary=Mountain;Mountain;Mountain
                    ailibrary=Mountain;Mountain;Mountain
                    """.trimIndent(),
                )
                val aar = allMessages.last { it.type == GREMessageType.ActionsAvailableReq_695e }
                // Simulate a newer prompt having already fired: advance the prompt
                // horizon past this prompt's msgId.
                harness.bridge.messageCounter.markPromptMsgId(aar.msgId + 1)

                val autopush = CopilotAutopush(harness.bridge, SeatId(1), url)
                autopush.onPrompt(aar)
                Thread.sleep(1_000) // give the push thread room to (not) inject
                autopush.shutdown()

                injectCount.get() shouldBe 0
            } finally {
                server.stop(0)
            }
        }

        test("autopush re-injects a dropped ACTION response and stops once the host accepts it") {
            // Uses a creature cast (a real, visible action) so autopush takes the
            // client-inject path (server-side pass only applies to ActionsAvailableReq
            // passes). Bridge swallows the first inject and, on the
            // second, marks a response accepted — as the host would.
            val injectCount = AtomicInteger(0)
            val landedLatch = CountDownLatch(1)
            val onSecondInject = AtomicReference<() -> Unit> {}
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond") { ex ->
                val n = injectCount.incrementAndGet()
                if (n >= 2) {
                    onSecondInject.get().invoke()
                    landedLatch.countDown()
                }
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.close()
            }
            server.executor = null
            server.start()
            val url = "http://127.0.0.1:${server.address.port}"

            try {
                startPuzzleRaw(
                    """
                    [metadata]
                    Name:Autopush Reinject
                    Goal:Win
                    Turns:5

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Grizzly Bears
                    humanbattlefield=Forest;Forest
                    humanlibrary=Forest;Forest;Forest
                    ailibrary=Forest;Forest;Forest
                    """.trimIndent(),
                )
                // A MulliganReq deterministically injects (KeepHand) and is NOT an
                // ActionsAvailableReq, so the server-side-pass path never applies —
                // isolating the inject self-heal from any AI decision. msgId far ahead
                // of any real prompt so it is neither coalesced (superseded-at-dequeue)
                // nor read as superseded mid-flight.
                val mulliganReq =
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.MulliganReq_aa0d)
                        .setMsgId(999_999)
                        .setGameStateId(harness.bridge.messageCounter.currentGsId())
                        .setMulliganReq(Messages.MulliganReq.getDefaultInstance())
                        .build()
                onSecondInject.set { harness.bridge.messageCounter.markResponseAccepted() }

                val autopush = CopilotAutopush(harness.bridge, SeatId(1), url)
                autopush.onPrompt(mulliganReq)

                landedLatch.await(10, TimeUnit.SECONDS).shouldBeTrue()
                // Give the push loop room to (not) fire a third inject.
                Thread.sleep(1_000)
                autopush.shutdown()

                // Dropped once, landed on the retry, then stopped — no wasted re-injects.
                injectCount.get() shouldBe 2
            } finally {
                server.stop(0)
            }
        }

        test("landed() requires the priority window to advance, not just an envelope-accept bump") {
            // The wedge's core: responsesAccepted() bumps when a response clears
            // the envelope guard (respId match), NOT when it is applied. For an AAR
            // inject the copilot must anchor to the pending action id — a bare
            // counter bump while the same window stays parked is NOT landed, so the
            // copilot keeps re-injecting instead of stopping and leaving the
            // game-loop stuck in awaitAction.
            startPuzzleRaw(GRIZZLY_CAST_PUZZLE)
            val autopush = CopilotAutopush(harness.bridge, SeatId(1), "http://127.0.0.1:1")
            try {
                val seatAction = harness.bridge.seat(SeatId(1)).action
                val pending = seatAction.getPending() ?: error("expected a pending priority window after puzzle start")
                val baseline = harness.bridge.messageCounter.responsesAccepted()

                // Envelope accepted but the window has not advanced → NOT landed.
                harness.bridge.messageCounter.markResponseAccepted()
                autopush.landed(baseline, pending.actionId) shouldBe false

                // A non-AAR prompt has no window anchor and falls back to the counter.
                autopush.landed(baseline, null) shouldBe true

                // The window advances (future completes) → landed.
                seatAction.submitAction(pending.actionId, PlayerAction.PassPriority)
                autopush.landed(baseline, pending.actionId) shouldBe true
            } finally {
                autopush.shutdown()
            }
        }
    }) {
    private companion object {
        // A pending priority window is parked at Human Main1 after this puzzle
        // starts, giving [CopilotAutopush.landed] a real action-bridge anchor.
        val GRIZZLY_CAST_PUZZLE =
            """
            [metadata]
            Name:Autopush AAR Cast
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Grizzly Bears
            humanbattlefield=Forest;Forest
            humanlibrary=Forest;Forest;Forest
            ailibrary=Forest;Forest;Forest
            """.trimIndent()
    }
}
