package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned iterative and one-shot PayCosts windows. */
internal class ManaSourcePaymentHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(ManaSourcePaymentHandler::class.java)

    fun tryHandlePerformAction(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val actions = greMsg.performActionResp.actionsList
        if (actions.none { it.actionType == ActionType.MakePayment || it.actionType == ActionType.Pass }) return false
        val runtime = ctx.bridge.cutCoordinator.manaSourcePayments
        val pending = runtime.current() ?: return false
        val selectedIds =
            actions
                .flatMap { action ->
                    buildList {
                        if (action.actionType == ActionType.MakePayment && action.instanceId != 0) add(action.instanceId)
                        action.manaSelectionsList.mapNotNullTo(this) { it.instanceId.takeIf { id -> id != 0 } }
                    }
                }.distinct()
        val receipt =
            if (actions.any { it.actionType == ActionType.Pass }) {
                runtime.complete(pending.interactionId, greMsg.gameStateId, selectedIds)
            } else {
                runtime.select(pending.interactionId, greMsg.gameStateId, selectedIds)
            }
        if (receipt == null) {
            log.warn("Mana-source payment action did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source payment action did not match the current interaction" }
            return true
        }
        deliver(receipt, autoPass)
        return true
    }

    fun tryHandleCancel(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.manaSourcePayments
        val pending = runtime.current() ?: return false
        val receipt = runtime.cancel(pending.interactionId, greMsg.gameStateId)
        if (receipt == null) {
            log.warn("Mana-source payment cancel did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source payment cancel did not match the current interaction" }
            return true
        }
        deliver(receipt, autoPass)
        return true
    }

    fun tryHandleEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.manaSourcePayments
        val pending = runtime.current() ?: return false
        val selectedIds = greMsg.effectCostResp.costSelection.idsList
        val receipt = runtime.complete(pending.interactionId, greMsg.gameStateId, selectedIds)
        if (receipt == null) {
            log.warn("Mana-source EffectCost response did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source EffectCost response did not match the current interaction" }
            return true
        }
        deliver(receipt, autoPass)
        return true
    }

    fun tryHandleOneShotEffectCost(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.oneShotPayCosts
        val pending = runtime.current() ?: return false
        val accepted =
            runtime.submit(
                pending.interactionId,
                greMsg.gameStateId,
                greMsg.effectCostResp.costSelection.idsList,
            )
        if (!accepted) {
            log.warn("One-shot PayCosts response did not match the current interaction")
            DevCheck.failOnAutoPass { "One-shot PayCosts response did not match the current interaction" }
            return true
        }
        ctx.bridge.awaitPriority()
        autoPass()
        return true
    }

    fun tryHandleOneShotCancel(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val runtime = ctx.bridge.cutCoordinator.oneShotPayCosts
        val pending = runtime.current() ?: return false
        if (!runtime.cancel(pending.interactionId, greMsg.gameStateId)) return true
        ctx.bridge.awaitPriority()
        autoPass()
        return true
    }

    private fun deliver(
        receipt: leyline.bridge.handoff.ManaSourcePaymentCommandReceipt,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        receipt.deliveryToken?.let { token ->
            val batches = bridge.cutCoordinator.drain(counters.seatId)
            try {
                batches.forEach(sink::sendBundledGRE)
            } catch (ex: Exception) {
                bridge.cutCoordinator.failDelivery(ex)
            }
            check(bridge.cutCoordinator.manaSourcePayments.acknowledgeDelivery(receipt.interactionId, token)) {
                "Mana-source payment delivery acknowledgement was stale"
            }
        }
        if (receipt.completed) {
            bridge.awaitPriority()
            autoPass()
        }
    }
}
