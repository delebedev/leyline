package leyline.bridge.coord

import leyline.game.CardSelectMaterializationDiagnostic
import leyline.game.ManaSourcePaymentMaterializationDiagnostic
import leyline.game.MaterializationDiagnostic
import leyline.game.OneShotPayCostsMaterializationDiagnostic
import leyline.game.OrderMaterializationDiagnostic
import leyline.game.PendingCardSelectCut
import leyline.game.PendingCut
import leyline.game.PendingInteractionCut
import leyline.game.PendingManaSourcePaymentCut
import leyline.game.PendingOneShotPayCostsCut
import leyline.game.PendingOrderCut
import leyline.game.PendingSearchCut
import leyline.game.PendingStaticChoiceCut
import leyline.game.PlaybackTerminalFailure
import leyline.game.PromptTerminalFailureContext
import leyline.game.SearchMaterializationDiagnostic
import leyline.game.StaticChoiceMaterializationDiagnostic

/** Write-once terminal state and waiter teardown for one match cut coordinator. */
internal class MatchCutTerminalRuntime(
    private val owner: MatchCutCoordinator,
) {
    data class Context(
        val pending: PendingCut? = null,
        val diagnostic: MaterializationDiagnostic? = null,
        val pendingInteraction: PendingInteractionCut? = null,
        val pendingSearch: PendingSearchCut? = null,
        val searchDiagnostic: SearchMaterializationDiagnostic? = null,
        val pendingOrder: PendingOrderCut? = null,
        val orderDiagnostic: OrderMaterializationDiagnostic? = null,
        val pendingCardSelect: PendingCardSelectCut? = null,
        val cardSelectDiagnostic: CardSelectMaterializationDiagnostic? = null,
        val pendingStaticChoice: PendingStaticChoiceCut? = null,
        val staticChoiceDiagnostic: StaticChoiceMaterializationDiagnostic? = null,
        val pendingManaSourcePayment: PendingManaSourcePaymentCut? = null,
        val manaSourcePaymentDiagnostic: ManaSourcePaymentMaterializationDiagnostic? = null,
        val pendingOneShotPayCosts: PendingOneShotPayCostsCut? = null,
        val oneShotPayCostsDiagnostic: OneShotPayCostsMaterializationDiagnostic? = null,
    )

    @Volatile
    private var failure: PlaybackTerminalFailure? = null

    fun current(): PlaybackTerminalFailure? = failure

    fun reset() {
        failure = null
    }

    fun ensureOpen() {
        failure?.let { throw it }
    }

    fun terminate(
        cause: Throwable,
        context: Context = Context(),
    ): PlaybackTerminalFailure =
        synchronized(owner.feedLock) {
            failure?.let { return@synchronized it }
            PlaybackTerminalFailure(
                pendingCut = context.pending,
                diagnostic = context.diagnostic,
                pendingInteractionCut = context.pendingInteraction,
                prompt =
                    PromptTerminalFailureContext(
                        pendingSearchCut = context.pendingSearch,
                        searchDiagnostic = context.searchDiagnostic,
                        pendingOrderCut = context.pendingOrder,
                        orderDiagnostic = context.orderDiagnostic,
                        pendingCardSelectCut = context.pendingCardSelect,
                        cardSelectDiagnostic = context.cardSelectDiagnostic,
                        pendingStaticChoiceCut = context.pendingStaticChoice,
                        staticChoiceDiagnostic = context.staticChoiceDiagnostic,
                        pendingManaSourcePaymentCut = context.pendingManaSourcePayment,
                        manaSourcePaymentDiagnostic = context.manaSourcePaymentDiagnostic,
                        pendingOneShotPayCostsCut = context.pendingOneShotPayCosts,
                        oneShotPayCostsDiagnostic = context.oneShotPayCostsDiagnostic,
                    ),
                cause = cause,
            ).also { terminal ->
                context.pending?.let(owner::retainPendingCut)
                failure = terminal
                owner.interactions.terminate(terminal)
                owner.actions.terminate()
                owner.targeting.terminate(terminal)
                owner.search.terminate(terminal)
                owner.order.terminate(terminal)
                owner.cardSelect.terminate(terminal)
                owner.staticChoices.terminate(terminal)
                owner.manaSourcePayments.terminate(terminal)
                owner.oneShotPayCosts.terminate(terminal)
                owner.bridge.failActionWindows(terminal)
                owner.bridge.prioritySignal.signal()
            }
        }
}
