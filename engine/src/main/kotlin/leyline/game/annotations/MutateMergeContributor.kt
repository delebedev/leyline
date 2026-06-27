package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.KeywordAbilityIds
import leyline.game.state.MutateLayeredEffectKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Mutate/Merge annotations: a LayeredEffectCreated transient per newly merged
 * component plus a persistent MutateLayeredEffect ([MutateLayeredEffectKind])
 * carrying the merged pile's combined ability ids. Released piles emit a
 * LayeredEffectDestroyed transient.
 */
object MutateMergeContributor : AnnotationContributor {
    override val rank: Int = 40

    override fun contribute(ctx: AnnotationContext): Contribution {
        val snap = ctx.snap
        val bridge = ctx.bridge
        val frameIds = ctx.frameIds
        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()
        val currentKeys = mutableSetOf<Pair<Int, Int>>()

        for (bound in snap.boundCards.values) {
            val targetIid = bound.snapshot.mergedToInstanceId ?: continue
            val componentIid = frameIds.cardIid(bound.forgeCardId).value
            val key = componentIid to targetIid
            currentKeys.add(key)

            val allocation = bridge.getOrAllocMutateMergeEffectId(componentIid, targetIid)
            if (allocation.created) {
                transient.add(
                    AnnotationBuilder.layeredEffectCreated(
                        effectId = EffectId(allocation.effectId),
                        affectorId = InstanceId(componentIid),
                    ),
                )
            }

            val abilityGrpIds =
                bound.data
                    ?.abilityIds
                    ?.map { it.first }
                    .orEmpty()
            persistent.add(
                AnnotationBuilder.mutateLayeredEffect(
                    componentId = InstanceId(componentIid),
                    targetId = InstanceId(targetIid),
                    effectId = EffectId(allocation.effectId),
                    abilityGrpIds = abilityGrpIds,
                    isTop = bound.snapshot.isTopMergedComponent,
                    abilityGrpId = GrpId(KeywordAbilityIds.MUTATE),
                ),
            )
        }

        for (effectId in bridge.releaseMutateMergeEffects(currentKeys)) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        return Contribution(
            transient = transient,
            persistent = mapOf(MutateLayeredEffectKind to persistent),
        )
    }
}
