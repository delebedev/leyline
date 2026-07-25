package leyline.game.mapping

import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.event.GameEvent
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Event fallback for Void trigger abilities that collapse between snapshots. */
internal object VoidAbilityWordFeedBuilder {
    fun build(
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .filter { it.voidTrigger }
            .map { cast ->
                val abilityIid =
                    if (cast.abilityForgeId != 0) {
                        frameIds.triggerStackAbilityIid(cast.abilityForgeId)
                    } else {
                        frameIds.stackAbilityIid(cast.cardId)
                    }
                AnnotationBuilder.abilityWordActive(
                    instanceId = abilityIid,
                    abilityWordName = "Void",
                    affectorId = InstanceId(cast.seatId.value),
                    affectedIds = listOf(abilityIid),
                )
            }
}
