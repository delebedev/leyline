package leyline.game.mapping

import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.event.GameEvent
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Event fallback for five-plus Opus triggers that collapse between snapshots. */
internal object OpusAbilityWordFeedBuilder {
    fun build(
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .filter { it.isTrigger && it.opusActive }
            .map { cast ->
                val abilityIid =
                    if (cast.abilityForgeId != 0) {
                        frameIds.triggerStackAbilityIid(cast.abilityForgeId)
                    } else {
                        frameIds.stackAbilityIid(cast.cardId)
                    }
                AnnotationBuilder.abilityWordActive(
                    instanceId = abilityIid,
                    abilityWordName = "Opus",
                    affectorId = InstanceId(cast.seatId.value),
                    affectedIds = listOf(abilityIid),
                )
            }
}
