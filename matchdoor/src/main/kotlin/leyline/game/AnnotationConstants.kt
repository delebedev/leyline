package leyline.game

import leyline.bridge.GrpId

/** Universal content-side grpIds that annotations reference directly. */
object AnnotationConstants {
    /** Ability grpId used by the `TemporaryPermanent` annotation to mark EOT-sacrifice tokens. */
    val EOT_SACRIFICE_GRP_ID: GrpId = GrpId(192424)

    /** Adventure-cast qualification grpId — fixed ability ID referenced by the `Qualification` annotation. */
    val ADVENTURE_QUALIFICATION_GRP_ID: GrpId = GrpId(196)
}
