package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Simulates a real client's state accumulator.
 *
 * Processes GREToClientMessage stream. Full replaces; Diff merges.
 * Tracks objects, zones, actions, turnInfo -- enough to assert session-level
 * invariants like "every action instanceId exists in known objects".
 *
 * Test-only utility -- not part of production code.
 */
class ClientAccumulator {

    /** instanceId -> GameObjectInfo (latest version). Full replaces all; Diff adds/updates. */
    val objects = mutableMapOf<Int, GameObjectInfo>()

    /** zoneId -> ZoneInfo (latest version). */
    val zones = mutableMapOf<Int, ZoneInfo>()

    /** Latest turnInfo from most recent GameStateMessage. */
    var turnInfo: TurnInfo? = null
        private set

    /** Latest ActionsAvailableReq received. */
    var actions: ActionsAvailableReq? = null
        private set

    /** High-water mark of gsId seen. */
    var latestGsId: Int = 0
        private set

    /** Total messages processed. */
    var messageCount: Int = 0
        private set

    /** All gsIds seen in order (for diagnostics). */
    val gsIdHistory = mutableListOf<Int>()

    /** Process a single GRE message. */
    fun process(gre: GREToClientMessage) {
        messageCount++

        when {
            gre.hasGameStateMessage() -> processGameState(gre.gameStateMessage)
            gre.hasActionsAvailableReq() -> actions = gre.actionsAvailableReq
        }
    }

    /** Process a list of GRE messages (one bundle). */
    fun processAll(messages: List<GREToClientMessage>) {
        messages.forEach { process(it) }
    }

    /** Seed the accumulator with a Full GSM (simulates handshake baseline). */
    fun seedFull(gsm: GameStateMessage) {
        require(gsm.type == GameStateType.Full) { "seedFull requires Full GSM, got ${gsm.type}" }
        processGameState(gsm)
    }

    // --- Invariant checks ---

    /**
     * Every instanceId in current ActionsAvailableReq must exist in [objects].
     * Returns list of missing instanceIds (empty = invariant holds).
     */
    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        val missing = mutableListOf<Int>()
        for (action in req.actionsList) {
            if (action.instanceId != 0 && !objects.containsKey(action.instanceId)) {
                missing.add(action.instanceId)
            }
        }
        return missing
    }

    /**
     * Every instanceId referenced by a **visible** zone must exist in [objects].
     * Returns list of (zoneId, instanceId) pairs where object is missing.
     *
     * Hidden zones (Library) and Private zones (opponent Hand, Sideboard)
     * intentionally carry objectInstanceIds without matching GameObjectInfo —
     * the real server does the same. The client uses zone counts for UI
     * (e.g. "52 cards in library") but never renders hidden card details.
     */
    fun zoneObjectsMissingFromObjects(): List<Pair<Int, Int>> {
        val missing = mutableListOf<Pair<Int, Int>>()
        for ((zoneId, zone) in zones) {
            // Skip hidden/private zones — real server sends objectInstanceIds
            // without GameObjectInfo for these (library, opponent hand, sideboard).
            // Also skip Limbo — it's a protocol bookkeeping zone, not rendered.
            if (zone.visibility == Visibility.Hidden || zone.visibility == Visibility.Private) continue
            if (zone.type == ZoneType.Limbo) continue
            for (iid in zone.objectInstanceIdsList) {
                if (!objects.containsKey(iid)) {
                    missing.add(zoneId to iid)
                }
            }
        }
        return missing
    }

    // --- Internal ---

    private fun processGameState(gs: GameStateMessage) {
        val gsId = gs.gameStateId
        gsIdHistory.add(gsId)
        if (gsId > latestGsId) latestGsId = gsId

        if (gs.hasTurnInfo()) turnInfo = gs.turnInfo

        when (gs.type) {
            GameStateType.Full -> {
                objects.clear()
                zones.clear()
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            GameStateType.Diff -> {
                // Remove deleted instances first (real server sends these for retired IDs)
                gs.diffDeletedInstanceIdsList.forEach { objects.remove(it) }
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            else -> {} // ignore
        }
    }
}
