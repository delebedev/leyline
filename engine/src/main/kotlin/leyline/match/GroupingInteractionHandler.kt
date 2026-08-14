package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned Scry and Surveil windows. */
internal class GroupingInteractionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(GroupingInteractionHandler::class.java)

    fun onGroupResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val runtime = ctx.bridge.cutCoordinator.grouping
        val pending = runtime.current()
        if (pending == null) {
            log.warn("GroupResp did not match a published Grouping interaction")
            DevCheck.failOnAutoPass { "GroupResp did not match a published Grouping interaction" }
            return
        }
        val groups = greMsg.groupResp.groupsList
        if (groups.size != 2) {
            log.warn("GroupResp must contain the exact top and away groups")
            DevCheck.failOnAutoPass { "GroupResp must contain the exact top and away groups" }
            return
        }
        val topIds = groups.getOrNull(0)?.idsList.orEmpty()
        val awayIds = groups.getOrNull(1)?.idsList.orEmpty()
        if (!runtime.submit(pending.interactionId, greMsg.gameStateId, topIds, awayIds)) {
            log.warn("GroupResp did not match the current Grouping interaction")
            DevCheck.failOnAutoPass { "GroupResp did not match the current Grouping interaction" }
            return
        }
        ctx.bridge.awaitPriority()
        autoPass()
    }
}
