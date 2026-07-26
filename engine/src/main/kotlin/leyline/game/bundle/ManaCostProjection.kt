package leyline.game.bundle

import forge.card.mana.ManaCost
import leyline.bridge.types.ManaColorMapping
import leyline.game.bundle.CastingTimeOptionsBuilder.ManaRequirementSpec
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

object ManaCostProjection {
    fun hybridOrTwoGenericColors(cost: ManaCost): List<ManaColor> =
        cost.mapNotNull { shard -> ManaColorMapping.fromOrTwoGenericShard(shard) }

    fun requirements(cost: ManaCost): List<ManaRequirementSpec> =
        buildList {
            for (shard in cost) {
                val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
                val color = hybridColor ?: ManaColorMapping.fromShard(shard) ?: continue
                add(
                    ManaRequirementSpec(
                        colors =
                            if (hybridColor != null) {
                                listOf(ManaColor.TwoGeneric, color)
                            } else {
                                listOf(color)
                            },
                    ),
                )
            }
            if (cost.genericCost > 0) {
                add(ManaRequirementSpec(colors = listOf(ManaColor.Generic), count = cost.genericCost))
            }
        }
}
