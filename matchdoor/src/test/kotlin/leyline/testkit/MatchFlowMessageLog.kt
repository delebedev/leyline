package leyline.testkit

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

internal class MatchFlowMessageLog(
    private val messages: List<GREToClientMessage>,
) {
    fun snapshot(): Int = messages.size

    fun since(snapshot: Int): List<GREToClientMessage> =
        messages.subList(snapshot, messages.size).toList()

    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> =
        since(snapshot).mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

    fun annotationsSince(snapshot: Int): List<AnnotationInfo> =
        gameStateMessagesSince(snapshot).flatMap { it.annotationsList }

    fun latestPromptGsId(): Int {
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.type in harnessPromptGreTypes) return message.gameStateId
        }
        return 0
    }
}

private val harnessPromptGreTypes: Set<GREMessageType> =
    setOf(
        GREMessageType.ActionsAvailableReq_695e,
        GREMessageType.SelectTargetsReq_695e,
        GREMessageType.SelectNreq,
        GREMessageType.GroupReq_695e,
        GREMessageType.SearchReq_695e,
        GREMessageType.DeclareAttackersReq_695e,
        GREMessageType.DeclareBlockersReq_695e,
        GREMessageType.CastingTimeOptionsReq_695e,
        GREMessageType.PayCostsReq_695e,
        GREMessageType.PromptReq,
        GREMessageType.OptionalActionMessage_695e,
        GREMessageType.AssignDamageReq_695e,
    )
