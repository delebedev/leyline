package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

/**
 * InstanceIds for an active reveal-choose effect.
 *
 * Projection uses [withTentativeState] so reveal lookup, retention, and
 * allocation stay private until the enclosing frame mutation batch commits.
 * Insertion order is preserved because cleanup annotations expose allocation
 * order to the client.
 */
class RevealProxyTracker {
    data class State(
        val proxies: Map<ForgeCardId, InstanceId>,
    )

    data class Transition(
        val baselineVersion: Long,
        val nextState: State,
    )

    private val lock = Any()
    private var version = 0L
    private var state = State(emptyMap())
    private val tentative = ThreadLocal<Planner?>()

    val isEmpty: Boolean
        get() = tentative.get()?.isEmpty() ?: synchronized(lock) { state.proxies.isEmpty() }

    fun <T> withTentativeState(block: () -> T): T {
        if (tentative.get() != null) return block()
        val committed = synchronized(lock) { version to state }
        tentative.set(Planner(committed.first, committed.second))
        return try {
            block()
        } finally {
            tentative.remove()
        }
    }

    fun tentativeTransition(): Transition =
        checkNotNull(tentative.get()) {
            "No tentative reveal state is active"
        }.transition()

    fun commit(transition: Transition): Boolean =
        synchronized(lock) {
            if (version != transition.baselineVersion) return false
            state = transition.nextState.copyMaps()
            version++
            true
        }

    fun lookup(forgeCardId: ForgeCardId): InstanceId? =
        tentative.get()?.lookup(forgeCardId) ?: synchronized(lock) { state.proxies[forgeCardId] }

    fun allocate(
        forgeCardId: ForgeCardId,
        id: InstanceId,
    ) {
        tentative.get()?.allocate(forgeCardId, id) ?: synchronized(lock) {
            val next = state.proxies.toMutableMap()
            next[forgeCardId] = id
            state = State(next)
            version++
        }
    }

    /** Remove views outside the current reveal set, preserving allocation order. */
    fun retain(activeCardIds: Set<ForgeCardId>): List<InstanceId> =
        tentative.get()?.retain(activeCardIds) ?: synchronized(lock) {
            val removed =
                state.proxies
                    .filterKeys { it !in activeCardIds }
                    .values
                    .toList()
            if (removed.isNotEmpty()) {
                state = State(state.proxies.filterKeys { it in activeCardIds })
                version++
            }
            removed
        }

    /** Empty the tracker and return the ids that were cleared. */
    fun drain(): List<InstanceId> =
        tentative.get()?.drain() ?: synchronized(lock) {
            val out = state.proxies.values.toList()
            if (out.isNotEmpty()) {
                state = State(emptyMap())
                version++
            }
            out
        }

    fun clear() {
        tentative.get()?.clear() ?: synchronized(lock) {
            if (state.proxies.isNotEmpty()) {
                state = State(emptyMap())
                version++
            }
        }
    }

    private class Planner(
        private val baselineVersion: Long,
        initial: State,
    ) {
        private val proxies = initial.proxies.toMutableMap()

        fun isEmpty(): Boolean = proxies.isEmpty()

        fun lookup(forgeCardId: ForgeCardId): InstanceId? = proxies[forgeCardId]

        fun allocate(
            forgeCardId: ForgeCardId,
            id: InstanceId,
        ) {
            proxies[forgeCardId] = id
        }

        fun retain(activeCardIds: Set<ForgeCardId>): List<InstanceId> {
            val removed = proxies.filterKeys { it !in activeCardIds }.values.toList()
            proxies.keys.retainAll(activeCardIds)
            return removed
        }

        fun drain(): List<InstanceId> = proxies.values.toList().also { proxies.clear() }

        fun clear() {
            proxies.clear()
        }

        fun transition(): Transition = Transition(baselineVersion, State(proxies.toMap()))
    }

    private fun State.copyMaps(): State = State(proxies.toMap())
}
