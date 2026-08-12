package leyline.game.state

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/**
 * Immutable prompt-derived projection data for one state-frame cut.
 *
 * The shell materializes this narrow value before compilation. Projection may
 * read these facts but never reaches back into a prompt bridge or journal.
 */
data class PromptProjectionFacts(
    val choiceResults: List<ChoiceResultFact> = emptyList(),
    val reveals: List<RevealFact> = emptyList(),
    val convokePayments: List<ConvokePaymentsFact> = emptyList(),
    val collectEvidenceCosts: List<CollectEvidenceFact> = emptyList(),
    val targetSpecs: List<PendingTargetSpecRecord> = emptyList(),
) {
    val activeReveal: RevealFact? get() = reveals.firstOrNull()

    data class ChoiceResultFact(
        val seatId: SeatId,
        val entry: PromptJournal.ChoiceResultEntry,
    ) {
        val result: PromptSideEffect.ChoiceResult get() = entry.result
    }

    data class RevealFact(
        val seatId: SeatId,
        val entry: PromptJournal.RevealEntry,
        val hasPendingPrompt: Boolean,
    ) {
        val reveal: PromptSideEffect.RevealStarted get() = entry.reveal
    }

    data class ConvokePaymentsFact(
        val seatId: SeatId,
        val entry: PromptJournal.ConvokePaymentsEntry,
    ) {
        val sourceForgeCardId: ForgeCardId get() = entry.sourceForgeCardId
        val payments: List<PromptSideEffect.ConvokePayment> get() = entry.payments
    }

    data class CollectEvidenceFact(
        val seatId: SeatId,
        val entry: PromptJournal.CollectEvidenceEntry,
    ) {
        val context: PromptSideEffect.CollectEvidenceCost get() = entry.context
    }
}

/** Exact prompt entries a successful state-frame commit may consume. */
data class PromptFactConsumption(
    val choiceResults: List<PromptProjectionFacts.ChoiceResultFact> = emptyList(),
    val staleReveals: List<PromptProjectionFacts.RevealFact> = emptyList(),
    val convokePayments: List<PromptProjectionFacts.ConvokePaymentsFact> = emptyList(),
    val collectEvidenceCosts: List<PromptProjectionFacts.CollectEvidenceFact> = emptyList(),
    val targetSpecs: List<PendingTargetSpecRecord> = emptyList(),
)

data class PendingTargetSpecRecord(
    val seatId: SeatId,
    val entry: InteractivePromptBridge.PendingTargetEntry,
) {
    val spec: InteractivePromptBridge.PendingTarget get() = entry.spec
}
