package leyline.web

import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq

internal fun authRequestBytes(clientId: String): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setRequestId(1)
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
        .setPayload(
            AuthenticateRequest
                .newBuilder()
                .setClientId(clientId)
                .setPlayerName(clientId)
                .build()
                .toByteString(),
        ).build()
        .toByteArray()

internal fun connectRequestBytes(
    matchId: String,
    seatId: Int,
): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setRequestId(2)
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487)
        .setPayload(
            ClientToMatchDoorConnectRequest
                .newBuilder()
                .setMatchId(matchId)
                .setClientToGreMessageBytes(
                    ClientToGREMessage
                        .newBuilder()
                        .setSystemSeatId(seatId)
                        .setType(ClientMessageType.ConnectReq_097b)
                        .setConnectReq(ConnectReq.newBuilder())
                        .build()
                        .toByteString(),
                ).build()
                .toByteString(),
        ).build()
        .toByteArray()
