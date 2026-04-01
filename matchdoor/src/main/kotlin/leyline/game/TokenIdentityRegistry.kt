package leyline.game

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches resolved token grpId per instanceId.
 *
 * Populated at first resolution (during [leyline.game.mapper.ObjectMapper.resolveGrpId]),
 * consulted on every subsequent GSM build. Eliminates fragile re-resolution via Forge
 * runtime references that can break between diff ticks (e.g. detached spawning ability).
 *
 * Copy tokens store the source permanent's grpId — resolved via
 * [forge.game.card.Card.getCopiedPermanent] at creation time.
 *
 * Lifecycle: one per [GameBridge]. Entries retired when instanceIds move to Limbo.
 */
class TokenIdentityRegistry {

    private val grpIds = ConcurrentHashMap<Int, Int>()

    /** Register a token's resolved grpId. Idempotent — first write wins. */
    fun register(instanceId: Int, grpId: Int) {
        grpIds.putIfAbsent(instanceId, grpId)
    }

    /** Look up a previously registered token grpId, or null if unregistered. */
    fun resolve(instanceId: Int): Int? = grpIds[instanceId]

    /** Remove entry for a retired instanceId. */
    fun retire(instanceId: Int) {
        grpIds.remove(instanceId)
    }

    /** Number of tracked tokens (test visibility). */
    fun size(): Int = grpIds.size
}
