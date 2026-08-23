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
    private data class Lifecycle(
        val runtime: Any,
        val current: () -> Any?,
        val terminate: (Throwable) -> Unit,
        val reset: () -> Unit,
        val pendingCutLocked: () -> PendingPromptCut<*>?,
    )

    private val lifecycle = mutableListOf<Lifecycle>()

    val targeting =
        own(
            MatchTargetingInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
        )
    val compatibilityCostSelection = MatchCompatibilityCostSelectionRuntime(owner)
    val blocking =
        own(
            MatchBlockingInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val search =
        own(
            MatchSearchInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val order =
        own(
            MatchOrderInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val grouping =
        own(
            MatchGroupingInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val cardSelect =
        own(
            MatchCardSelectInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val staticChoices =
        own(
            MatchStaticChoiceInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val revealChoices =
        own(
            MatchRevealChoiceInteractionRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.claimDeliveryFailureCutLocked() },
        )
    val modalChoices =
        own(
            MatchModalChoiceRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val manaSourcePayments =
        own(
            MatchManaSourcePaymentRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )
    val oneShotPayCosts =
        own(
            MatchOneShotPayCostsRuntime(owner),
            current = { it.current() },
            terminate = { runtime, cause -> runtime.terminate(cause) },
            reset = { it.reset() },
            pendingCutLocked = { it.pendingCutLocked() },
        )

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

    fun hasPendingInteraction(): Boolean = lifecycle.any { it.current() != null }

    fun hasRevealProjectionPrompt(): Boolean = revealChoices.current() != null || cardSelect.current() != null

    fun currentOneShotPayCosts(): PublishedOneShotPayCostsInteraction? = oneShotPayCosts.current()

    fun terminate(cause: Throwable) = lifecycle.forEach { it.terminate(cause) }

    fun reset() = lifecycle.forEach { it.reset() }

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            lifecycle.forEach { entry ->
                entry.pendingCutLocked()?.let { pending ->
                    owner.failPrompt(cause, pending)
                }
            }
            owner.fail(cause)
        }

    internal fun lifecycleOwners(): List<Any> = lifecycle.map { it.runtime }

    private fun <T : Any> own(
        runtime: T,
        current: (T) -> Any?,
        terminate: (T, Throwable) -> Unit,
        reset: (T) -> Unit,
        pendingCutLocked: (T) -> PendingPromptCut<*>? = { null },
    ): T =
        runtime.also {
            lifecycle +=
                Lifecycle(
                    runtime = runtime,
                    current = { current(runtime) },
                    terminate = { cause -> terminate(runtime, cause) },
                    reset = { reset(runtime) },
                    pendingCutLocked = { pendingCutLocked(runtime) },
                )
        }
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
