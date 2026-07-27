package leyline.web

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ChooseStartingPlayerResp
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.TeamType

private const val PLAYER_SEAT_ID = 1
private const val FAMILIAR_SEAT_ID = 2

/**
 * The opponent seat's channel, driven inside the server for browser clients.
 *
 * The match handshake is written for a two-channel client: seat 1 plays, and
 * seat 2 — the Familiar — mirrors the match and answers
 * [GREMessageType.ChooseStartingPlayerReq_695e]. That answer is what starts the
 * deal-and-mulligan flow *for both seats*, so without it seat 1 sits on the
 * initial bundle forever and no cards are ever dealt.
 *
 * The native transport supplies the second channel for free: every connection
 * gets its own [MatchConnection]. The web relay cannot. It keys one engine per
 * match and multiplexes every browser socket onto it, so a second socket is by
 * construction the *same* client reconnecting — [MatchConnection] closes the
 * live session and re-seats the handler, destroying the match it was meant to
 * join.
 *
 * So the server supplies the seat. This drives a second [MatchConnection] over
 * the same registry as the browser's, sinks its outbound frames (they belong to
 * a seat nobody is watching), and answers the starting-player prompt the moment
 * it appears.
 */
internal class WebFamiliarSeat(
    private val openConnection: (MatchOutput) -> MatchConnection,
    /**
     * Whether [matchId] is one the seat belongs in. A puzzle starts from a
     * prepared board and a spectated match already has both seats, so neither
     * raises the starting-player prompt this exists to answer.
     */
    private val needsFamiliarSeat: (String) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(WebFamiliarSeat::class.java)

    private var connection: MatchConnection? = null
    private var playerClientId: String = ""

    /** msgId of a [GREMessageType.ChooseStartingPlayerReq_695e] seen but not yet answered. */
    private var unansweredPromptMsgId: Int? = null

    /**
     * Sink for the Familiar's outbound frames. Everything here is addressed to
     * seat 2 and duplicates what seat 1 already has, so the only frame worth
     * reading is the starting-player prompt.
     */
    private val output =
        object : MatchOutput {
            override fun send(message: MatchServiceToClientMessage) {
                if (!message.hasGreToClientEvent()) return
                message.greToClientEvent.greToClientMessagesList
                    .lastOrNull { it.type == GREMessageType.ChooseStartingPlayerReq_695e }
                    ?.let { unansweredPromptMsgId = it.msgId }
            }

            override fun close() = Unit
        }

    /**
     * Mirror the browser's handshake onto the opponent seat.
     *
     * The Familiar joins once seat 1's connect has landed, so the match and its
     * bridge already exist by the time it asks for them, and it survives browser
     * reconnects — a page reload re-seats seat 1 but must not re-run seat 2.
     */
    fun followBrowser(message: ClientToMatchServiceMessage) {
        try {
            when (message.clientToMatchServiceMessageType) {
                ClientToMatchServiceMessageType.AuthenticateRequest_f487 ->
                    playerClientId = AuthenticateRequest.parseFrom(message.payload).clientId
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 ->
                    join(ClientToMatchDoorConnectRequest.parseFrom(message.payload).matchId)
                else -> Unit
            }
        } catch (e: InvalidProtocolBufferException) {
            log.warn("Familiar seat: unreadable handshake frame from the browser", e)
        }
    }

    fun close() {
        connection?.disconnected()
        connection = null
    }

    private fun join(matchId: String) {
        if (connection != null || matchId.isEmpty() || !needsFamiliarSeat(matchId)) return
        val familiar = openConnection(output)
        connection = familiar
        log.info("Familiar seat: joining matchId={} as seat {}", matchId, FAMILIAR_SEAT_ID)
        familiar.opened()
        familiar.receive(authenticate("${playerClientId}_Familiar"))
        familiar.receive(connectTo(matchId))
        answerStartingPlayer(familiar)
    }

    /**
     * Answer the prompt raised by the connect above.
     *
     * Deferred until [join]'s connect returns rather than answered from inside
     * [output], so the response is never dispatched re-entrantly through a
     * connection that is still handling its own connect.
     */
    private fun answerStartingPlayer(familiar: MatchConnection) {
        val respId = unansweredPromptMsgId
        if (respId == null) {
            // Nothing else will deal the opening hands, so the match is now
            // stalled on its initial bundle. Say so — silence here reads as a
            // client that never rendered rather than a handshake that stopped.
            log.warn("Familiar seat: connected without a starting-player prompt; opening hands will not be dealt")
            return
        }
        unansweredPromptMsgId = null
        familiar.receive(chooseStartingPlayer(respId))
    }

    private fun authenticate(clientId: String): ClientToMatchServiceMessage =
        serviceMessage(
            ClientToMatchServiceMessageType.AuthenticateRequest_f487,
            AuthenticateRequest
                .newBuilder()
                .setClientId(clientId)
                .build()
                .toByteString(),
        )

    private fun connectTo(matchId: String): ClientToMatchServiceMessage =
        serviceMessage(
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
            ClientToMatchDoorConnectRequest
                .newBuilder()
                .setMatchId(matchId)
                .setClientToGreMessageBytes(
                    ClientToGREMessage
                        .newBuilder()
                        .setSystemSeatId(FAMILIAR_SEAT_ID)
                        .setType(ClientMessageType.ConnectReq_097b)
                        .setConnectReq(ConnectReq.newBuilder())
                        .build()
                        .toByteString(),
                ).build()
                .toByteString(),
        )

    /**
     * The starting-player choice.
     *
     * The engine reads no field of the response — the handler it reaches takes
     * no argument. Who plays first was settled by the die roll before the
     * initial bundle went out; this message is the trigger for the deal, not the
     * decision, and the payload only has to be well-formed.
     */
    private fun chooseStartingPlayer(respId: Int): ClientToMatchServiceMessage =
        serviceMessage(
            ClientToMatchServiceMessageType.ClientToGremessage,
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(FAMILIAR_SEAT_ID)
                .setType(ClientMessageType.ChooseStartingPlayerResp_097b)
                .setRespId(respId)
                .setChooseStartingPlayerResp(
                    ChooseStartingPlayerResp
                        .newBuilder()
                        .setTeamType(TeamType.Individual)
                        .setSystemSeatId(PLAYER_SEAT_ID)
                        .setTeamId(PLAYER_SEAT_ID),
                ).build()
                .toByteString(),
        )

    private fun serviceMessage(
        type: ClientToMatchServiceMessageType,
        payload: ByteString,
    ): ClientToMatchServiceMessage =
        ClientToMatchServiceMessage
            .newBuilder()
            .setClientToMatchServiceMessageType(type)
            .setPayload(payload)
            .build()
}
