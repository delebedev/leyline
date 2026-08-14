package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

data class GroupingCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
)

data class GroupingSourceValue(
    val hostCardId: ForgeCardId?,
    val forgeAbilityId: Int,
    val abilityOnStack: Boolean,
)

/** Immutable materialization input for one Scry or Surveil grouping window. */
data class GroupingWindowValue(
    val context: GroupingContext,
    val candidates: List<GroupingCandidateValue>,
    val source: GroupingSourceValue?,
    val defaultOptionIndex: Int,
    val singleCardChoice: Boolean,
)

data class PublishedGroupingInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val context: GroupingContext,
)

data class GroupingArrangementValue(
    val seatId: SeatId,
    val context: GroupingContext,
    val topIds: List<Int>,
    val awayIds: List<Int>,
)
