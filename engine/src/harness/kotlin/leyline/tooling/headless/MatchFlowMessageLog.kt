package leyline.tooling.headless

import leyline.game.bundle.PROMPT_GRE_TYPES
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

internal class MatchFlowMessageLog(
    private val messages: List<GREToClientMessage>,
) {
    fun snapshot(): Int = messages.size

    fun since(snapshot: Int): List<GREToClientMessage> = messages.subList(snapshot, messages.size).toList()

    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> =
        since(snapshot).mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }

    fun annotationsSince(snapshot: Int): List<AnnotationInfo> = gameStateMessagesSince(snapshot).flatMap { it.annotationsList }

    fun latestPrompt(): GREToClientMessage? {
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.type in PROMPT_GRE_TYPES) return message
        }
        return null
    }

    fun latestPromptGsId(): Int = latestPrompt()?.gameStateId ?: 0

    fun latestPromptMsgId(): Int = latestPrompt()?.msgId ?: 0
}
