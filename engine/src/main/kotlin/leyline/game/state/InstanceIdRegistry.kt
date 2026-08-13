package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

/** Projection-owned client identity values and their private compile-time editor. */
object InstanceIdRegistry {
    data class State(
        val nextInstanceId: Int,
        val forgeIdToInstanceId: Map<ForgeCardId, InstanceId>,
        val instanceIdToForgeId: Map<InstanceId, ForgeCardId>,
    )

    data class IdReallocation(
        val old: InstanceId,
        val new: InstanceId,
    )

    class Planner internal constructor(
        initial: State,
    ) {
        private var nextInstanceId = initial.nextInstanceId
        private val forward = initial.forgeIdToInstanceId.toMutableMap()
        private val reverse = initial.instanceIdToForgeId.toMutableMap()

        fun peek(forgeCardId: ForgeCardId): InstanceId? = forward[forgeCardId]

        fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
            forward[forgeCardId] ?: InstanceId(nextInstanceId++).also { id ->
                forward[forgeCardId] = id
                reverse[id] = forgeCardId
            }

        fun reserve(): InstanceId = InstanceId(nextInstanceId++)

        fun realloc(forgeCardId: ForgeCardId): IdReallocation {
            val old = forward[forgeCardId]
            if (old == null) return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
            val new = reserve()
            forward[forgeCardId] = new
            reverse[new] = forgeCardId
            return IdReallocation(old, new)
        }

        fun getForgeCardId(instanceId: InstanceId): ForgeCardId? = reverse[instanceId]

        fun replace(state: State) {
            nextInstanceId = state.nextInstanceId
            forward.clear()
            forward.putAll(state.forgeIdToInstanceId)
            reverse.clear()
            reverse.putAll(state.instanceIdToForgeId)
        }

        fun freeze(): State = State(nextInstanceId, forward.toMap(), reverse.toMap())
    }

    fun initialState(startId: Int = 100): State = State(startId, emptyMap(), emptyMap())
}
