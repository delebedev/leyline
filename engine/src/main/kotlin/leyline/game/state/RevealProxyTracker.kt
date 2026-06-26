package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

/**
 * Proxy instanceIds for an active reveal-choose effect. Lives on [GameBridge]
 * because proxy IDs are allocated via `bridge.ids` (shared synthetic sequence)
 * and read at diff-build time, not at prompt time. StateMapper drives
 * allocate / lookup / drain during GSM assembly.
 *
 * Insertion order is preserved so [drain] yields IDs in the order they were
 * allocated — downstream `RevealProxiesDeleted` events depend on a
 * deterministic cleanup order.
 */
class RevealProxyTracker {
    private val proxies: MutableMap<ForgeCardId, InstanceId> = LinkedHashMap()

    val size: Int get() = proxies.size
    val isEmpty: Boolean get() = proxies.isEmpty()

    fun lookup(forgeCardId: ForgeCardId): InstanceId? = proxies[forgeCardId]

    fun allocate(
        forgeCardId: ForgeCardId,
        id: InstanceId,
    ) {
        proxies[forgeCardId] = id
    }

    /** Empty the tracker and return the ids that were cleared (for RevealProxiesDeleted). */
    fun drain(): List<InstanceId> {
        val out: List<InstanceId> = proxies.values.toList()
        proxies.clear()
        return out
    }

    fun clear() {
        proxies.clear()
    }
}
