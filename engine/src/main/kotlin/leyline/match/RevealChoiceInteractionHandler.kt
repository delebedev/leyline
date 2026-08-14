package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned reveal-backed SelectN windows. */
internal class RevealChoiceInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(RevealChoiceInteractionHandler::class.java)

    fun tryHandleSelectN(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.revealChoices
        val pending = runtime.current() ?: return false
        if (!runtime.submit(pending.interactionId, greMsg.gameStateId, greMsg.selectNResp.idsList)) {
            log.warn("RevealChoice response did not match the current interaction")
            DevCheck.failOnAutoPass { "RevealChoice response did not match the current interaction" }
            return true
        }
        ctx.bridge.awaitPriority()
        autoPass()
        return true
    }
}
