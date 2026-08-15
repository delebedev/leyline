package leyline.bridge.coord

import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.CompatibilityCostSelectionRuntime
import leyline.bridge.handoff.GroupingInteractionRuntime
import leyline.bridge.handoff.ManaSourcePaymentRuntime
import leyline.bridge.handoff.ModalChoiceInteractionRuntime
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.PromptRuntimeBindings
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.handoff.RevealChoiceInteractionRuntime
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.types.SeatId
import leyline.game.CardSelectMaterializationDiagnostic
import leyline.game.GroupingMaterializationDiagnostic
import leyline.game.ManaSourcePaymentMaterializationDiagnostic
import leyline.game.ModalChoiceMaterializationDiagnostic
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.OrderMaterializationDiagnostic
import leyline.game.PendingCardSelectCut
import leyline.game.PendingGroupingCut
import leyline.game.PendingManaSourcePaymentCut
import leyline.game.PendingModalChoiceCut
import leyline.game.PendingOneShotPayCostsCut
import leyline.game.PendingOrderCut
import leyline.game.PendingRevealChoiceCut
import leyline.game.PendingSearchCut
import leyline.game.PendingStaticChoiceCut
import leyline.game.RevealChoiceMaterializationDiagnostic
import leyline.game.SearchMaterializationDiagnostic
import leyline.game.StaticChoiceMaterializationDiagnostic

/** Owns the complete prompt-runtime inventory for one match. */
internal class MatchPromptRuntimeSet(
    private val owner: MatchCutCoordinator,
) {
    val targeting = MatchTargetingInteractionRuntime(owner)
    val compatibilityCostSelection = MatchCompatibilityCostSelectionRuntime(owner)
    val search = MatchSearchInteractionRuntime(owner)
    val order = MatchOrderInteractionRuntime(owner)
    val grouping = MatchGroupingInteractionRuntime(owner)
    val cardSelect = MatchCardSelectInteractionRuntime(owner)
    val staticChoices = MatchStaticChoiceInteractionRuntime(owner)
    val revealChoices = MatchRevealChoiceInteractionRuntime(owner)
    val modalChoices = MatchModalChoiceRuntime(owner)
    val manaSourcePayments = MatchManaSourcePaymentRuntime(owner)
    val oneShotPayCosts = MatchOneShotPayCostsRuntime(owner)

    fun bindings(seatId: SeatId): PromptRuntimeBindings {
        check(seatId == owner.humanSeat) { "Prompt runtimes are only registered for the human seat" }
        return PromptRuntimeBindings(
            targeting = targeting,
            compatibilityCostSelection = compatibilityCostSelection,
            search = search,
            order = order,
            grouping = grouping,
            cardSelect = cardSelect,
            staticChoice = staticChoices,
            revealChoice = revealChoices,
            modalChoice = modalChoices,
            manaSourcePayment = manaSourcePayments,
            oneShotPayCosts = oneShotPayCosts,
        )
    }

    fun hasPendingInteraction(): Boolean =
        targeting.current() != null ||
            search.current() != null ||
            modalChoices.current() != null ||
            order.current() != null ||
            grouping.current() != null ||
            cardSelect.current() != null ||
            staticChoices.current() != null ||
            revealChoices.current() != null ||
            manaSourcePayments.current() != null ||
            oneShotPayCosts.current() != null

    fun hasRevealProjectionPrompt(): Boolean = revealChoices.current() != null || cardSelect.current() != null

    fun currentOneShotPayCosts(): PublishedOneShotPayCostsInteraction? = oneShotPayCosts.current()

    fun terminate(cause: Throwable) {
        targeting.terminate(cause)
        search.terminate(cause)
        order.terminate(cause)
        grouping.terminate(cause)
        cardSelect.terminate(cause)
        staticChoices.terminate(cause)
        revealChoices.terminate(cause)
        modalChoices.terminate(cause)
        manaSourcePayments.terminate(cause)
        oneShotPayCosts.terminate(cause)
    }

    fun reset() {
        targeting.reset()
        search.reset()
        order.reset()
        grouping.reset()
        cardSelect.reset()
        staticChoices.reset()
        revealChoices.reset()
        modalChoices.reset()
        manaSourcePayments.reset()
        oneShotPayCosts.reset()
    }

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            oneShotPayCosts.pendingCutLocked()?.let { owner.failOneShotPayCosts(cause, it) }
            manaSourcePayments.pendingCutLocked()?.let { owner.failManaSourcePayment(cause, it) }
            search.pendingCutLocked()?.let { owner.failSearch(cause, it) }
            order.pendingCutLocked()?.let { owner.failOrder(cause, it) }
            grouping.pendingCutLocked()?.let { owner.failGrouping(cause, it) }
            cardSelect.pendingCutLocked()?.let { owner.failCardSelect(cause, it) }
            staticChoices.pendingCutLocked()?.let { owner.failStaticChoice(cause, it) }
            modalChoices.pendingCutLocked()?.let { owner.failModalChoice(cause, it) }
            revealChoices.failDelivery(cause)
        }
}

