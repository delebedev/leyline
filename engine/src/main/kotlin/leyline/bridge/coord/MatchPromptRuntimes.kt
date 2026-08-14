package leyline.bridge.coord

import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.GroupingInteractionRuntime
import leyline.bridge.handoff.ManaSourcePaymentRuntime
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.types.SeatId
import leyline.game.CardSelectMaterializationDiagnostic
import leyline.game.GroupingMaterializationDiagnostic
import leyline.game.ManaSourcePaymentMaterializationDiagnostic
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.OrderMaterializationDiagnostic
import leyline.game.PendingCardSelectCut
import leyline.game.PendingGroupingCut
import leyline.game.PendingManaSourcePaymentCut
import leyline.game.PendingOneShotPayCostsCut
import leyline.game.PendingOrderCut
import leyline.game.PendingSearchCut
import leyline.game.PendingStaticChoiceCut
import leyline.game.SearchMaterializationDiagnostic
import leyline.game.StaticChoiceMaterializationDiagnostic

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

internal fun MatchCutCoordinator.groupingRuntime(seatId: SeatId): GroupingInteractionRuntime {
    check(seatId == humanSeat) { "Grouping interaction runtime is only registered for the human seat" }
    return grouping
}

internal fun MatchCutCoordinator.cardSelectRuntime(seatId: SeatId): CardSelectInteractionRuntime {
    check(seatId == humanSeat) { "CardSelect interaction runtime is only registered for the human seat" }
    return cardSelect
}

internal fun MatchCutCoordinator.staticChoiceRuntime(seatId: SeatId): StaticChoiceInteractionRuntime {
    check(seatId == humanSeat) { "StaticChoice interaction runtime is only registered for the human seat" }
    return staticChoices
}

internal fun MatchCutCoordinator.manaSourcePaymentRuntime(seatId: SeatId): ManaSourcePaymentRuntime {
    check(seatId == humanSeat) { "Mana-source payment runtime is only registered for the human seat" }
    return manaSourcePayments
}

internal fun MatchCutCoordinator.oneShotPayCostsRuntime(seatId: SeatId): OneShotPayCostsRuntime {
    check(seatId == humanSeat) { "One-shot PayCosts runtime is only registered for the human seat" }
    return oneShotPayCosts
}

internal fun MatchCutCoordinator.failSearch(
    cause: Throwable,
    pending: PendingSearchCut? = null,
    diagnostic: SearchMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingSearch = pending, searchDiagnostic = diagnostic))

internal fun MatchCutCoordinator.failManaSourcePayment(
    cause: Throwable,
    pending: PendingManaSourcePaymentCut? = null,
    diagnostic: ManaSourcePaymentMaterializationDiagnostic? = null,
): Nothing =
    failTerminal(
        cause,
        MatchCutTerminalRuntime.Context(
            pendingManaSourcePayment = pending,
            manaSourcePaymentDiagnostic = diagnostic,
        ),
    )

internal fun MatchCutCoordinator.failOneShotPayCosts(
    cause: Throwable,
    pending: PendingOneShotPayCostsCut? = null,
    diagnostic: OneShotPayCostsMaterializationDiagnostic? = null,
): Nothing =
    failTerminal(
        cause,
        MatchCutTerminalRuntime.Context(
            pendingOneShotPayCosts = pending,
            oneShotPayCostsDiagnostic = diagnostic,
        ),
    )

internal fun MatchCutCoordinator.failOrder(
    cause: Throwable,
    pending: PendingOrderCut? = null,
    diagnostic: OrderMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingOrder = pending, orderDiagnostic = diagnostic))

internal fun MatchCutCoordinator.failGrouping(
    cause: Throwable,
    pending: PendingGroupingCut? = null,
    diagnostic: GroupingMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingGrouping = pending, groupingDiagnostic = diagnostic))

internal fun MatchCutCoordinator.failCardSelect(
    cause: Throwable,
    pending: PendingCardSelectCut? = null,
    diagnostic: CardSelectMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingCardSelect = pending, cardSelectDiagnostic = diagnostic))

internal fun MatchCutCoordinator.failStaticChoice(
    cause: Throwable,
    pending: PendingStaticChoiceCut? = null,
    diagnostic: StaticChoiceMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingStaticChoice = pending, staticChoiceDiagnostic = diagnostic))
