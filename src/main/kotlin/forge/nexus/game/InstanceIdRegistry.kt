package forge.nexus.game

import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional mapping between Forge card IDs and Arena instanceIds.
 *
 * Arena's protocol uses opaque `instanceId` integers to identify game objects.
 * Forge uses its own `Card.id` sequence. This registry translates between the two,
 * allocating fresh instanceIds on first sight and supporting reallocation when
 * cards change zones (Arena assigns new IDs on zone transfer).
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
    data class IdReallocation(val old: Int, val new: Int)

    /** Allocate or return existing Arena instanceId for a Forge card ID. */
    fun getOrAlloc(forgeCardId: Int): Int =
        forgeIdToInstanceId.computeIfAbsent(forgeCardId) {
            val id = nextInstanceId++
            instanceIdToForgeId[id] = forgeCardId
            id
        }

    /**
     * Allocate a fresh instanceId for a Forge card that changed zones.
     * Updates forward map (forgeCardId → new ID), keeps old ID in reverse map.
     */
    fun realloc(forgeCardId: Int): IdReallocation {
        val oldId = forgeIdToInstanceId[forgeCardId]
            ?: return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
        val newId = nextInstanceId++
        forgeIdToInstanceId[forgeCardId] = newId
        instanceIdToForgeId[newId] = forgeCardId
        // old reverse entry kept intentionally — client may reference old IDs
        return IdReallocation(oldId, newId)
    }

    /** Reverse lookup: Arena instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: Int): Int? = instanceIdToForgeId[instanceId]

    /** Read-only snapshot of instanceId → forgeCardId (for debug panel). */
    fun snapshot(): Map<Int, Int> = HashMap(instanceIdToForgeId)
}
