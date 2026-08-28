package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.MechanicSourceFacts

/** Pure resolution of cut-scoped mechanic source attribution. */
internal object MechanicSourceProjection {
    fun sourceZoneId(
        event: GameEvent.SpellCast,
        facts: MechanicSourceFacts,
    ): Int = event.activationZoneId.takeIf { it != 0 } ?: facts.sourceZone(event.cardId)

    fun triggeringObjectZoneId(
        event: GameEvent.SpellCast,
        sourceZoneId: Int,
        facts: MechanicSourceFacts,
    ): Int = event.triggeringObjectCardId?.let(facts::sourceZone) ?: sourceZoneId

    fun manaAbilityGrpId(
        snapshot: GsmSnapshot,
        sourceForgeCardId: ForgeCardId,
    ): GrpId = GrpId(snapshot.boundCards[sourceForgeCardId]?.snapshot?.basicLandManaAbilityGrpId ?: 0)

    fun paymentAbilityGrpId(
        payment: GameEvent.ManaPayment,
        fallback: (ForgeCardId) -> GrpId,
    ): GrpId = payment.abilityGrpId.takeIf { it != 0 }?.let(::GrpId) ?: fallback(payment.sourceCardId)

    fun tokenCreatedAffectorId(
        event: GameEvent.TokenCreated,
        facts: MechanicSourceFacts,
        resolvingStackIidsByCard: Map<ForgeCardId, InstanceId>,
        stackAbilityIid: (Int, ForgeCardId) -> InstanceId,
        cardIid: (ForgeCardId) -> InstanceId,
    ): InstanceId? {
        val explicitSource = event.sourceCardId
        if (explicitSource != null) {
            return if (event.sourceAbilityForgeId != 0) {
                stackAbilityIid(event.sourceAbilityForgeId, explicitSource)
            } else {
                resolvingStackIidsByCard[explicitSource] ?: cardIid(explicitSource)
            }
        }

        return facts.tokenCreatorByTokenForgeCardId[event.cardId]
            ?.let { stackAbilityIid(it.sourceAbilityForgeId, it.sourceForgeCardId) }
            ?: resolvingStackIidsByCard.values.singleOrNull()
    }
}
