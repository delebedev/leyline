package leyline.match

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import leyline.infra.MatchOutput
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

private const val COMPANION_SEAT_ID = 2

/** Supplies the non-interactive opponent channel expected by a single-channel client. */
internal class RuntimeCompanionSeat(
    private val openConnection: (MatchOutput) -> MatchConnection,
    private val isNeeded: (String) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(RuntimeCompanionSeat::class.java)
    private var connection: MatchConnection? = null
    private var playerClientId: String = ""

    private val output =
        object : MatchOutput {
            override fun send(message: MatchServiceToClientMessage) = Unit

            override fun close() = Unit
        }

    fun follow(message: ClientToMatchServiceMessage) {
        try {
            when (message.clientToMatchServiceMessageType) {
                ClientToMatchServiceMessageType.AuthenticateRequest_f487 ->
                    playerClientId = AuthenticateRequest.parseFrom(message.payload).clientId
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 ->
                    join(ClientToMatchDoorConnectRequest.parseFrom(message.payload).matchId)
                else -> Unit
            }
        } catch (e: InvalidProtocolBufferException) {
            log.warn("Companion seat: unreadable handshake frame", e)
        }
    }

    fun close() {
        connection?.disconnected()
        connection = null
    }

    private fun join(matchId: String) {
        if (connection != null || matchId.isEmpty() || !isNeeded(matchId)) return
        val companion = openConnection(output)
        connection = companion
        companion.opened()
        companion.receive(authenticate("${playerClientId}_Familiar"))
        companion.receive(connectTo(matchId))
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
                        .setSystemSeatId(COMPANION_SEAT_ID)
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
