package leyline.web

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

private const val FAMILIAR_SEAT_ID = 2

/**
 * The opponent seat's channel, driven inside the server for browser clients.
 *
 * The match handshake is written for a two-channel client: seat 1 plays, and
 * seat 2 — the Familiar — joins the match and starts the deal-and-mulligan flow
 * for both seats without receiving an interactive request.
 *
 * The native transport supplies the second channel for free: every connection
 * gets its own [MatchConnection]. The web relay cannot. It keys one engine per
 * match and multiplexes every browser socket onto it, so a second socket is by
 * construction the *same* client reconnecting — [MatchConnection] closes the
 * live session and re-seats the handler, destroying the match it was meant to
 * join.
 *
 * So the server supplies the seat. This drives a second [MatchConnection] over
 * the same registry as the browser's and sinks its outbound frames because they
 * belong to a seat nobody is watching.
 */
internal class WebFamiliarSeat(
    private val openConnection: (MatchOutput) -> MatchConnection,
    /**
     * Whether [matchId] is one the seat belongs in. A puzzle starts from a
     * prepared board and a spectated match already has both seats, so neither
     * needs the server-owned Familiar progression this supplies.
     */
    private val needsFamiliarSeat: (String) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(WebFamiliarSeat::class.java)

    private var connection: MatchConnection? = null
    private var playerClientId: String = ""

    /** Sink for the Familiar's read-only outbound frames. */
    private val output =
        object : MatchOutput {
            override fun send(message: MatchServiceToClientMessage) = Unit

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
