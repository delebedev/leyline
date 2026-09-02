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
    ) : ProjectionIdentityWorkspace {
        private var nextInstanceId = initial.nextInstanceId
        private val forward = initial.forgeIdToInstanceId.toMutableMap()
        private val reverse = initial.instanceIdToForgeId.toMutableMap()

        fun peek(forgeCardId: ForgeCardId): InstanceId? = forward[forgeCardId]

        override fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
            forward[forgeCardId] ?: InstanceId(nextInstanceId++).also { id ->
                forward[forgeCardId] = id
                reverse[id] = forgeCardId
            }

        fun reserve(): InstanceId = InstanceId(nextInstanceId++)

        fun bind(
            forgeCardId: ForgeCardId,
            instanceId: InstanceId,
        ) {
            nextInstanceId = maxOf(nextInstanceId, instanceId.value + 1)
            val displaced = reverse[instanceId]
            if (displaced != null && displaced != forgeCardId) {
                val fresh = reserve()
                forward[displaced] = fresh
                reverse[fresh] = displaced
            }
            forward[forgeCardId]?.let { old ->
                if (old != instanceId) reverse.remove(old)
            }
            forward[forgeCardId] = instanceId
            reverse[instanceId] = forgeCardId
        }

        fun alias(
            existingForgeId: ForgeCardId,
            aliasForgeId: ForgeCardId,
        ): InstanceId {
            val instanceId = getOrAlloc(existingForgeId)
            val occupied = forward[aliasForgeId]
            check(occupied == null || occupied == instanceId) { "Forge identity alias already has a different instance id" }
            forward[aliasForgeId] = instanceId
            reverse[instanceId] = aliasForgeId
            return instanceId
        }

        fun realloc(forgeCardId: ForgeCardId): IdReallocation {
            val old = forward[forgeCardId]
            if (old == null) return getOrAlloc(forgeCardId).let { IdReallocation(it, it) }
            val new = reserve()
            forward[forgeCardId] = new
            reverse[new] = forgeCardId
            return IdReallocation(old, new)
        }

        fun reallocTo(
            forgeCardId: ForgeCardId,
            new: InstanceId,
        ): IdReallocation {
            val old = getOrAlloc(forgeCardId)
            check(reverse[new] == null || reverse[new] == forgeCardId) { "Reserved instance id belongs to another card" }
            nextInstanceId = maxOf(nextInstanceId, new.value + 1)
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

/** Private mutable identity operation owned by one tentative projection edit. */
fun interface ProjectionIdentityWorkspace {
    fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId
}
