package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

/** Raised when another identity writer advanced the registry before commit. */
class StaleInstanceIdTransitionException :
    IllegalStateException(
        "Instance-id projection plan is stale; retry from the immutable frame input",
    )

/**
 * Bidirectional mapping between Forge card IDs and client instanceIds.
 *
 * The client protocol uses opaque `instanceId` integers to identify game objects.
 * Forge uses its own `Card.id` sequence. This registry translates between the two,
 * allocating fresh instanceIds on first sight and supporting reallocation when
 * cards change zones.
 *
 * Projection compilation uses [withTentativeState]. Its planner starts from a
 * committed [State], answers every lookup and allocation locally, and returns a
 * [Transition] for the shell to validate and commit. No registry lock is held
 * while projection reads Forge or builds a frame.
 */
class InstanceIdRegistry(
    startId: Int = 100,
) {
    /** Complete identity state owned by one committed or tentative projection. */
    data class State(
        val nextInstanceId: Int,
        val forgeIdToInstanceId: Map<ForgeCardId, InstanceId>,
        val instanceIdToForgeId: Map<InstanceId, ForgeCardId>,
    )

    /** Committed state plus the registry generation from which it was read. */
    data class VersionedState(
        val version: Long,
        val state: State,
    )

    /** Tentative state that may be installed if its committed generation is unchanged. */
    data class Transition(
        val baselineVersion: Long,
        val nextState: State,
    )

    /** Result of reallocating an instanceId for a zone transfer. */
    data class IdReallocation(
        val old: InstanceId,
        val new: InstanceId,
    )

    private val lock = Any()
    private var version = 0L
    private var state =
        State(
            nextInstanceId = startId,
            forgeIdToInstanceId = emptyMap(),
            instanceIdToForgeId = emptyMap(),
        )
    private val tentative = ThreadLocal<Planner?>()

    /**
     * Run projection work against a private identity planner.
     *
     * Nested scopes reuse the outer planner so [StateMapper.buildDiff] can call
     * [StateMapper.buildFromSnapshot] without opening a second transaction.
     * The planner is discarded when [block] returns or throws.
     */
    fun <T> withTentativeState(block: () -> T): T {
        if (tentative.get() != null) return block()
        val committed = committedState()
        tentative.set(Planner(committed.version, committed.state))
        return try {
            block()
        } finally {
            tentative.remove()
        }
    }

    /** Transition produced by the current [withTentativeState] scope. */
    fun tentativeTransition(): Transition =
        checkNotNull(tentative.get()) {
            "No tentative instance-id state is active"
        }.transition()

    /** Read the committed state and generation without exposing mutable maps. */
    fun committedState(): VersionedState =
        synchronized(lock) {
            VersionedState(version, state.copyMaps())
        }

    /**
     * Install a tentative state exactly once if no writer changed the registry
     * since the planner's baseline. Returns false for a stale plan; the caller
     * must retry projection from the same immutable frame input.
     */
    fun commit(transition: Transition): Boolean =
        synchronized(lock) {
            if (version != transition.baselineVersion) return false
            validate(transition.nextState)
            state = transition.nextState.copyMaps()
            version++
            true
        }

    /** Return the current mapped instanceId for [forgeCardId], or `null`. */
    fun peek(forgeCardId: ForgeCardId): InstanceId? =
        tentative.get()?.peek(forgeCardId) ?: synchronized(lock) { state.forgeIdToInstanceId[forgeCardId] }

    /**
     * Reserve the next instanceId without adding a mapping.
     * Shell callers may use this directly; projection callers are redirected to
     * the private planner by the active scope.
     */
    fun reserveNextInstanceId(): InstanceId =
        tentative.get()?.reserve() ?: synchronized(lock) {
            val id = InstanceId(state.nextInstanceId)
            state = state.copy(nextInstanceId = state.nextInstanceId + 1).copyMaps()
            version++
            id
        }

    /** Allocate or return existing client instanceId for a Forge card ID. */
    fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
        tentative.get()?.getOrAlloc(forgeCardId) ?: synchronized(lock) {
            state.forgeIdToInstanceId[forgeCardId]
                ?: InstanceId(state.nextInstanceId).also { id ->
                    val forward = state.forgeIdToInstanceId.toMutableMap()
                    val reverse = state.instanceIdToForgeId.toMutableMap()
                    forward[forgeCardId] = id
                    reverse[id] = forgeCardId
                    state =
                        State(
                            nextInstanceId = state.nextInstanceId + 1,
                            forgeIdToInstanceId = forward,
                            instanceIdToForgeId = reverse,
                        )
                    version++
                }
        }

    /** Allocate a fresh instanceId for a Forge card that changed zones. */
    fun realloc(forgeCardId: ForgeCardId): IdReallocation =
        tentative.get()?.realloc(forgeCardId) ?: synchronized(lock) {
            val oldId = state.forgeIdToInstanceId[forgeCardId]
            if (oldId == null) {
                val id = allocateCommitted(forgeCardId)
                return@synchronized IdReallocation(id, id)
            }
            val newId = InstanceId(state.nextInstanceId)
            val forward = state.forgeIdToInstanceId.toMutableMap()
            val reverse = state.instanceIdToForgeId.toMutableMap()
            forward[forgeCardId] = newId
            reverse[newId] = forgeCardId
            state =
                State(
                    nextInstanceId = state.nextInstanceId + 1,
                    forgeIdToInstanceId = forward,
                    instanceIdToForgeId = reverse,
                )
            version++
            IdReallocation(oldId, newId)
        }

    /**
     * Apply a previously-planned reallocation from a shell-only path.
     * Projection commits use [commit] instead, so a stale projection cannot be
     * partially installed.
     */
    fun applyRealloc(realloc: IdReallocation) {
        synchronized(lock) {
            val fid = state.instanceIdToForgeId[realloc.old] ?: state.instanceIdToForgeId[realloc.new] ?: return
            val forward = state.forgeIdToInstanceId.toMutableMap()
            val reverse = state.instanceIdToForgeId.toMutableMap()
            forward[fid] = realloc.new
            reverse[realloc.new] = fid
            state =
                State(
                    nextInstanceId = maxOf(state.nextInstanceId, realloc.new.value + 1),
                    forgeIdToInstanceId = forward,
                    instanceIdToForgeId = reverse,
                )
            version++
        }
    }

    /** Nuke-and-repave all active mappings; the monotonic counter is retained. */
    fun resetAll(): List<InstanceId> =
        synchronized(lock) {
            val oldIds = state.forgeIdToInstanceId.values.toList()
            state = State(state.nextInstanceId, emptyMap(), emptyMap())
            version++
            oldIds
        }

    /** Allocate a synthetic instanceId not mapped to any Forge card. */
    fun allocSynthetic(): InstanceId =
        tentative.get()?.reserve() ?: synchronized(lock) {
            val id = InstanceId(state.nextInstanceId)
            state = state.copy(nextInstanceId = state.nextInstanceId + 1).copyMaps()
            version++
            id
        }

    /** Reverse lookup: client instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId? =
        tentative.get()?.getForgeCardId(instanceId) ?: synchronized(lock) { state.instanceIdToForgeId[instanceId] }

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun snapshot(): Map<InstanceId, ForgeCardId> =
        synchronized(lock) {
            state.instanceIdToForgeId.toMap()
        }

    private fun allocateCommitted(forgeCardId: ForgeCardId): InstanceId {
        val id = InstanceId(state.nextInstanceId)
        val forward = state.forgeIdToInstanceId.toMutableMap()
        val reverse = state.instanceIdToForgeId.toMutableMap()
        forward[forgeCardId] = id
        reverse[id] = forgeCardId
        state = State(state.nextInstanceId + 1, forward, reverse)
        version++
        return id
    }

    private fun validate(next: State) {
        check(
            next.forgeIdToInstanceId
                .values
                .toSet()
                .size == next.forgeIdToInstanceId.size,
        ) {
            "Tentative instance-id state contains duplicate forward IDs"
        }
        for ((fid, iid) in next.forgeIdToInstanceId) {
            check(next.instanceIdToForgeId[iid] == fid) {
                "Tentative instance-id state has inconsistent reverse mapping for $fid → $iid"
            }
        }
        check(
            next.nextInstanceId >
                (next.instanceIdToForgeId.keys.maxOfOrNull { it.value } ?: Int.MIN_VALUE),
        ) {
            "Tentative instance-id counter does not advance past allocated IDs"
        }
    }

    private class Planner(
        private val baselineVersion: Long,
        initial: State,
    ) {
        private var nextInstanceId = initial.nextInstanceId
        private val forward = initial.forgeIdToInstanceId.toMutableMap()
        private val reverse = initial.instanceIdToForgeId.toMutableMap()

        fun peek(forgeCardId: ForgeCardId): InstanceId? = forward[forgeCardId]

        fun getOrAlloc(forgeCardId: ForgeCardId): InstanceId =
            forward[forgeCardId] ?: InstanceId(nextInstanceId).also { id ->
                nextInstanceId++
                forward[forgeCardId] = id
                reverse[id] = forgeCardId
            }

        fun reserve(): InstanceId = InstanceId(nextInstanceId++).also { }

        fun realloc(forgeCardId: ForgeCardId): IdReallocation {
            val oldId = forward[forgeCardId]
            if (oldId == null) {
                val id = getOrAlloc(forgeCardId)
                return IdReallocation(id, id)
            }
            val newId = InstanceId(nextInstanceId++)
            forward[forgeCardId] = newId
            reverse[newId] = forgeCardId
            return IdReallocation(oldId, newId)
        }

        fun getForgeCardId(instanceId: InstanceId): ForgeCardId? = reverse[instanceId]

        fun transition(): Transition =
            Transition(
                baselineVersion = baselineVersion,
                nextState = State(nextInstanceId, forward.toMap(), reverse.toMap()),
            )
    }

    private fun State.copyMaps(): State =
        copy(
            forgeIdToInstanceId = forgeIdToInstanceId.toMap(),
            instanceIdToForgeId = instanceIdToForgeId.toMap(),
        )
}
