package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional mapping between Forge card IDs and client instanceIds.
 *
 * The client protocol uses opaque `instanceId` integers to identify game objects.
 * Forge uses its own `Card.id` sequence. This registry translates between the two,
 * allocating fresh instanceIds on first sight and supporting reallocation when
 * cards change zones (the protocol assigns new IDs on zone transfer).
 *
 * Thread-safe: concurrent maps + atomic counter. One registry per game.
 */
class InstanceIdRegistry(startId: Int = 100) {
    private val forgeIdToInstanceId = ConcurrentHashMap<Int, Int>()
    private val instanceIdToForgeId = ConcurrentHashMap<Int, Int>()
    private var nextInstanceId = startId

    /**
     * Result of reallocating an instanceId for a zone transfer.
     * [old] is the previous instanceId (retired to Limbo), [new] is the freshly allocated one.
     */
    data class IdReallocation(val old: InstanceId, val new: InstanceId)

    /** Allocate or return existing client instanceId for a Forge card ID. */
    fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
        InstanceId(
            forgeIdToInstanceId.computeIfAbsent(forgeCardId.value) {
                val id = nextInstanceId++
                instanceIdToForgeId[id] = forgeCardId.value
                id
            },
        )

    /**
     * Allocate a fresh instanceId for a Forge card that changed zones.
     * Updates forward map (forgeCardId → new ID), keeps old ID in reverse map.
     */
    fun realloc(forgeCardId: ForgeCardId): IdReallocation {
        val oldRaw = forgeIdToInstanceId[forgeCardId.value]
            ?: return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
        val newRaw = nextInstanceId++
        forgeIdToInstanceId[forgeCardId.value] = newRaw
        instanceIdToForgeId[newRaw] = forgeCardId.value
        // old reverse entry kept intentionally — client may reference old IDs
        return IdReallocation(InstanceId(oldRaw), InstanceId(newRaw))
    }

    /**
     * Nuke-and-repave: clear all active mappings and return the old instanceIds.
     *
     * Used for mulligan DealHand where the real server deletes every previous
     * instanceId via `diffDeletedInstanceIds` and issues entirely fresh IDs.
     * The reverse map is also cleared so old IDs don't resolve.
     */
    fun resetAll(): List<InstanceId> {
        val oldIds = forgeIdToInstanceId.values.map { InstanceId(it) }
        forgeIdToInstanceId.clear()
        instanceIdToForgeId.clear()
        return oldIds
    }

    /** Reverse lookup: client instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId? =
        instanceIdToForgeId[instanceId.value]?.let { ForgeCardId(it) }

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun snapshot(): Map<Int, Int> = HashMap(instanceIdToForgeId)

    /** Read-only snapshot of forgeCardId → current active instanceId. */
    fun activeSnapshot(): Map<Int, Int> = HashMap(forgeIdToInstanceId)
}
