package leyline.bridge.coord

import leyline.bridge.handoff.PromptRuntimeBindings
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.types.SeatId
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.PromptTerminalEvidence

/** Owns the complete coordinator prompt-runtime inventory for one match. */
internal class MatchPromptRuntimeSet(
    private val owner: MatchCutCoordinator,
) {
    private val lifecycle = mutableListOf<PromptLifecycle>()

    val targeting = own(MatchTargetingInteractionRuntime(owner))
    val compatibilityCostSelection = MatchCompatibilityCostSelectionRuntime(owner)
    val blocking = own(MatchBlockingInteractionRuntime(owner))
    val search = own(MatchSearchInteractionRuntime(owner))
    val order = own(MatchOrderInteractionRuntime(owner))
    val distribution = own(MatchDistributionInteractionRuntime(owner))
    val grouping = own(MatchGroupingInteractionRuntime(owner))
    val cardSelect = own(MatchCardSelectInteractionRuntime(owner))
    val staticChoices = own(MatchStaticChoiceInteractionRuntime(owner))
    val revealChoices = own(MatchRevealChoiceInteractionRuntime(owner))
    val modalChoices = own(MatchModalChoiceRuntime(owner))
    val manaSourcePayments = own(MatchManaSourcePaymentRuntime(owner))
    val oneShotPayCosts = own(MatchOneShotPayCostsRuntime(owner))

    fun bindings(seatId: SeatId): PromptRuntimeBindings {
        check(seatId == owner.humanSeat) { "Prompt runtimes are only registered for the human seat" }
        return PromptRuntimeBindings(
            targeting = targeting,
            compatibilityCostSelection = compatibilityCostSelection,
            search = search,
            order = order,
            distribution = distribution,
            grouping = grouping,
            cardSelect = cardSelect,
            staticChoice = staticChoices,
            revealChoice = revealChoices,
            modalChoice = modalChoices,
            manaSourcePayment = manaSourcePayments,
            oneShotPayCosts = oneShotPayCosts,
        )
    }

    fun hasPendingInteraction(): Boolean = lifecycle.any { it.current() != null }

    fun hasRevealProjectionPrompt(): Boolean = revealChoices.current() != null || cardSelect.current() != null

    fun currentOneShotPayCosts(): PublishedOneShotPayCostsInteraction? = oneShotPayCosts.current()

    fun terminate(cause: Throwable) = lifecycle.forEach { it.terminate(cause) }

    fun reset() = lifecycle.forEach { it.reset() }

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            lifecycle
                .filterIsInstance<PromptTerminalCutOwner>()
                .sortedBy { it.terminalPriority }
                .forEach { entry ->
                    entry.claimTerminalCutLocked()?.let { pending ->
                        owner.failPrompt(cause, pending)
                    }
                }
            owner.fail(cause)
        }

    internal fun lifecycleOwners(): List<PromptLifecycle> = lifecycle.toList()

    private fun <T : PromptLifecycle> own(runtime: T): T = runtime.also(lifecycle::add)
}

internal fun MatchCutCoordinator.failPrompt(
    cause: Throwable,
    pending: PendingPromptCut<*>? = null,
    diagnostic: PromptMaterializationDiagnostic<*>? = null,
): Nothing {
    check(pending == null || diagnostic == null) { "Prompt failure cannot retain both a cut and a materialization diagnostic" }
    val evidence =
        pending?.let(PromptTerminalEvidence::Pending)
            ?: diagnostic?.let(PromptTerminalEvidence::Materialization)
    failTerminal(cause, MatchCutTerminalRuntime.Context(promptEvidence = evidence))
}
