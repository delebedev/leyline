package leyline.game.state

/**
 * Per-game wire identity for stack abilities whose source card can mutate
 * before resolution completes.
 */
data class AbilityWireIdentity(
    val abilityIid: Int,
    val sourceIidAtCreate: Int,
    val sourceZoneAtCreate: Int,
    val abilityGrpId: Int,
)

class AbilityLineageRegistry {
    private val byAbilityIid = mutableMapOf<Int, AbilityWireIdentity>()

    fun record(identity: AbilityWireIdentity) {
        byAbilityIid[identity.abilityIid] = identity
    }

    fun find(abilityIid: Int): AbilityWireIdentity? = byAbilityIid[abilityIid]

    fun consume(abilityIid: Int): AbilityWireIdentity? = byAbilityIid.remove(abilityIid)
}
