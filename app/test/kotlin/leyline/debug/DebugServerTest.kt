package leyline.debug

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.ConnectionState
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class DebugServerTest :
    FunSpec({
        test("mounts only current diagnostic and puzzle routes") {
            val port = ServerSocket(0).use { it.localPort }
            val server = DebugServer(port = port, runtimePuzzle = AtomicReference(null))
            server.start()
            try {
                assertSoftly {
                    request(port, "GET", "/api/puzzle") shouldBe 200
                    request(port, "GET", "/api/best-play") shouldBe 200
                    request(port, "GET", "/api/copilot-proposal") shouldBe 200
                    request(port, "GET", "/api/response-acceptance") shouldBe 200
                    request(port, "POST", "/api/response-acceptance") shouldBe 405
                    request(port, "GET", "/api/inject-full") shouldBe 405
                    request(port, "GET", "/api/copilot-consult") shouldBe 405
                    // POST without a configured card repository → 503, not 404.
                    request(port, "POST", "/api/copilot-consult") shouldBe 503

                    listOf(
                        "/",
                        "/api/events",
                        "/api/priority-log",
                        "/api/gre/matches",
                        "/api/draft/status",
                    ).forEach { path ->
                        request(port, "GET", path) shouldBe 404
                    }
                }
            } finally {
                server.stop()
            }
        }

        test("serializes deck overrides as JSON") {
            val port = ServerSocket(0).use { it.localPort }
            val server = DebugServer(port = port, runtimePuzzle = AtomicReference(null))
            server.start()
            try {
                val response = requestBody(port, "/api/ai-deck", "Name with \\\"quote\\\"")

                response.code shouldBe 200
                Json
                    .parseToJsonElement(response.body)
                    .jsonObject["aiDeck"]!!
                    .jsonPrimitive.content shouldBe "Name with \\\"quote\\\""
            } finally {
                server.stop()
            }
        }

        test("reports offered and accepted response identities") {
            val port = ServerSocket(0).use { it.localPort }
            val bridge = GameBridge(cardRepository = InMemoryCardRepository()).also { it.wrapGame(GameBootstrap.createGame()) }
            val session =
                MatchSession(
                    ConnectionState(SeatId(1), "test-match", ListMessageSink(), MatchRegistry()),
                    bridge,
                    paceDelayMs = 0,
                )
            val server = DebugServer(port = port, sessionProvider = { session })
            server.start()
            try {
                val prompt =
                    GREToClientMessage
                        .newBuilder()
                        .setMsgId(17)
                        .setGameStateId(42)
                        .setType(GREMessageType.SelectNreq)
                        .build()
                session.sendBundledGRE(listOf(prompt))
                val offered = Json.parseToJsonElement(getBody(port, "/api/response-acceptance")).jsonObject
                assertSoftly {
                    offered["matchId"]!!.jsonPrimitive.content shouldBe "test-match"
                    offered["seatId"]!!.jsonPrimitive.content shouldBe "1"
                    offered["prompt"]!!.jsonObject["msgId"]!!.jsonPrimitive.content shouldBe "17"
                    offered["prompt"]!!.jsonObject["gameStateId"]!!.jsonPrimitive.content shouldBe "42"
                    offered["responses"]!!.jsonArray.size shouldBe 0
                    offered["server"]!!.jsonObject.containsKey("checkoutRoot") shouldBe false
                }

                bridge.responseAcceptance.markResponseAccepted(17)
                val accepted = Json.parseToJsonElement(getBody(port, "/api/response-acceptance")).jsonObject
                val response = accepted["responses"]!!.jsonArray.single().jsonObject
                assertSoftly {
                    accepted["prompt"].toString() shouldBe "null"
                    response["ordinal"]!!.jsonPrimitive.content shouldBe "1"
                    response["respId"]!!.jsonPrimitive.content shouldBe "17"
                }
            } finally {
                server.stop()
                session.close()
            }
        }
    })

private fun request(
    port: Int,
    method: String,
    path: String,
): Int {
    val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    return try {
        connection.responseCode
    } finally {
        connection.disconnect()
    }
}

private data class HttpResponse(
    val code: Int,
    val body: String,
)

private fun requestBody(
    port: Int,
    path: String,
    body: String,
): HttpResponse {
    val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    return try {
        connection.outputStream.use { it.write(body.toByteArray()) }
        HttpResponse(connection.responseCode, connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
    }
}

private fun getBody(
    port: Int,
    path: String,
): String {
    val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    return try {
        connection.inputStream.bufferedReader().readText()
    } finally {
        connection.disconnect()
    }
}
