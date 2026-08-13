package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

object RevealProxyTracker {
    data class State(
        val entries: Map<ForgeCardId, InstanceId>,
    )

    class Planner internal constructor(
        initial: State,
    ) {
        private val entries = initial.entries.toMutableMap()

        fun isEmpty(): Boolean = entries.isEmpty()

        fun lookup(forgeCardId: ForgeCardId): InstanceId? = entries[forgeCardId]

        fun allocate(
            forgeCardId: ForgeCardId,
            id: InstanceId,
        ) {
            entries[forgeCardId] = id
        }

        fun retain(activeCardIds: Set<ForgeCardId>): List<InstanceId> =
            entries
                .filterKeys { it !in activeCardIds }
                .values
                .toList()
                .also { entries.keys.retainAll(activeCardIds) }

        fun drain(): List<InstanceId> = entries.values.toList().also { entries.clear() }

        fun freeze(): State = State(entries.toMap())
    }
}
