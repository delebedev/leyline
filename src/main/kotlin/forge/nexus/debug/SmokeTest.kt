package forge.nexus.debug

import com.google.protobuf.ByteString
import forge.nexus.protocol.ClientFrameDecoder
import forge.nexus.protocol.ClientHeaderPrepender
import forge.nexus.protocol.ClientHeaderStripper
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("forge.nexus.debug.SmokeTest")

/**
 * Headless smoke test: connects to the client stub as a fake client
 * and validates the full auth → mulligan flow without launching the client.
 *
 * Usage: `make arena-smoke` (requires stub running on localhost)
 */
fun main() {
    val group = NioEventLoopGroup(1)
    val ssl = SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build()

    try {
        // Phase 1: Front Door
        println("=== Front Door (:30010) ===")
        val fdResult = testFrontDoor(group, ssl)
        println("  Auth response: ${fdResult.sessionOk}")
        println("  MatchCreated:  ${fdResult.matchCreatedOk}")
        check(fdResult.sessionOk) { "Front Door auth failed" }
        check(fdResult.matchCreatedOk) { "Front Door MatchCreated not received" }

        // Phase 2: Match Door (two connections — player + familiar)
        println("\n=== Match Door (:30003) ===")
        val seat1Result = testMatchDoor(group, ssl, seatId = 1, isFamiliar = false)
        val seat2Result = testMatchDoor(group, ssl, seatId = 2, isFamiliar = true)

        println("  Seat 1 auth:       ${seat1Result.authOk}")
        println("  Seat 1 roomState:  ${seat1Result.roomStateOk}")
        println("  Seat 1 bundle:     ${seat1Result.initialBundleOk}")
        println("  Seat 2 auth:       ${seat2Result.authOk}")
        println("  Seat 2 roomState:  ${seat2Result.roomStateOk}")
        println("  Seat 2 bundle:     ${seat2Result.initialBundleOk}")

        // Wait for mulligan flow (seat 2 triggers it via ChooseStartingPlayerResp)
        Thread.sleep(500)
        println("  Seat 1 dealHand:   ${seat1Result.dealHandOk}")
        println("  Seat 1 mullReq:    ${seat1Result.mulliganReqOk}")
        println("  Seat 2 dealHand:   ${seat2Result.dealHandOk}")

        // Shape warnings from structural validation
        val allWarnings = seat1Result.shapeWarnings + seat2Result.shapeWarnings
        if (allWarnings.isNotEmpty()) {
            println("\n=== Shape Warnings (${allWarnings.size}) ===")
            allWarnings.forEach { println("  ⚠ $it") }
        }

        val allOk = fdResult.sessionOk &&
            fdResult.matchCreatedOk &&
            seat1Result.authOk &&
            seat1Result.roomStateOk &&
            seat1Result.initialBundleOk &&
            seat2Result.authOk &&
            seat2Result.roomStateOk &&
            seat2Result.initialBundleOk

        val shapeOk = allWarnings.isEmpty()
        println("\n=== Result: ${if (allOk) "PASS" else "FAIL"} (shape: ${if (shapeOk) "OK" else "${allWarnings.size} warnings"}) ===")
        if (!allOk) exitProcess(1)
    } finally {
        group.shutdownGracefully().sync()
    }
}

// --- Front Door test ---

private data class FrontDoorResult(val sessionOk: Boolean, val matchCreatedOk: Boolean)

private fun testFrontDoor(group: NioEventLoopGroup, ssl: SslContext): FrontDoorResult {
    val handler = FrontDoorTestHandler()
    val ch = connect(group, ssl, 30010) { ch ->
        ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
        ch.pipeline().addLast("handler", handler)
    }

    // Send auth
    val authJson = """{"ClientVersion":"2026.56.10","Token":"smoke-test","PlatformId":"Mac"}"""
    sendFdMessage(ch, UUID.randomUUID().toString(), authJson)

    handler.authFuture.get(5, TimeUnit.SECONDS)

    // Send state request
    sendFdMessage(ch, UUID.randomUUID().toString(), """{"RequestedType":"None"}""")
    Thread.sleep(200)

    // Send play request to trigger MatchCreated
    sendFdMessage(ch, UUID.randomUUID().toString(), """{"EventId":"AIBotMatch","PlayQueue":true}""")

    val matchCreated = try {
        handler.matchCreatedFuture.get(5, TimeUnit.SECONDS)
        true
    } catch (_: Exception) {
        false
    }

    ch.close().sync()
    return FrontDoorResult(handler.sessionOk, matchCreated)
}

