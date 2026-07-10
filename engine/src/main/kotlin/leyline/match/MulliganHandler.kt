package leyline.match

import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Mulligan flow delegate for [MatchHandler]. Owns the per-seat mulligan state
 * (hand grpIds, mull count, London tuck) and is testable without a live Netty
 * channel.
 *
 * Uses provider lambdas for session/output/matchId/seatId to avoid holding
 * [MatchConnection] references directly. Cross-seat lookups (e.g. sending the
 * opponent's DealHand) go through [MatchRegistry.getConnection] → peer's
 * [MulliganHandler], which requires both seat handlers to be registered first.
 */
class MulliganHandler(
    private val matchConfig: MatchConfig,
    private val registry: MatchRegistry,
    private val sessionProvider: () -> GameOps?,
    private val outputProvider: () -> MatchOutput,
    private val matchIdProvider: () -> String,
    private val seatIdProvider: () -> SeatId,
) {
    private val log = LoggerFactory.getLogger(MulliganHandler::class.java)

    var mulliganCount = 0
        private set

    var seat1Hand: List<Int> = emptyList()
    var seat2Hand: List<Int> = emptyList()

    private val session get() = sessionProvider()
    private val output get() = outputProvider()
    private val matchId get() = matchIdProvider()
    private val seatId: SeatId get() = seatIdProvider()

    /** Handle ChooseStartingPlayerResp — triggers mulligan flow or skip-mulligan. */
    fun onChooseStartingPlayer() {
        // ChooseStartingPlayerResp may arrive on any seat's channel (often seat 2).
        // Check puzzle mode via registry; don't require MatchSession locally.
        val match = registry.getMatch(matchId)
        if (match?.bridge?.isPuzzle == true) {
            log.info("Match Door GRE: ignoring ChooseStartingPlayerResp for puzzle")
            return
        }

        if (matchConfig.game.skipMulligan) {
            log.info("Match Door GRE: skipMulligan — bypassing mulligan phase")
            // Send DealHand on this seat's channel — use handler's session (may be FamiliarSession)
            sendDealHandViaConnection(session, match?.bridge)
            val seat1Connection = registry.getConnection(matchId, SeatId(1))
            seat1Connection?.mulliganHandler?.sendDealHandPublic()
            seat1Connection?.session?.onMulliganKeep()
        } else {
            log.info("Match Door GRE: seat {} chose starting player", seatId.value)
            sendDealHandAndMulligan()
            val seat1Connection = registry.getConnection(matchId, SeatId(1))
            if (seat1Connection != null) {
                seat1Connection.mulliganHandler.sendDealHandPublic()
                seat1Connection.mulliganHandler.sendMulliganReq()
            } else {
                log.warn("Match Door: seat 1 peer not found for matchId={}", matchId)
            }
        }
    }

    /** Handle MulliganResp — keep or mulligan decision. */
    fun onMulliganResp(greMsg: ClientToGREMessage) {
        val s = session ?: return
        val bridge = s.gameBridge
        if (bridge.isPuzzle) {
            log.info("Match Door GRE: ignoring MulliganResp for puzzle")
            return
        }
        if (seatId == bridge.seating.familiarSeat) return // Familiar — no action

        val decision = greMsg.mulliganResp.decision
        log.info("Match Door GRE: seat {} mulligan decision={}", seatId.value, decision)

        when (decision) {
            MulliganOption.AcceptHand -> {
                if (!bridge.submitKeep(seatId)) return
                bridge.awaitPriority()
                s.onMulliganKeep()
            }
            MulliganOption.Mulligan,
            MulliganOption.None_a2b7,
            MulliganOption.UNRECOGNIZED,
            -> {
                if (!bridge.submitMull(seatId)) return
                mulliganCount++
                val deletedIds = bridge.ids.resetAll().map { it.value }
                seat1Hand = bridge.getHandGrpIds(SeatId(1))
                sendDealHand(deletedIds)
                sendMulliganReq(reportedMulliganCount = mulliganCount, numCards = seat1Hand.size)
            }
        }
    }

    /** Handle GroupResp — London tuck. */
    fun onGroupResp(greMsg: ClientToGREMessage) {
        val s = session ?: return
        val bridge = s.gameBridge
        if (seatId != bridge.seating.humanSeat) return

        val groups = greMsg.groupResp.groupsList
        val tuckIds = if (groups.size >= 2) groups[1].idsList else groups.firstOrNull()?.idsList ?: emptyList()
        log.info("Match Door GRE: seat {} GroupResp tuck {} cards", seatId.value, tuckIds.size)
        val handCards = bridge.getHandCards(seatId)
        val tuckCards =
            tuckIds.mapNotNull { iid ->
                val forgeId = bridge.getForgeCardId(InstanceId(iid))?.value
                handCards.firstOrNull { it.id == forgeId }
            }
        bridge.submitTuck(seatId, tuckCards)
        bridge.awaitPriority()
        s.onMulliganKeep()
    }

    // --- Senders ---

    /**
     * DealHand via any SessionOps (works for both MatchSession and FamiliarSession).
     * Used when ChooseStartingPlayerResp arrives on the Familiar channel with skipMulligan.
     */
    private fun sendDealHandViaConnection(
        s: SessionOps?,
        bridge: GameBridge?,
    ) {
        if (s == null || bridge == null) return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHand(s.counter.currentMsgId(), gsId, bridge, seatId)
        s.counter.setMsgId(nextMsgId)
        s.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("DealHand seat=${seatId.value} deletedIds=0")
        ProtoDump.dump(msg, "DealHand-seat${seatId.value}")
        output.send(msg)
    }

    /** DealHand only — public for cross-connection calls. */
    fun sendDealHandPublic() {
        sendDealHand()
    }

    /** DealHand only (no MulliganReq) for this handler's seat. */
    private fun sendDealHand(diffDeletedInstanceIds: List<Int> = emptyList()) {
        val s = session ?: return
        val bridge = s.gameBridge
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHand(s.counter.currentMsgId(), gsId, bridge, seatId, diffDeletedInstanceIds)
        s.counter.setMsgId(nextMsgId)
        s.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("DealHand seat=${seatId.value} deletedIds=${diffDeletedInstanceIds.size}")
        ProtoDump.dump(msg, "DealHand-seat${seatId.value}")
        output.send(msg)
    }

    /**
     * MulliganReq sequence for seat 1.
     *
     * @param reportedMulliganCount mulliganCount for the proto (default: internal counter).
     * @param numCards NumberOfCards prompt value (default: 7 for London).
     */
    fun sendMulliganReq(
        reportedMulliganCount: Int = mulliganCount,
        numCards: Int = 7,
    ) {
        val s = session ?: return
        val bridge = s.gameBridge
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) =
            HandshakeMessages.mulliganReqSeat1(
                s.counter.currentMsgId(),
                gsId,
                bridge,
                mulliganCount = reportedMulliganCount,
                numCards = numCards,
            )
        s.counter.setMsgId(nextMsgId)
        s.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("MulliganReq seat=${seatId.value} mulliganCount=$reportedMulliganCount numCards=$numCards")
        ProtoDump.dump(msg, "MulliganReq-seat${seatId.value}")
        output.send(msg)
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan() {
        val s = session ?: return
        val bridge = s.gameBridge
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(s.counter.currentMsgId(), gsId, bridge)
        s.counter.setMsgId(nextMsgId)
        s.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("DealHand+MulliganReq seat=${seatId.value}")
        ProtoDump.dump(msg, "DealHand+MullReq-seat${seatId.value}")
        output.send(msg)
    }
}
