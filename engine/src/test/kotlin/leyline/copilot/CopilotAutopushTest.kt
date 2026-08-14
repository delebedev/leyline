package leyline.copilot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Client-native push transport exercised against a live session prompt. */
@Suppress("SleepInsteadOfDelay", "NoThreadSleepInTests")
class CopilotAutopushTest :
    SessionTest({
        test("autopush waits for the exact request and submits pass through the client workflow") {
            val statusCount = AtomicInteger(0)
            val submitCount = AtomicInteger(0)
            val rawCount = AtomicInteger(0)
            val statusBody = AtomicReference("")
            val submitBody = AtomicReference("")
            val onSubmit = AtomicReference<() -> Unit>({})
            val submitted = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond/native/status") { exchange ->
                statusBody.set(exchange.readBody())
                val status = if (statusCount.incrementAndGet() == 1) "no_request" else "ready"
                exchange.respond("""{"status":"$status"}""")
            }
            server.createContext("/respond/native") { exchange ->
                submitCount.incrementAndGet()
                submitBody.set(exchange.readBody())
                onSubmit.get().invoke()
                submitted.countDown()
                exchange.respond("""{"status":"submitted"}""")
            }
            server.createContext("/respond") { exchange ->
                rawCount.incrementAndGet()
                exchange.respond("ok")
            }
            server.start()

            try {
                startPuzzleRaw(PASS_PUZZLE)
                val prompt = allMessages.last { it.type == GREMessageType.ActionsAvailableReq_695e }
                val actionBridge = harness.bridge.seat(SeatId(1)).action
                val pending = actionBridge.getPending() ?: error("expected a pending priority action")
                onSubmit.set {
                    harness.bridge.messageCounter.markResponseAccepted()
                    actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                }

                val autopush =
                    CopilotAutopush(
                        harness.bridge,
                        SeatId(1),
                        "http://127.0.0.1:${server.address.port}",
                        nativeReadyPollMs = 0,
                        nativeReadyChecks = 3,
                        landPollMs = 1,
                        landPollChecks = 20,
                    )
                autopush.onPrompt(prompt)
                submitted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                Thread.sleep(100)
                autopush.shutdown()

                assertSoftly {
                    statusCount.get() shouldBeExactly 2
                    submitCount.get() shouldBeExactly 1
                    rawCount.get() shouldBeExactly 0
                    statusBody.get() shouldContain "\"family\":\"ActionsAvailableReq\""
                    statusBody.get() shouldContain "\"gameStateId\":${prompt.gameStateId}"
                    statusBody.get() shouldContain "\"respId\":${prompt.msgId}"
                    submitBody.get() shouldContain "\"hex\":"
                    actionBridge.getPending()?.actionId shouldNotBe pending.actionId
                }
            } finally {
                server.stop(0)
            }
        }

        test("autopush skips a prompt superseded before native readiness") {
            val requestCount = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requestCount.incrementAndGet()
                exchange.respond("""{"status":"ready"}""")
            }
            server.start()

            try {
                startPuzzleRaw(PASS_PUZZLE)
                val prompt = allMessages.last { it.type == GREMessageType.ActionsAvailableReq_695e }
                harness.bridge.messageCounter.markPromptMsgId(prompt.msgId + 1)

                val autopush =
                    CopilotAutopush(
                        harness.bridge,
                        SeatId(1),
                        "http://127.0.0.1:${server.address.port}",
                        nativeReadyPollMs = 0,
                        nativeReadyChecks = 3,
                        landPollMs = 1,
                        landPollChecks = 20,
                    )
                autopush.onPrompt(prompt)
                Thread.sleep(100)
                autopush.shutdown()

                requestCount.get() shouldBeExactly 0
            } finally {
                server.stop(0)
            }
        }

        test("native transport waits for an older request in the same prompt family") {
            val statusCount = AtomicInteger(0)
            val submitCount = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond/native/status") { exchange ->
                exchange.readBody()
                if (statusCount.incrementAndGet() == 1) {
                    exchange.respond(
                        """{"status":"stale","observed":{"family":"SelectTargetsReq","gameStateId":41,"msgId":72}}""",
                    )
                } else {
                    exchange.respond("""{"status":"ready"}""")
                }
            }
            server.createContext("/respond/native") { exchange ->
                exchange.readBody()
                submitCount.incrementAndGet()
                exchange.respond("""{"status":"submitted"}""")
            }
            server.start()

            try {
                val prompt = prompt(GREMessageType.SelectTargetsReq_695e, gsId = 42, msgId = 74)
                val result =
                    CopilotNativeTransport(
                        "http://127.0.0.1:${server.address.port}",
                        readyPollMs = 0,
                        maxReadyChecks = 3,
                    ).submit(prompt, "00") { false }

                assertSoftly {
                    result.outcome shouldBe NativeSubmitOutcome.SUBMITTED
                    statusCount.get() shouldBeExactly 2
                    submitCount.get() shouldBeExactly 1
                }
            } finally {
                server.stop(0)
            }
        }

        test("native transport rejects a newer request identity") {
            val submitCount = AtomicInteger(0)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond/native/status") { exchange ->
                exchange.readBody()
                exchange.respond(
                    """{"status":"stale","observed":{"family":"SelectTargetsReq","gameStateId":43,"msgId":76}}""",
                )
            }
            server.createContext("/respond/native") { exchange ->
                exchange.readBody()
                submitCount.incrementAndGet()
                exchange.respond("""{"status":"submitted"}""")
            }
            server.start()

            try {
                val prompt = prompt(GREMessageType.SelectTargetsReq_695e, gsId = 42, msgId = 74)
                val result =
                    CopilotNativeTransport(
                        "http://127.0.0.1:${server.address.port}",
                        readyPollMs = 0,
                        maxReadyChecks = 3,
                    ).submit(prompt, "00") { false }

                result.outcome shouldBe NativeSubmitOutcome.IDENTITY_ERROR
                submitCount.get() shouldBeExactly 0
            } finally {
                server.stop(0)
            }
        }

        test("autopush never resubmits after the client delegate reports submitted") {
            val submitCount = AtomicInteger(0)
            val submitted = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/respond/native/status") { exchange ->
                exchange.readBody()
                exchange.respond("""{"status":"ready"}""")
            }
            server.createContext("/respond/native") { exchange ->
                exchange.readBody()
                submitCount.incrementAndGet()
                submitted.countDown()
                exchange.respond("""{"status":"submitted"}""")
            }
            server.start()

            try {
                startPuzzleRaw(CAST_PUZZLE)
                val prompt = allMessages.last { it.type == GREMessageType.ActionsAvailableReq_695e }
                val autopush =
                    CopilotAutopush(
                        harness.bridge,
                        SeatId(1),
                        "http://127.0.0.1:${server.address.port}",
                        nativeReadyPollMs = 0,
                        nativeReadyChecks = 3,
                        landPollMs = 1,
                        landPollChecks = 2,
                    )
                autopush.onPrompt(prompt)
                submitted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                Thread.sleep(100)
                autopush.shutdown()

                submitCount.get() shouldBeExactly 1
            } finally {
                server.stop(0)
            }
        }

        test("landed requires the priority window to advance") {
            startPuzzleRaw(CAST_PUZZLE)
            val autopush = CopilotAutopush(harness.bridge, SeatId(1), "http://127.0.0.1:1")
            try {
                val seatAction = harness.bridge.seat(SeatId(1)).action
                val pending = seatAction.getPending() ?: error("expected a pending priority window")
                val baseline = harness.bridge.messageCounter.responsesAccepted()

                harness.bridge.messageCounter.markResponseAccepted()
                autopush.landed(baseline, pending.actionId) shouldBe false
                autopush.landed(baseline, null) shouldBe true

                seatAction.submitAction(pending.actionId, PlayerAction.PassPriority)
                autopush.landed(baseline, pending.actionId) shouldBe true
            } finally {
                autopush.shutdown()
            }
        }
    }) {
    private companion object {
        val PASS_PUZZLE =
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
            """.trimIndent()

        val CAST_PUZZLE =
            """
            [metadata]
            Name:Autopush Cast
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

private fun HttpExchange.readBody(): String = requestBody.use { it.readBytes().decodeToString() }

private fun prompt(
    type: GREMessageType,
    gsId: Int,
    msgId: Int,
): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setType(type)
        .setGameStateId(gsId)
        .setMsgId(msgId)
        .build()

private fun HttpExchange.respond(body: String) {
    val bytes = body.toByteArray()
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
