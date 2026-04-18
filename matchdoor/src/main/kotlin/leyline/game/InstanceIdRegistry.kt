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
 * Thread-safe reads (concurrent maps). Writes (getOrAlloc, realloc) are engine-thread-only.
 * One registry per game.
 */
class InstanceIdRegistry(startId: Int = 100) {
    private val forgeIdToInstanceId = ConcurrentHashMap<ForgeCardId, InstanceId>()
    private val instanceIdToForgeId = ConcurrentHashMap<InstanceId, ForgeCardId>()
    private var nextInstanceId = startId

    /**
     * Result of reallocating an instanceId for a zone transfer.
     * [old] is the previous instanceId (retired to Limbo), [new] is the freshly allocated one.
     */
    data class IdReallocation(val old: InstanceId, val new: InstanceId)

    /** Allocate or return existing client instanceId for a Forge card ID. */
    fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
        forgeIdToInstanceId.computeIfAbsent(forgeCardId) {
            val id = InstanceId(nextInstanceId++)
            instanceIdToForgeId[id] = forgeCardId
            id
        }

    /**
     * Allocate a fresh instanceId for a Forge card that changed zones.
     * Updates forward map (forgeCardId → new ID), keeps old ID in reverse map.
     */
    fun realloc(forgeCardId: ForgeCardId): IdReallocation {
        val oldId = forgeIdToInstanceId[forgeCardId]
            ?: return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
        val newId = InstanceId(nextInstanceId++)
        forgeIdToInstanceId[forgeCardId] = newId
        instanceIdToForgeId[newId] = forgeCardId
        // old reverse entry kept intentionally — client may reference old IDs
        return IdReallocation(oldId, newId)
    }

    /**
     * Compute the planned reallocation WITHOUT committing.
     *
     * Returns the [IdReallocation] that [applyRealloc] would commit. Deterministic:
     * the returned `new` id is the current `nextInstanceId` value. Caller MUST apply
     * the returned plan before calling `planRealloc` again on a different fid, or
     * call [planReallocBatch] for multiple fids.
     *
     * Used by [leyline.game.ZoneTransferDetector] during the pure-diff compute phase;
     * `bridge.applyMutations` commits the plans afterwards via [applyRealloc].
     */
    fun planRealloc(forgeCardId: ForgeCardId): IdReallocation {
        val oldId = forgeIdToInstanceId[forgeCardId]
            ?: return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
        val newId = InstanceId(nextInstanceId) // does NOT increment
        return IdReallocation(oldId, newId)
    }

    /**
     * Plan a batch of reallocations, threading the counter forward across fids.
     * Returns reallocations in the same order as [fids].
     */
    fun planReallocBatch(fids: List<ForgeCardId>): List<IdReallocation> {
        if (fids.isEmpty()) return emptyList()
        var counter = nextInstanceId
        return fids.map { fid ->
            val oldId = forgeIdToInstanceId[fid]
            if (oldId == null) {
                val newId = InstanceId(counter++)
                IdReallocation(newId, newId)
            } else {
                val newId = InstanceId(counter++)
                IdReallocation(oldId, newId)
            }
        }
    }

    /**
     * Apply a previously-planned reallocation.
     *
     * Updates forward map to [realloc.new]; keeps reverse entries for both old and
     * new ids (preserves retired-id lookups). Advances [nextInstanceId] if the plan's
     * new id is >= the current counter.
     */
    fun applyRealloc(realloc: IdReallocation) {
        val fid = instanceIdToForgeId[realloc.old] ?: instanceIdToForgeId[realloc.new]
        if (fid == null) {
            // First-seen case — caller's plan was for a fid we don't know yet.
            // Shouldn't happen in normal bundle flow; safe no-op.
            return
        }
        forgeIdToInstanceId[fid] = realloc.new
        instanceIdToForgeId[realloc.new] = fid
        if (realloc.new.value >= nextInstanceId) {
            nextInstanceId = realloc.new.value + 1
        }
    }

    /**
     * Nuke-and-repave: clear all active mappings and return the old instanceIds.
     *
     * Used for mulligan DealHand where the protocol deletes every previous
     * instanceId via `diffDeletedInstanceIds` and issues entirely fresh IDs.
     * The reverse map is also cleared so old IDs don't resolve.
     */
    fun resetAll(): List<InstanceId> {
        val oldIds = forgeIdToInstanceId.values.toList()
        forgeIdToInstanceId.clear()
        instanceIdToForgeId.clear()
        return oldIds
    }

    /**
     * Allocate a synthetic instanceId not mapped to any Forge card.
     * Used for RevealedCard proxy objects that mirror real cards.
     */
    fun allocSynthetic(): InstanceId = InstanceId(nextInstanceId++)

    /** Reverse lookup: client instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId? =
        instanceIdToForgeId[instanceId]

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun snapshot(): Map<InstanceId, ForgeCardId> = HashMap(instanceIdToForgeId)

    /** Read-only snapshot of forgeCardId → current active instanceId. */
    fun activeSnapshot(): Map<ForgeCardId, InstanceId> = HashMap(forgeIdToInstanceId)
}
