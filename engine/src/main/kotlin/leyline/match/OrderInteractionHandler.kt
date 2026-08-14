package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned ordered-card windows. */
internal class OrderInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(OrderInteractionHandler::class.java)

    fun onOrderResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val runtime = ctx.bridge.cutCoordinator.order
        val pending = runtime.current()
        if (pending == null) {
            log.warn("OrderResp did not match a published Order interaction")
            DevCheck.failOnAutoPass { "OrderResp did not match a published Order interaction" }
            return
        }
        if (!runtime.submit(pending.interactionId, greMsg.gameStateId, greMsg.orderResp.idsList)) {
            log.warn("OrderResp did not match the current Order interaction")
            DevCheck.failOnAutoPass { "OrderResp did not match the current Order interaction" }
            return
        }
        ctx.bridge.awaitPriority()
        autoPass()
    }
}
