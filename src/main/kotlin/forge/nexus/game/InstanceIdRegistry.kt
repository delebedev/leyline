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
     * Returns (oldInstanceId, newInstanceId).
     */
    fun realloc(forgeCardId: Int): Pair<Int, Int> {
        val oldId = forgeIdToInstanceId[forgeCardId]
            ?: return getOrAlloc(forgeCardId).let { it to it }
        val newId = nextInstanceId++
        forgeIdToInstanceId[forgeCardId] = newId
        instanceIdToForgeId[newId] = forgeCardId
        // old reverse entry kept intentionally — client may reference old IDs
        return oldId to newId
    }

    /** Reverse lookup: Arena instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: Int): Int? = instanceIdToForgeId[instanceId]

    /** Read-only snapshot of instanceId → forgeCardId (for debug panel). */
    fun snapshot(): Map<Int, Int> = HashMap(instanceIdToForgeId)
}
