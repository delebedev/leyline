package leyline.game.state

/**
 * Tracks stable synthetic effect ids for current-frame keyed lifecycle effects.
 *
 * Consumers own protocol-specific annotation decisions. This class only answers:
 * keep an id while a key is live, allocate an id for a new key, and release ids
 * whose keys disappear from the current frame.
 */
internal class SyntheticEffectLifecycle<K>(
    private val nextEffectId: () -> Int,
) {
    data class Allocation(
        val effectId: Int,
        val created: Boolean,
    )

    private val active = mutableMapOf<K, Int>()

    fun getOrAllocId(key: K): Int = active.getOrPut(key, nextEffectId)

    fun getOrAlloc(key: K): Allocation {
        active[key]?.let { return Allocation(it, created = false) }
        val effectId = nextEffectId()
        active[key] = effectId
        return Allocation(effectId, created = true)
    }

    fun releaseMissing(currentKeys: Set<K>): List<Int> {
        val expired = active.keys - currentKeys
        return expired.mapNotNull { active.remove(it) }
    }

    fun clear() {
        active.clear()
    }
}
