package leyline.game.annotations

import leyline.bridge.types.GrpId
import leyline.game.state.CastingTimeOptionKind
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

/** Free-cast choices projected on exile-resident Cascade and Discover results. */
object CastingPermissionContributor : AnnotationContributor {
    override val rank: Int = 18

    override fun contribute(ctx: AnnotationContext): Contribution =
        Contribution(
            persistent =
                mapOf(
                    CastingTimeOptionKind to
                        ctx.promptFacts.castingPermissions.map { permission ->
                            AnnotationBuilder.castingTimeOption(
                                stackInstanceId = ctx.frameIds.cardIid(permission.cardForgeId),
                                type = CastingTimeOptionType.CastThroughAbility,
                                alternateCostGrpId = GrpId(149),
                                castAbilityGrpId = GrpId(permission.castAbilityGrpId),
                            )
                        },
                ),
        )
}
