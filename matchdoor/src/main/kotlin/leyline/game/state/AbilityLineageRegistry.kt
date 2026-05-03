package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-ability identity record stored when an Ability gameObject lands on the
 * stack and consulted at lifecycle emission time so the emission keeps
 * referring to the cast-time identity even after the host card mutates during
 * resolution.
 *
 * The branch's [leyline.game.mapping.FrameIdResolver.triggerStackAbilityIid]
 * mints stack-ability iids keyed by SpellAbility.id; this registry adds the
 * pre-mutation source iid + grpId so AbilityInstanceDeleted.affector closes
 * against the original source iid even when the host transformed mid-resolve.
 *
 * Lifecycle:
 *  - Created: GameEventCollector on SpellCast(isAbility=true, abilityForgeId>0)
 *  - Consulted: StateMapper.emitTriggerLifecycleAnnotations for RS/RC/AID
 *  - Cleared: consume() called on matching SpellResolved or AID emission
 *
 * Threading: Forge events fire on the engine thread; StateMapper reads
 * happen on the same thread or inside the session-thread closeBundleFrame
 * critical section. ConcurrentHashMap covers both.
 */
data class AbilityWireIdentity(
    val abilityForgeId: Int,
    val abilityIid: InstanceId,
    val sourceForgeId: ForgeCardId,
    val sourceIidAtCreate: InstanceId,
    val sourceZoneAtCreate: Int,
    val abilityGrpId: Int,
)

class AbilityLineageRegistry {
    private val byAbilityForgeId = ConcurrentHashMap<Int, AbilityWireIdentity>()

    /** Store the identity for an ability that just landed on the stack. */
    fun record(identity: AbilityWireIdentity) {
        byAbilityForgeId[identity.abilityForgeId] = identity
    }

    /** Look up without removing. Returns null if no identity is recorded. */
    fun lookup(abilityForgeId: Int): AbilityWireIdentity? = byAbilityForgeId[abilityForgeId]

    /** Look up and remove in one operation — call on AID emission. */
    fun consume(abilityForgeId: Int): AbilityWireIdentity? = byAbilityForgeId.remove(abilityForgeId)

    /** Drop all entries — call on match teardown. */
    fun clear() {
        byAbilityForgeId.clear()
    }
}
