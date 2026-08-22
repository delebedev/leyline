package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned competing-replacement windows. */
internal class ReplacementInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(ReplacementInteractionHandler::class.java)

    fun onSelectReplacementResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val runtime = ctx.bridge.cutCoordinator.replacement
        val pending = runtime.current()
        if (pending == null) {
            log.warn("SelectReplacementResp did not match a published Replacement interaction")
            DevCheck.failOnAutoPass { "SelectReplacementResp did not match a published Replacement interaction" }
            return
        }
        if (!greMsg.hasSelectReplacementResp()) {
            log.warn("SelectReplacementResp lacked a replacement row")
            DevCheck.failOnAutoPass { "SelectReplacementResp lacked a replacement row" }
            return
        }
        if (!runtime.submitWire(pending.interactionId, greMsg.gameStateId, greMsg.selectReplacementResp.replacement)) {
            log.warn("SelectReplacementResp did not match or validate against the current Replacement interaction")
            DevCheck.failOnAutoPass { "SelectReplacementResp did not match or validate against the current Replacement interaction" }
            return
        }
        ctx.bridge.awaitPriority()
        autoPass()
    }
}