private class FrontDoorTestHandler : ChannelInboundHandlerAdapter() {
    var sessionOk = false
    val authFuture = CompletableFuture<Unit>()
    val matchCreatedFuture = CompletableFuture<Unit>()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) return
        try {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            if (bytes.size <= ClientFrameDecoder.HEADER_SIZE) return
            val payload = bytes.copyOfRange(ClientFrameDecoder.HEADER_SIZE, bytes.size)
            val text = payload.toString(Charsets.UTF_8)
            when {
                "SessionId" in text -> {
                    sessionOk = true
                    authFuture.complete(Unit)
                }
                "MatchCreated" in text -> matchCreatedFuture.complete(Unit)
            }
        } finally {
            msg.release()
        }
    }
}

private fun sendFdMessage(ch: Channel, transactionId: String, json: String) {
    val buf = ByteArrayOutputStream()
    // field 2 (string) = transactionId
    writeProtoString(buf, 2, transactionId)
    // field 4 (string) = JSON payload
    writeProtoString(buf, 4, json)
    val envelope = buf.toByteArray()

    val header = ByteArray(ClientFrameDecoder.HEADER_SIZE)
    header[0] = ClientFrameDecoder.VERSION
    header[1] = ClientFrameDecoder.TYPE_DATA_FD
    header[2] = (envelope.size and 0xFF).toByte()
    header[3] = ((envelope.size shr 8) and 0xFF).toByte()

    val frame = Unpooled.buffer(header.size + envelope.size)
    frame.writeBytes(header)
    frame.writeBytes(envelope)
    ch.writeAndFlush(frame)
}

// --- Match Door test ---

private data class MatchDoorResult(
    val authOk: Boolean,
    val roomStateOk: Boolean,
    val initialBundleOk: Boolean,
    val dealHandOk: Boolean,
    val mulliganReqOk: Boolean,
    val shapeWarnings: List<String>,
)

