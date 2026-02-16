package forge.nexus.server

import forge.nexus.debug.ArenaDebugCollector
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusTap
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.nexus.protocol.ProtoDump
import forge.nexus.protocol.Templates
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Match Door handler (port 30003) — thin Netty adapter.
 *
 * Pre-mulligan messages (auth, connect, room state, deal hand, mulligan) are handled
 * here using template-based senders. Post-mulligan game orchestration is delegated
 * to [MatchSession].
 */
class MatchHandler(
    private val registry: MatchRegistry = defaultRegistry,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    private var mulliganCount = 0
    private var matchId = "forge-match-1"
    private var clientId = "forge-player-1"
    private var seatId = 1

    private var seat1Hand: List<Int> = emptyList()
    private var seat2Hand: List<Int> = emptyList()

    /** Netty context — saved for template senders and cross-connection calls. */
    private var nettyCtx: ChannelHandlerContext? = null

    /** Game session — created on connect, holds all post-mulligan state. */
    internal var session: MatchSession? = null

    companion object {
        val defaultRegistry = MatchRegistry()
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        NexusTap.inbound(msg.clientToMatchServiceMessageType, msg.requestId)
        ArenaDebugCollector.recordInbound(msg)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(ctx, msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGreuimessage -> handleGREMessage(ctx, msg)
            else -> log.warn("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleMatchAuth(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        clientId = authReq.clientId.ifEmpty { "forge-player-$seatId" }
        val playerName = authReq.playerName.ifEmpty { "ForgePlayer" }
        log.info("Match Door: auth clientId={} playerName={}", clientId, playerName)

        val resp = MatchServiceToClientMessage.newBuilder()
            .setRequestId(msg.requestId)
            .setAuthenticateResponse(
                AuthenticateResponse.newBuilder()
                    .setClientId(clientId)
                    .setSessionId("forge-session-1")
                    .setScreenName(playerName),
            )
            .build()
        ProtoDump.dump(resp, "AuthResp")
        ctx.writeAndFlush(resp)
    }

    private fun handleMatchDoorConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        if (connectReq.matchId.isNotEmpty()) matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", matchId)

        if (!connectReq.clientToGreMessageBytes.isEmpty) {
            val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
            if (greMsg.systemSeatId > 0) seatId = greMsg.systemSeatId
            log.info("Match Door: detected seatId={}", seatId)
            nettyCtx = ctx

            // Create session + sink
            val sink = NettyMessageSink(ctx)
            val s = MatchSession(seatId, matchId, sink, registry)
            session = s
            registry.registerSession(matchId, seatId, s)
            registry.registerHandler(matchId, seatId, this)

            processGREMessage(ctx, greMsg)
        }
    }

    private fun handleGREMessage(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        processGREMessage(ctx, ClientToGREMessage.parseFrom(msg.payload))
    }

    private fun processGREMessage(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        NexusTap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)
        val s = session

        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> {
                // Evict stale bridges from previous matches and reset debug collectors
                val evicted = registry.evictStale(matchId)
                if (evicted.isNotEmpty()) {
                    evicted.forEach { it.shutdown() }
                    ArenaDebugCollector.clear()
                    GameStateCollector.clear()
                    log.info("Match Door: evicted {} stale bridge(s)", evicted.size)
                }

                // Only one bridge per match — first seat to arrive creates it
                val bridge = registry.getOrCreateBridge(matchId) {
                    GameBridge().also { it.start(seed = 42L) }
                }
                s?.gameBridge = bridge
                seat1Hand = bridge.getHandGrpIds(1)
                seat2Hand = bridge.getHandGrpIds(2)
                log.info("Match Door: seat {} connected, hands seat1={} seat2={}", seatId, seat1Hand, seat2Hand)
                sendRoomState(ctx)
                sendInitialBundle(ctx)
            }

            ClientMessageType.ChooseStartingPlayerResp_097b -> {
                log.info("Match Door GRE: seat {} chose starting player", seatId)
                sendDealHandAndMulligan(ctx) // seat 2: DealHand + MulliganReq
                // Cross-connection: find seat 1's handler to send DealHand + MulliganReq
                // Stub — will be wired properly via registry in Task 6
                val seat1Handler = registry.getHandler(matchId, 1)
                if (seat1Handler != null) {
                    seat1Handler.sendDealHand()
                    seat1Handler.sendMulliganReq()
                } else {
                    log.warn("Match Door: seat 1 peer not found for matchId={}", matchId)
                }
            }

            ClientMessageType.MulliganResp_097b -> {
                val decision = greMsg.mulliganResp.decision
                log.info("Match Door GRE: seat {} mulligan decision={}", seatId, decision)
                val bridge = s?.gameBridge
                if (seatId == 2) {
                    // Familiar responded — ignored
                } else if (decision == MulliganOption.AcceptHand) {
                    bridge?.submitKeep(seatId)
                    s?.onMulliganKeep()
                } else {
                    mulliganCount++
                    bridge?.submitMull(seatId)
                    seat1Hand = bridge?.getHandGrpIds(1) ?: emptyList()
                    sendDealHandAndMulligan(ctx)
                }
            }

            ClientMessageType.SetSettingsReq_097b -> {
                s?.onSettings(greMsg)
            }

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s?.onConcede()
            }

            ClientMessageType.PerformActionResp_097b -> {
                if (seatId == 1) {
                    s?.onPerformAction(greMsg)
                } else {
                    log.debug("Match Door: ignoring PerformActionResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareAttackersResp_097b,
            ClientMessageType.SubmitAttackersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareAttackers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareAttackersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareBlockersResp_097b,
            ClientMessageType.SubmitBlockersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareBlockers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareBlockersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.SelectTargetsResp_097b -> {
                if (seatId == 1) {
                    s?.onSelectTargets(greMsg)
                }
            }

            else -> log.warn("Match Door GRE: unhandled type: {}", greMsg.type)
        }
    }

    // --- Template-based senders (pre-mulligan, use ctx directly) ---

    private fun sendRoomState(ctx: ChannelHandlerContext) {
        val playerId = clientId.removeSuffix("_Familiar")
        val msg = Templates.roomState(matchId, playerId)
        NexusTap.outboundTemplate("RoomState matchId=$matchId")
        ProtoDump.dump(msg, "RoomState")
        ctx.writeAndFlush(msg)
    }

    private fun sendInitialBundle(ctx: ChannelHandlerContext) {
        val s = session ?: return
        s.gameStateId++
        val deckGrpIds = s.gameBridge?.getDeckGrpIds(seatId) ?: emptyList()
        val deck = StateMapper.buildDeckMessage(deckGrpIds)
        val (msg, nextMsgId) = Templates.initialBundle(seatId, matchId, s.msgIdCounter, s.gameStateId, deck)
        s.msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("InitialBundle seat=$seatId")
        ProtoDump.dump(msg, "InitialBundle-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand only (no MulliganReq) for seat 1. Called cross-connection. */
    fun sendDealHand() {
        val ctx = nettyCtx ?: return
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        s.gameStateId++
        val (msg, nextMsgId) = Templates.dealHandSeat1(s.msgIdCounter, s.gameStateId, bridge)
        s.msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("DealHand seat=$seatId")
        ProtoDump.dump(msg, "DealHand-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** MulliganReq sequence for seat 1. */
    fun sendMulliganReq() {
        val ctx = nettyCtx ?: return
        val s = session ?: return
        s.gameStateId++
        val (msg, nextMsgId) = Templates.mulliganReqSeat1(s.msgIdCounter, s.gameStateId)
        s.msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "MulliganReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        s.gameStateId++
        val (msg, nextMsgId) = Templates.dealHandMulliganSeat2(s.msgIdCounter, s.gameStateId, bridge)
        s.msgIdCounter = nextMsgId
        NexusTap.outboundTemplate("DealHand+MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "DealHand+MullReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Match Door error: {}", cause.message, cause)
        session?.gameBridge?.shutdown()
        ctx.close()
    }
}
