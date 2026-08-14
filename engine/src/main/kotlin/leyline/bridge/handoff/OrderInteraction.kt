package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/** Engine-thread intent for a synthetic hand-to-library move shown with an Order prompt. */
data class OrderMoveIntent(
    val seatId: SeatId,
    val forgeCardIds: List<ForgeCardId>,
    val putOnTop: Boolean,
)

/** Move fact owned by one coordinator window. */
data class OrderMoveValue(
    val seatId: SeatId,
    val forgeCardIds: List<ForgeCardId>,
    val putOnTop: Boolean,
)

data class OrderCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
)

/** Immutable materialization input for one ordered-card window. */
data class OrderWindowValue(
    val kind: OrderRouteKind,
    val candidates: List<OrderCandidateValue>,
    val sourceForgeCardId: ForgeCardId?,
    val defaultOptionIndex: Int,
    val move: OrderMoveValue?,
)

data class PublishedOrderInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: OrderRouteKind,
)
