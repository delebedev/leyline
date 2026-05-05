package leyline.game.annotations

import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId

/** Universal content-side grpIds that annotations reference directly. */
object AnnotationConstants {
    /** Ability grpId used by the `TemporaryPermanent` annotation to mark EOT-sacrifice tokens. */
    val EOT_SACRIFICE_GRP_ID: GrpId = GrpId(192424)

    /** Adventure-cast qualification grpId — fixed ability ID referenced by the `Qualification` annotation. */
    val ADVENTURE_QUALIFICATION_GRP_ID: GrpId = GrpId(196)

    /** Shared Battlefield zone ID, used as `affectorId` on zone-scoped persistent
     *  annotations whose affector is the zone rather than any specific permanent. */
    val BATTLEFIELD_ZONE_AFFECTOR: InstanceId = InstanceId(28)

    /** `DesignationType` enum value for the `Prepared` card-state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`. */
    const val DESIGNATION_TYPE_PREPARED: Int = 24

    /** `DesignationType` enum value for the `Plotted` card-state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`
     *  for cards exiled with the plot keyword. */
    const val DESIGNATION_TYPE_PLOTTED: Int = 18

    /** `DesignationType` enum value for the `LeftUnlocked` Room-door state.
     *  Marks the left half of a split-room enchantment as unlocked. Gained when
     *  a `CastLeftRoom` action resolves; persists while the door stays open. */
    const val DESIGNATION_TYPE_LEFT_UNLOCKED: Int = 19

    /** `DesignationType` enum value for the `RightUnlocked` Room-door state.
     *  Marks the right half of a split-room enchantment as unlocked. Gained when
     *  a `CastRightRoom` action resolves; persists while the door stays open. */
    const val DESIGNATION_TYPE_RIGHT_UNLOCKED: Int = 20
}