private fun testMatchDoor(
    group: NioEventLoopGroup,
    ssl: SslContext,
    seatId: Int,
    isFamiliar: Boolean,
): MatchDoorResult {
    val handler = MatchDoorTestHandler()
    val ch = connect(group, ssl, 30003) { ch ->
        ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
        ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
        ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(MatchServiceToClientMessage.getDefaultInstance()))
        ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender(ClientFrameDecoder.TYPE_DATA_FD))
        ch.pipeline().addLast("handler", handler)
    }

    // Send AuthenticateRequest
    val clientId = if (isFamiliar) "smoke-player_Familiar" else "smoke-player"
    val authPayload = AuthenticateRequest.newBuilder()
        .setClientId(clientId)
        .setPlayerName(if (isFamiliar) "Sparky" else "SmokePlayer")
        .build()
    sendMdMessage(ch, 1, ClientToMatchServiceMessageType.AuthenticateRequest_f487, authPayload.toByteArray())

    try {
        handler.authFuture.get(5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        log.debug("Match door auth handshake timed out", e)
    }

    // Send MatchDoorConnect with embedded ConnectReq
    val connectReq = ClientToGREMessage.newBuilder()
        .setType(ClientMessageType.ConnectReq_097b)
        .setSystemSeatId(seatId)
        .build()
    val doorConnect = ClientToMatchDoorConnectRequest.newBuilder()
        .setMatchId("forge-match-1")
        .setClientToGreMessageBytes(connectReq.toByteString())
        .build()
    sendMdMessage(ch, 2, ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487, doorConnect.toByteArray())

    try {
        handler.bundleFuture.get(5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        log.debug("Match door initial bundle timed out", e)
    }

    // Seat 2: send ChooseStartingPlayerResp (triggers mulligan flow)
    if (isFamiliar) {
        val chooseResp = ClientToGREMessage.newBuilder()
            .setType(ClientMessageType.ChooseStartingPlayerResp_097b)
            .setSystemSeatId(seatId)
            .setChooseStartingPlayerResp(ChooseStartingPlayerResp.newBuilder().setTeamId(1))
            .build()
        sendMdMessage(ch, 3, ClientToMatchServiceMessageType.ClientToGremessage, chooseResp.toByteArray())
        Thread.sleep(500)
    } else {
        // Seat 1 waits for cross-connection delivery
        Thread.sleep(1000)
    }

    ch.close().sync()
    return MatchDoorResult(
        authOk = handler.authOk,
        roomStateOk = handler.roomStateOk,
        initialBundleOk = handler.initialBundleOk,
        dealHandOk = handler.dealHandOk,
        mulliganReqOk = handler.mulliganReqOk,
        shapeWarnings = handler.shapeWarnings.toList(),
    )
}

private class MatchDoorTestHandler : SimpleChannelInboundHandler<MatchServiceToClientMessage>() {
    var authOk = false
    var roomStateOk = false
    var initialBundleOk = false
    var dealHandOk = false
    var mulliganReqOk = false
    val authFuture = CompletableFuture<Unit>()
    val bundleFuture = CompletableFuture<Unit>()

    /** Shape issues found during message validation. */
    val shapeWarnings = mutableListOf<String>()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: MatchServiceToClientMessage) {
        if (msg.hasAuthenticateResponse()) {
            authOk = true
            authFuture.complete(Unit)
        }
        if (msg.hasMatchGameRoomStateChangedEvent()) {
            roomStateOk = true
        }
        if (msg.hasGreToClientEvent()) {
            val event = msg.greToClientEvent
            validateGreBundle(event)
            for (gre in event.greToClientMessagesList) {
                when (gre.type) {
                    GREMessageType.ConnectResp_695e -> initialBundleOk = true
                    GREMessageType.GameStateMessage_695e -> {
                        if (gre.hasGameStateMessage() && gre.gameStateMessage.gameObjectsCount > 0) {
                            dealHandOk = true
                        }
                    }
                    GREMessageType.MulliganReq_aa0d -> mulliganReqOk = true
                    else -> {}
                }
            }
            if (initialBundleOk) bundleFuture.complete(Unit)
        }
    }

    /** Structural validation of a GRE bundle. */
    private fun validateGreBundle(event: GreToClientEvent) {
        val allZoneInstanceIds = mutableSetOf<Int>()

        for (gre in event.greToClientMessagesList) {
            // Collect zone instanceIds from any GameStateMessage
            if (gre.hasGameStateMessage()) {
                val gs = gre.gameStateMessage
                for (zone in gs.zonesList) {
                    allZoneInstanceIds.addAll(zone.objectInstanceIdsList)
                }

                // Validate GameStateUpdate values
                if (gs.hasGameInfo() && gs.gameInfo.stage == GameStage.Play_a920) {
                    if (gs.update == GameStateUpdate.Send) {
                        shapeWarnings.add("GSM gsId=${gs.gameStateId}: Play stage should use SendHiFi or SendAndRecord, got Send")
                    }
                }

                // Validate embedded actions reference known instanceIds
                for (action in gs.actionsList) {
                    if (action.action.instanceId != 0 && action.action.instanceId !in allZoneInstanceIds) {
                        shapeWarnings.add("GSM gsId=${gs.gameStateId}: action instanceId=${action.action.instanceId} not in any zone")
                    }
                }
            }

            // Validate ActionsAvailableReq references
            if (gre.hasActionsAvailableReq()) {
                for (action in gre.actionsAvailableReq.actionsList) {
                    if (action.instanceId != 0 && action.instanceId !in allZoneInstanceIds) {
                        shapeWarnings.add("ActionsReq: instanceId=${action.instanceId} not in preceding GSM zones")
                    }
                }
            }
        }
    }
}

private fun sendMdMessage(ch: Channel, requestId: Int, type: ClientToMatchServiceMessageType, payload: ByteArray) {
    val msg = ClientToMatchServiceMessage.newBuilder()
        .setRequestId(requestId)
        .setClientToMatchServiceMessageType(type)
        .setPayload(ByteString.copyFrom(payload))
        .build()
    ch.writeAndFlush(msg)
}

// --- shared helpers ---

private fun connect(
    group: NioEventLoopGroup,
    ssl: SslContext,
    port: Int,
    init: (SocketChannel) -> Unit,
): Channel {
    val b = Bootstrap()
        .group(group)
        .channel(NioSocketChannel::class.java)
        .handler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline().addFirst("ssl", ssl.newHandler(ch.alloc(), "localhost", port))
                init(ch)
            }
        })
    return b.connect("localhost", port).sync().channel()
}

private fun writeProtoString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeVarint(out, (fieldNumber shl 3) or 2) // wire type 2 = length-delimited
    writeVarint(out, bytes.size)
    out.write(bytes)
}

private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        out.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    out.write(v)
}
