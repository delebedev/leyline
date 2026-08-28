package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned divided allocations. */
internal class DistributionInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(DistributionInteractionHandler::class.java)

    fun onDistributionResp(greMsg: ClientToGREMessage): Boolean {
        val runtime = ctx.bridge.cutCoordinator.distribution
        val pending = runtime.current()
        if (pending == null) {
            log.warn("DistributionResp did not match a published Distribution interaction")
            DevCheck.failOnAutoPass { "DistributionResp did not match a published Distribution interaction" }
            return false
        }
        val rows = greMsg.distributionResp.distributionsList.map { it.instanceId to it.amount }
        if (!runtime.submitWire(pending.interactionId, greMsg.gameStateId, rows)) {
            log.warn("DistributionResp did not match or validate against the current Distribution interaction")
            DevCheck.failOnAutoPass { "DistributionResp did not match or validate against the current Distribution interaction" }
            return false
        }
        return true
    }
}
