package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId

/** Immutable prompt values materialized for one projection cut. */
data class PromptProjectionFacts(
    val choiceResults: List<ChoiceResultFact> = emptyList(),
    val reveals: List<RevealFact> = emptyList(),
    val convokePayments: List<ConvokePaymentsFact> = emptyList(),
    val collectEvidenceCosts: List<CollectEvidenceFact> = emptyList(),
    val targetSpecs: List<TargetSpecFact> = emptyList(),
) {
    val activeReveal: RevealFact? get() = reveals.firstOrNull()

    data class ChoiceResultFact(
        val key: PromptFactKey,
        val result: ChoiceResult,
    )

    data class RevealFact(
        val key: PromptFactKey,
        val reveal: RevealStarted,
        val hasPendingPrompt: Boolean,
    )

    data class ConvokePaymentsFact(
        val key: PromptFactKey,
        val sourceForgeCardId: ForgeCardId,
        val payments: List<ConvokePayment>,
    )

    data class CollectEvidenceFact(
        val key: PromptFactKey,
        val context: CollectEvidenceCost,
    )
}

data class PromptFactKey(
    val seatId: SeatId,
    val version: Long,
)

data class ChoiceResult(
    val sourceForgeCardId: ForgeCardId,
    val chooserSeatId: SeatId,
    val choiceValue: Int,
    val choiceDomain: Int?,
    val sentiment: Int,
)

data class RevealStarted(
    val allHandCardIds: List<ForgeCardId>,
    val ownerSeatId: SeatId,
)

data class ConvokePayment(
    val paymentForgeCardId: ForgeCardId,
    val color: Int,
    val substitutionGrpId: Int,
    val paymentAbilityGrpId: Int,
)

data class CollectEvidenceCost(
    val sourceForgeCardId: ForgeCardId,
    val threshold: Int,
)

data class TargetSpecFact(
    val key: PromptFactKey,
    val spec: TargetSpec,
)

data class TargetSpec(
    val spellForgeCardId: Int,
    val spellName: String,
    val index: Int,
    val affectorInstanceIdAtRecord: Int,
    val affectees: List<TargetAffectee>,
    val isStackAbility: Boolean,
    val promptId: Int?,
    val abilityIdentity: ResolvedAbilityIdentity?,
    val forgeAbilityId: Int,
)

data class TargetAffectee(
    val targetForgeCardId: Int?,
    val targetSeatId: Int?,
    val distribution: Int?,
)

/** Exact versioned journal entries consumed only after transition installation. */
data class PromptFactConsumption(
    val choiceResults: List<PromptFactKey> = emptyList(),
    val staleReveals: List<PromptFactKey> = emptyList(),
    val convokePayments: List<PromptFactKey> = emptyList(),
    val collectEvidenceCosts: List<PromptFactKey> = emptyList(),
    val targetSpecs: List<PromptFactKey> = emptyList(),
)
