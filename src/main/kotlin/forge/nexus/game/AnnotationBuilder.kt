package forge.nexus.game

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo

/** Builds Arena-format annotations for GameStateMessage. */
object AnnotationBuilder {

    fun zoneTransfer(
        instanceId: Int,
        srcZoneId: Int,
        destZoneId: Int,
        category: String,
    ): AnnotationInfo = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ZoneTransfer_af5a)
        .addAffectedIds(instanceId)
        .addDetails(stringDetail("zone_src", srcZoneId.toString()))
        .addDetails(stringDetail("zone_dest", destZoneId.toString()))
        .addDetails(stringDetail("category", category))
        .build()

    private fun stringDetail(key: String, value: String): KeyValuePairInfo =
        KeyValuePairInfo.newBuilder()
            .setKey(key)
            .addValueString(value)
            .build()
}
