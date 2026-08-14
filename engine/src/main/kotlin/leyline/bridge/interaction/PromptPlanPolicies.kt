package leyline.bridge.interaction

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateRefDto

enum class CandidateRefsPolicy {
    None,
    Selectable,
    SelectableAndUnfilteredForResolution,
}

fun CandidateRefsPolicy.candidateRefs(refs: List<PromptCandidateRefDto>): List<PromptCandidateRefDto> =
    when (this) {
        CandidateRefsPolicy.None -> emptyList()
        CandidateRefsPolicy.Selectable,
        CandidateRefsPolicy.SelectableAndUnfilteredForResolution,
        -> refs
    }

fun CandidateRefsPolicy.unfilteredRefs(
    refs: List<PromptCandidateRefDto>,
    semantic: PromptSemantic,
): List<PromptCandidateRefDto> =
    when {
        this == CandidateRefsPolicy.SelectableAndUnfilteredForResolution &&
            semantic == PromptSemantic.SelectNResolution -> refs
        else -> emptyList()
    }

enum class SourceIdPolicy {
    None,
    HostCard,
}

fun SourceIdPolicy.sourceEntityId(sa: SpellAbility?): Int? =
    when (this) {
        SourceIdPolicy.None -> null
        SourceIdPolicy.HostCard -> sa?.hostCard?.id
    }

enum class MandatoryChoicePolicy {
    AutoResolveWhenSatisfied,
    PromptWhenSatisfied,
}

fun MandatoryChoicePolicy.shouldAutoResolve(
    isOptional: Boolean,
    optionCount: Int,
    min: Int,
): Boolean = this == MandatoryChoicePolicy.AutoResolveWhenSatisfied && !isOptional && optionCount <= min

enum class AutoReturnPolicy {
    ReturnAllWhenSelectionSatisfied,
    Prompt,
}

fun resolutionRouteInput(
    refs: List<PromptCandidateRefDto>,
    optionCount: Int,
    abilityShape: ResolutionAbilityShape,
    allCandidatesProjectable: Boolean,
): ResolutionRouteInput =
    ResolutionRouteInput(
        optionCount = optionCount,
        candidateCount = refs.size,
        candidateKinds = refs.mapTo(linkedSetOf()) { it.kind },
        candidateZones = refs.mapTo(linkedSetOf()) { it.zone },
        abilityShape = abilityShape,
        allCandidatesProjectable = allCandidatesProjectable,
    )

val AutoReturnPolicy.shouldReturnAll: Boolean
    get() = this == AutoReturnPolicy.ReturnAllWhenSelectionSatisfied
