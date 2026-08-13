package leyline.game.state

/**
 * Caches resolved token grpId per instanceId.
 *
 * Populated at first resolution (during [leyline.game.snapshot.GrpIdResolver.resolve]),
 * consulted on every subsequent GSM build. Eliminates fragile re-resolution via Forge
 * runtime references that can break between diff ticks (e.g. detached spawning ability).
 *
 * Copy tokens store the source permanent's grpId — resolved via
 * [forge.game.card.Card.getCopiedPermanent] at creation time.
 *
 * Lifecycle: one per [GameBridge]. Entries retired when instanceIds move to Limbo.
 */
class TokenIdentityRegistry internal constructor(
    private val grpIds: MutableMap<Int, Int> = mutableMapOf(),
    private val read: ((Int) -> Int?)? = null,
    private val write: ((Int, Int) -> Unit)? = null,
    private val remove: ((Int) -> Unit)? = null,
    private val clearAll: (() -> Unit)? = null,
) {
    /** Register a token's resolved grpId. Idempotent — first write wins. */
    fun register(
        instanceId: Int,
        grpId: Int,
    ) {
        write?.invoke(instanceId, grpId) ?: grpIds.putIfAbsent(instanceId, grpId)
    }

    /** Look up a previously registered token grpId, or null if unregistered. */
    fun resolve(instanceId: Int): Int? = read?.invoke(instanceId) ?: grpIds[instanceId]

    /** Remove entry for a retired instanceId. */
    fun retire(instanceId: Int) {
        remove?.invoke(instanceId) ?: grpIds.remove(instanceId)
    }

    /** Remove all entries (puzzle hot-swap). */
    fun clear() {
        clearAll?.invoke() ?: grpIds.clear()
    }
}