/** Human-seat prompt runtimes registered with the engine-side prompt bridge. */
internal fun MatchCutCoordinator.targetingRuntime(seatId: SeatId): TargetingInteractionRuntime {
    check(seatId == humanSeat) { "Targeting interaction runtime is only registered for the human seat" }
    return prompts.targeting
}

internal fun MatchCutCoordinator.compatibilityCostSelectionRuntime(seatId: SeatId): CompatibilityCostSelectionRuntime {
    check(seatId == humanSeat) { "Compatibility cost selection runtime is only registered for the human seat" }
    return prompts.compatibilityCostSelection
}

internal fun MatchCutCoordinator.searchRuntime(seatId: SeatId): SearchInteractionRuntime {
    check(seatId == humanSeat) { "Search interaction runtime is only registered for the human seat" }
    return prompts.search
}

internal fun MatchCutCoordinator.orderRuntime(seatId: SeatId): OrderInteractionRuntime {
    check(seatId == humanSeat) { "Order interaction runtime is only registered for the human seat" }
    return prompts.order
}

internal fun MatchCutCoordinator.groupingRuntime(seatId: SeatId): GroupingInteractionRuntime {
    check(seatId == humanSeat) { "Grouping interaction runtime is only registered for the human seat" }
    return prompts.grouping
}

internal fun MatchCutCoordinator.cardSelectRuntime(seatId: SeatId): CardSelectInteractionRuntime {
    check(seatId == humanSeat) { "CardSelect interaction runtime is only registered for the human seat" }
    return prompts.cardSelect
}

internal fun MatchCutCoordinator.staticChoiceRuntime(seatId: SeatId): StaticChoiceInteractionRuntime {
    check(seatId == humanSeat) { "StaticChoice interaction runtime is only registered for the human seat" }
    return prompts.staticChoices
}

internal fun MatchCutCoordinator.revealChoiceRuntime(seatId: SeatId): RevealChoiceInteractionRuntime {
    check(seatId == humanSeat) { "RevealChoice interaction runtime is only registered for the human seat" }
    return prompts.revealChoices
}

internal fun MatchCutCoordinator.modalChoiceRuntime(seatId: SeatId): ModalChoiceInteractionRuntime {
    check(seatId == humanSeat) { "ModalChoice runtime is only registered for the human seat" }
    return prompts.modalChoices
}

internal fun MatchCutCoordinator.manaSourcePaymentRuntime(seatId: SeatId): ManaSourcePaymentRuntime {
    check(seatId == humanSeat) { "Mana-source payment runtime is only registered for the human seat" }
    return prompts.manaSourcePayments
}

internal fun MatchCutCoordinator.oneShotPayCostsRuntime(seatId: SeatId): OneShotPayCostsRuntime {
    check(seatId == humanSeat) { "One-shot PayCosts runtime is only registered for the human seat" }
    return prompts.oneShotPayCosts
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

internal fun MatchCutCoordinator.failRevealChoice(
    cause: Throwable,
    pending: PendingRevealChoiceCut? = null,
    diagnostic: RevealChoiceMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingRevealChoice = pending, revealChoiceDiagnostic = diagnostic))

internal fun MatchCutCoordinator.failModalChoice(
    cause: Throwable,
    pending: PendingModalChoiceCut? = null,
    diagnostic: ModalChoiceMaterializationDiagnostic? = null,
): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pendingModalChoice = pending, modalChoiceDiagnostic = diagnostic))
