package leyline.game.state

/** Engine-thread-owned recipient identities survive registry rebuilds without encoding printed slots. */
class GrantedAbilityIds {
    internal data class Key(
        val timestamp: Long,
        val staticId: Long,
        val definitionId: Int,
    )

    private val active = mutableMapOf<Key, Int>()
    private var nextId = FIRST_GRANTED_ID

    internal fun retain(keys: Set<Key>) {
        active.keys.retainAll(keys)
    }

    internal fun idFor(key: Key): Int =
        active.getOrPut(key) {
            check(nextId < Int.MAX_VALUE) { "Granted ability identity space exhausted" }
            nextId++
        }

    companion object {
        // Printed, ward and type-derived rows occupy the low ordinal namespace.
        const val FIRST_GRANTED_ID: Int = 1 shl 30
    }
}
