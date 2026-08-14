package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned card-backed SelectN windows. */
internal class CardSelectInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(CardSelectInteractionHandler::class.java)

    fun tryHandleSelectN(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean = submit(greMsg, greMsg.selectNResp.idsList, autoPass, ctx.bridge.cutCoordinator.cardSelect::submitSelectN)

    fun tryHandleEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean =
        submit(
            greMsg,
            greMsg.effectCostResp.costSelection.idsList,
            autoPass,
            ctx.bridge.cutCoordinator.cardSelect::submitEffectCost,
        )

    private fun submit(
        greMsg: ClientToGREMessage,
        selectedInstanceIds: List<Int>,
        autoPass: () -> Unit,
        submitSelection: (String, Int, List<Int>) -> Boolean,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.cardSelect
        val pending = runtime.current() ?: return false
        if (!submitSelection(pending.interactionId, greMsg.gameStateId, selectedInstanceIds)) {
            log.warn("CardSelect response did not match the current interaction")
            DevCheck.failOnAutoPass { "CardSelect response did not match the current interaction" }
            return true
        }
        ctx.bridge.awaitPriority()
        autoPass()
        return true
    }
}
