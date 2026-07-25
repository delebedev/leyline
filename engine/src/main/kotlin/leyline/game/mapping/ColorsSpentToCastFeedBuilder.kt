package leyline.game.mapping

import leyline.game.annotations.AnnotationBuilder
import leyline.game.event.GameEvent
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Event fallback for cast-payment markers when a stack item collapses between snapshots. */
internal object ColorsSpentToCastFeedBuilder {
    fun build(
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .filter { it.colorsSpentToCast.isNotEmpty() && (!it.isAbility || it.isTrigger) }
            .map { cast ->
                val iid =
                    if (cast.isTrigger) {
                        if (cast.abilityForgeId != 0) {
                            frameIds.triggerStackAbilityIid(cast.abilityForgeId)
                        } else {
                            frameIds.stackAbilityIid(cast.cardId)
                        }
                    } else {
                        frameIds.cardIid(cast.cardId)
                    }
                AnnotationBuilder.abilityWordActive(
                    instanceId = iid,
                    abilityWordName = "ColorsSpentToCast",
                    colors = cast.colorsSpentToCast,
                )
            }
}
