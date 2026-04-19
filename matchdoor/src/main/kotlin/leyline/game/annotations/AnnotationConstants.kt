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
}
