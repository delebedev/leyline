package leyline.game.state

import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/** Zone history produced by one tentative projection, applied after frame assembly. */
data class ZoneProjectionLifecycle(
    val retiredIds: List<Int> = emptyList(),
    val zoneAssignments: List<Pair<Int, Int>> = emptyList(),
) {
    fun applyTo(editor: ProjectionState.Editor) {
        editor.limboInstanceIds += retiredIds
        zoneAssignments.forEach { (iid, zoneId) -> editor.protoZones[iid] = zoneId }
    }
}

/** Owns the structural and lifecycle consequences of ordered, already allocated handoffs. */
internal class ZoneHandoffProjection(
    objects: List<GameObjectInfo>,
    zones: List<ZoneInfo>,
) {
    val objects = objects.toMutableList()
    val zones = zones.toMutableList()
    private val retiredIds = mutableListOf<Int>()
    private val zoneAssignments = mutableListOf<Pair<Int, Int>>()

    /** The snapshot already places the card in [projectedZoneId], using [projectedInstanceId]. */
    fun apply(
        handoff: ZoneHandoff,
        projectedZoneId: Int = handoff.zoneAssignment.second,
        projectedInstanceId: Int = handoff.realloc.old.value,
        objectIndex: Int? = null,
    ) = applyChain(listOf(handoff), projectedZoneId, projectedInstanceId, objectIndex)

    /** Only the last destination survives a same-frame chain; every old lifetime retires. */
    fun applyChain(
        handoffs: List<ZoneHandoff>,
        projectedZoneId: Int = handoffs.last().zoneAssignment.second,
        projectedInstanceId: Int =
            handoffs
                .first()
                .realloc.old.value,
        objectIndex: Int? = null,
    ) {
        val final = handoffs.last()
        if (handoffs.any { it.limboRetirement != null }) {
            objectIndex?.let { index ->
                objects[index] = objects[index].toBuilder().setInstanceId(final.realloc.new.value).build()
            }
            replaceInZone(projectedZoneId, projectedInstanceId, final.realloc.new.value)
            handoffs.forEach { handoff -> handoff.limboRetirement?.let { retire(it.value) } }
        }
        recordZone(final.zoneAssignment.first.value to final.zoneAssignment.second)
    }

    /** Retirement also covers vanished stack abilities that have no card handoff. */
    fun retire(instanceId: Int) {
        retiredIds.add(instanceId)
        appendToZone(ZoneIds.LIMBO, instanceId)
    }

    /** Resulting-state observations include hidden cards and newly created objects. */
    fun recordZone(assignment: Pair<Int, Int>) {
        zoneAssignments.add(assignment)
    }

    fun lifecycle(): ZoneProjectionLifecycle = ZoneProjectionLifecycle(retiredIds.toList(), zoneAssignments.toList())

    fun replaceInZone(
        zoneId: Int,
        oldId: Int,
        newId: Int,
    ) {
        val index = zones.indexOfFirst { it.zoneId == zoneId }
        if (index < 0) return
        val zone = zones[index]
        val ids = zone.objectInstanceIdsList.toMutableList()
        val idIndex = ids.indexOf(oldId)
        if (idIndex < 0) return
        ids[idIndex] = newId
        zones[index] =
            zone
                .toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(ids)
                .build()
    }

    fun appendToZone(
        zoneId: Int,
        instanceId: Int,
    ) {
        val index = zones.indexOfFirst { it.zoneId == zoneId }
        if (index < 0) return
        zones[index] = zones[index].toBuilder().addObjectInstanceIds(instanceId).build()
    }
}
