package leyline.bridge.handoff

import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.Collections

/** Immutable mana requirement value used by deferred-cost prompt plans. */
@ConsistentCopyVisibility
data class ManaRequirementSpec private constructor(
    val colors: List<ManaColor>,
    val count: Int,
) {
    companion object {
        fun frozen(
            colors: List<ManaColor>,
            count: Int = 1,
        ): ManaRequirementSpec = ManaRequirementSpec(Collections.unmodifiableList(colors.toList()), count)
    }
}
