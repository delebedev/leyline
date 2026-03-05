package leyline.match

import io.netty.channel.ChannelHandlerContext
import leyline.config.MatchConfig
import leyline.debug.Tap
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Mulligan flow delegate — owns mulligan state and pre-game hand senders.
 *
 * Extracted from [MatchHandler] to keep it thin. Handles:
 * - DealHand / DealHand+MulliganReq sends
 * - MulliganResp dispatch (keep / mulligan)
 * - GroupResp (London tuck)
 * - ChooseStartingPlayerResp (triggers mulligan or skip-mulligan)
 */
class MulliganHandler(
    private val matchConfig: MatchConfig,
    private val registry: MatchRegistry,
) {
    private val log = LoggerFactory.getLogger(MulliganHandler::class.java)

    var mulliganCount = 0
        private set

    var seat1Hand: List<Int> = emptyList()
    var seat2Hand: List<Int> = emptyList()

    // --- Accessors set by MatchHandler on connect ---
    lateinit var sessionProvider: () -> MatchSession?
    lateinit var ctxProvider: () -> ChannelHandlerContext?
    var matchId: String = ""
    var seatId: Int = 1

    private val session get() = sessionProvider()
    private val ctx get() = ctxProvider()

    /** Handle ChooseStartingPlayerResp — triggers mulligan flow or skip-mulligan. */
    fun onChooseStartingPlayer(matchHandlerRef: MatchHandler) {
        val s = session ?: return
        if (s.gameBridge?.isPuzzle == true) {
            log.info("Match Door GRE: ignoring ChooseStartingPlayerResp for puzzle")
            return
        }

        if (matchConfig.game.skipMulligan) {
            log.info("Match Door GRE: skipMulligan — bypassing mulligan phase")
            sendDealHand(ctx!!)
            val seat1Handler = registry.getHandler(matchId, 1)
            seat1Handler?.mulliganHandler?.sendDealHandPublic()
            seat1Handler?.session?.onMulliganKeep()
        } else {
            log.info("Match Door GRE: seat {} chose starting player", seatId)
            sendDealHandAndMulligan(ctx!!)
            val seat1Handler = registry.getHandler(matchId, 1)
            if (seat1Handler != null) {
                seat1Handler.mulliganHandler.sendDealHandPublic()
                seat1Handler.mulliganHandler.sendMulliganReq()
            } else {
                log.warn("Match Door: seat 1 peer not found for matchId={}", matchId)
            }
        }
    }

    /** Handle MulliganResp — keep or mulligan decision. */
    fun onMulliganResp(greMsg: ClientToGREMessage) {
        val s = session ?: return
        if (s.gameBridge?.isPuzzle == true) {
            log.info("Match Door GRE: ignoring MulliganResp for puzzle")
            return
        }

        val decision = greMsg.mulliganResp.decision
        log.info("Match Door GRE: seat {} mulligan decision={}", seatId, decision)
        val bridge = s.gameBridge

        if (seatId == 2) {
            // Familiar responded — ignored
        } else if (decision == MulliganOption.AcceptHand) {
            bridge?.submitKeep(seatId)
            bridge?.awaitPriority()
            s.onMulliganKeep()
        } else {
            mulliganCount++
            bridge?.submitMull(seatId)
            val deletedIds = bridge?.ids?.resetAll() ?: emptyList()
            seat1Hand = bridge?.getHandGrpIds(1) ?: emptyList()
            sendDealHand(ctx!!, deletedIds)
            sendMulliganReq(reportedMulliganCount = 0, numCards = seat1Hand.size)
        }
    }

    /** Handle GroupResp — London tuck. */
    fun onGroupResp(greMsg: ClientToGREMessage) {
        if (seatId != 1) return
        val s = session ?: return
        val bridge = s.gameBridge ?: return

        val groups = greMsg.groupResp.groupsList
        val tuckIds = if (groups.size >= 2) groups[1].idsList else groups.firstOrNull()?.idsList ?: emptyList()
        log.info("Match Door GRE: seat {} GroupResp tuck {} cards", seatId, tuckIds.size)
        val handCards = bridge.getHandCards(seatId)
        val tuckCards = tuckIds.mapNotNull { iid ->
            val forgeId = bridge.getForgeCardId(iid)
            handCards.firstOrNull { it.id == forgeId }
        }
        bridge.submitTuck(seatId, tuckCards)
        bridge.awaitPriority()
        s.onMulliganKeep()
    }

    // --- Senders ---

    /** DealHand only — public for cross-connection calls. Uses stored ctx. */
    fun sendDealHandPublic() {
        val c = ctx ?: return
        sendDealHand(c)
    }

    /** DealHand only (no MulliganReq) for this handler's seat. */
    private fun sendDealHand(ctx: ChannelHandlerContext, diffDeletedInstanceIds: List<Int> = emptyList()) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHand(s.counter.currentMsgId(), gsId, bridge, seatId, diffDeletedInstanceIds)
        s.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("DealHand seat=$seatId deletedIds=${diffDeletedInstanceIds.size}")
        ProtoDump.dump(msg, "DealHand-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /**
     * MulliganReq sequence for seat 1.
     *
     * @param reportedMulliganCount mulliganCount for the proto (default: internal counter).
     * @param numCards NumberOfCards prompt value (default: 7 for London).
     */
    fun sendMulliganReq(reportedMulliganCount: Int = mulliganCount, numCards: Int = 7) {
        val c = ctx ?: return
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.mulliganReqSeat1(
            s.counter.currentMsgId(),
            gsId,
            bridge,
            mulliganCount = reportedMulliganCount,
            numCards = numCards,
        )
        s.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("MulliganReq seat=$seatId mulliganCount=$reportedMulliganCount numCards=$numCards")
        ProtoDump.dump(msg, "MulliganReq-seat$seatId")
        c.writeAndFlush(msg)
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(s.counter.currentMsgId(), gsId, bridge)
        s.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("DealHand+MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "DealHand+MullReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** GroupReq bundle for London mulligan tuck. */
    @Suppress("unused") // Will be wired when we send GroupReq to client instead of auto-tucking
    private fun sendGroupReq(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val handCards = bridge.getHandCards(seatId)
        val handInstanceIds = handCards.map { bridge.getOrAllocInstanceId(it.id) }
        val tuckCount = bridge.getTuckCount()
        val (msg, nextMsgId) = HandshakeMessages.groupReqBundle(
            s.counter.currentMsgId(),
            gsId,
            seatId,
            mulliganCount,
            handInstanceIds,
            tuckCount,
            bridge,
        )
        s.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("GroupReq seat=$seatId tuck=$tuckCount")
        ProtoDump.dump(msg, "GroupReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }
}
