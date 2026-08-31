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
    internal val settled = own(SettledPromptOwner(owner))

    val targeting = own(MatchTargetingInteractionRuntime(owner))
    val compatibilityCostSelection = MatchCompatibilityCostSelectionRuntime(owner)
    val blocking = own(MatchBlockingInteractionRuntime(owner))
    val search = MatchSearchInteractionRuntime(owner, settled)
    val replacement = MatchReplacementInteractionRuntime(owner, settled)
    val order = MatchOrderInteractionRuntime(owner, settled)
    val distribution = MatchDistributionInteractionRuntime(owner, settled)
    val grouping = MatchGroupingInteractionRuntime(owner, settled)
    val cardSelect = MatchCardSelectInteractionRuntime(owner, settled)
    val staticChoices = MatchStaticChoiceInteractionRuntime(owner, settled)
    val revealChoices = MatchRevealChoiceInteractionRuntime(owner, settled)
    val modalChoices = MatchModalChoiceRuntime(owner, settled)
    val manaSourcePayments = own(MatchManaSourcePaymentRuntime(owner))
    val oneShotPayCosts = MatchOneShotPayCostsRuntime(owner, settled)

    fun bindings(seatId: SeatId): PromptRuntimeBindings {
        check(seatId == owner.humanSeat) { "Prompt runtimes are only registered for the human seat" }
        return PromptRuntimeBindings(
            targeting = targeting,
            compatibilityCostSelection = compatibilityCostSelection,
            search = search,
            replacement = replacement,
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
            val candidate =
                lifecycle
                    .filterIsInstance<PromptTerminalCutOwner>()
                    .mapNotNull(PromptTerminalCutOwner::terminalCutCandidateLocked)
                    .minByOrNull { it.priority }
            candidate?.let { owner.failPrompt(cause, it.cut) }
            owner.fail(cause)
        }

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
