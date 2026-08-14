package leyline.bridge.coord

import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.ManaSourcePaymentRuntime
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.types.SeatId

/** Human-seat prompt runtimes registered with the engine-side prompt bridge. */
internal fun MatchCutCoordinator.targetingRuntime(seatId: SeatId): TargetingInteractionRuntime {
    check(seatId == humanSeat) { "Targeting interaction runtime is only registered for the human seat" }
    return targeting
}

internal fun MatchCutCoordinator.searchRuntime(seatId: SeatId): SearchInteractionRuntime {
    check(seatId == humanSeat) { "Search interaction runtime is only registered for the human seat" }
    return search
}

internal fun MatchCutCoordinator.orderRuntime(seatId: SeatId): OrderInteractionRuntime {
    check(seatId == humanSeat) { "Order interaction runtime is only registered for the human seat" }
    return order
}

internal fun MatchCutCoordinator.cardSelectRuntime(seatId: SeatId): CardSelectInteractionRuntime {
    check(seatId == humanSeat) { "CardSelect interaction runtime is only registered for the human seat" }
    return cardSelect
}

internal fun MatchCutCoordinator.manaSourcePaymentRuntime(seatId: SeatId): ManaSourcePaymentRuntime {
    check(seatId == humanSeat) { "Mana-source payment runtime is only registered for the human seat" }
    return manaSourcePayments
}

internal fun MatchCutCoordinator.oneShotPayCostsRuntime(seatId: SeatId): OneShotPayCostsRuntime {
    check(seatId == humanSeat) { "One-shot PayCosts runtime is only registered for the human seat" }
    return oneShotPayCosts
}
