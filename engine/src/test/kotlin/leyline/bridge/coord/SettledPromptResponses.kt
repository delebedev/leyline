package leyline.bridge.coord

import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.GroupResp

internal fun MatchCutCoordinator.admitSettled(
    message: ClientToGREMessage,
    gameStateId: Int,
    respId: Int = bridge.committedSequence().lastPromptMsgId,
): SettledPromptAdmission =
    prompts.settled.admit(
        message
            .toBuilder()
            .setGameStateId(gameStateId)
            .setRespId(respId)
            .build(),
    )

internal fun MatchCutCoordinator.acceptSettled(
    message: ClientToGREMessage,
    gameStateId: Int,
    respId: Int = bridge.committedSequence().lastPromptMsgId,
): Boolean = admitSettled(message, gameStateId, respId) is SettledPromptAdmission.Accepted

internal fun groupResp(
    topIds: List<Int>,
    awayIds: List<Int>,
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(ClientMessageType.GroupResp_097b)
        .setGroupResp(
            GroupResp
                .newBuilder()
                .addGroups(Group.newBuilder().addAllIds(topIds))
                .addGroups(Group.newBuilder().addAllIds(awayIds)),
        ).build()
