package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bidirectional mapping between Forge card IDs and client instanceIds.
 *
 * The client protocol uses opaque `instanceId` integers to identify game objects.
 * Forge uses its own `Card.id` sequence. This registry translates between the two,
 * allocating fresh instanceIds on first sight and supporting reallocation when
 * cards change zones (the protocol assigns new IDs on zone transfer).
 *
 * Thread-safe reads and id-counter allocation. Forward/reverse map updates are
 * concurrent-map writes; callers must still avoid semantically racing zone
 * transfers for the same card.
 * One registry per game.
 */
class InstanceIdRegistry(
    startId: Int = 100,
) {
    private val forgeIdToInstanceId = ConcurrentHashMap<ForgeCardId, InstanceId>()
    private val instanceIdToForgeId = ConcurrentHashMap<InstanceId, ForgeCardId>()
    private val nextInstanceId = AtomicInteger(startId)

    /**
     * Result of reallocating an instanceId for a zone transfer.
     * [old] is the previous instanceId (retired to Limbo), [new] is the freshly allocated one.
     */
    data class IdReallocation(
        val old: InstanceId,
        val new: InstanceId,
    )

    /**
     * Return the current mapped instanceId for [forgeCardId], or `null` if none is mapped.
     * Unlike [getOrAlloc], never mutates — suitable for pure-compute paths.
     */
    fun peek(forgeCardId: ForgeCardId): InstanceId? = forgeIdToInstanceId[forgeCardId]

    /**
     * Reserve the next instanceId WITHOUT touching the forward/reverse maps.
     *
     * Used by [leyline.game.annotations.ZoneTransferDetector] during `buildDiff` compute: each zone-transfer
     * plan needs a unique id that won't collide with later [getOrAlloc] calls in the
     * same pass. Reserving the counter slot here guarantees uniqueness; the map commit
     * happens later in [applyRealloc] via `bridge.applyMutations`. Keeps
     * ordering-sensitive map writes out of compute while preserving id uniqueness.
     */
    fun reserveNextInstanceId(): InstanceId = InstanceId(nextInstanceId.getAndIncrement())

    /** Allocate or return existing client instanceId for a Forge card ID. */
    fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
        forgeIdToInstanceId.computeIfAbsent(forgeCardId) {
            val id = InstanceId(nextInstanceId.getAndIncrement())
            instanceIdToForgeId[id] = forgeCardId
            id
        }

    /**
     * Allocate a fresh instanceId for a Forge card that changed zones.
     * Updates forward map (forgeCardId → new ID), keeps old ID in reverse map.
     */
    fun realloc(forgeCardId: ForgeCardId): IdReallocation {
        val oldId =
            forgeIdToInstanceId[forgeCardId]
                ?: return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
        val newId = InstanceId(nextInstanceId.getAndIncrement())
        forgeIdToInstanceId[forgeCardId] = newId
        instanceIdToForgeId[newId] = forgeCardId
        // old reverse entry kept intentionally — client may reference old IDs
        return IdReallocation(oldId, newId)
    }

    /**
     * Reserve a reallocation without changing either identity map.
     *
     * The card must already be mapped: order-prompt candidates have previously
     * been projected, so a missing mapping indicates an invalid frame input.
     */
    fun planRealloc(forgeCardId: ForgeCardId): IdReallocation {
        val oldId = checkNotNull(forgeIdToInstanceId[forgeCardId]) { "Cannot plan reallocation for unmapped card $forgeCardId" }
        return IdReallocation(oldId, reserveNextInstanceId())
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
        nextInstanceId.updateAndGet { current -> maxOf(current, realloc.new.value + 1) }
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
    fun allocSynthetic(): InstanceId = InstanceId(nextInstanceId.getAndIncrement())

    /** Reverse lookup: client instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId? = instanceIdToForgeId[instanceId]

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun snapshot(): Map<InstanceId, ForgeCardId> = HashMap(instanceIdToForgeId)
}
