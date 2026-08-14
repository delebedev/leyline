package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned static enum SelectN windows. */
internal class StaticChoiceInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(StaticChoiceInteractionHandler::class.java)

    fun tryHandleSelectN(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.staticChoices
        val pending = runtime.current() ?: return false
        if (!runtime.submit(pending.interactionId, greMsg.gameStateId, greMsg.selectNResp.idsList)) {
            log.warn("StaticChoice response did not match the current interaction")
            DevCheck.failOnAutoPass { "StaticChoice response did not match the current interaction" }
            return true
        }
        ctx.bridge.awaitPriority()
        autoPass()
        return true
    }
}
