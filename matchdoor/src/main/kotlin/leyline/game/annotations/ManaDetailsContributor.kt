package leyline.game.annotations

import leyline.bridge.types.InstanceId
import leyline.game.state.ManaDetailsKind
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

/**
 * ManaDetails annotations: one persistent [ManaDetailsKind] pAnn per pooled mana
 * that does not empty between steps (e.g. mana that persists across phases).
 * Pure and persistent-only — reads the snapshot mana pools, no allocation.
 */
object ManaDetailsContributor : AnnotationContributor {
    override val rank: Int = 70

    override fun contribute(ctx: AnnotationContext): Contribution {
        val details =
            ctx.snap.seats.flatMap { seat ->
                seat.manaPool.mapNotNull { mana ->
                    if (ManaSpecType.DoesNotEmpty !in mana.specs) return@mapNotNull null
                    AnnotationBuilder.manaDetails(
                        sourceInstanceId = InstanceId(mana.srcInstanceId),
                        manaId = mana.manaId,
                    )
                }
            }
        return Contribution(persistent = mapOf(ManaDetailsKind to details))
    }
}
