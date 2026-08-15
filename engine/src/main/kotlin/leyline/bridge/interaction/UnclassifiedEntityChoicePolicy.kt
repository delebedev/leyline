package leyline.bridge.interaction

import leyline.DevCheck
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.PromptCandidateKind

/** Deterministic disposition for a resolution choice outside a typed card route. */
internal object UnclassifiedEntityChoicePolicy {
    enum class Domain {
        IncompleteDomain,
        PlayerOnly,
        MixedDomain,
        UnprojectableCard,
        UnsupportedHiddenLibrary,
    }

    data class Decision(
        val domain: Domain,
        val indices: List<Int>,
    )

    fun decide(
        request: PromptRequest,
        optional: Boolean,
        allCandidatesProjectable: Boolean,
    ): Decision? {
        if (request.route !is ResolvedPromptRoute.UnclassifiedEntityChoice) return null
        val domain = classify(request, allCandidatesProjectable)
        DevCheck.fail {
            "Resolution choice has unsupported entity domain: ${domain.name}"
        }
        val indices =
            if (optional) {
                emptyList()
            } else {
                (0 until request.min.coerceAtMost(request.options.size)).toList()
            }
        return Decision(domain, indices)
    }

    private fun classify(
        request: PromptRequest,
        allCandidatesProjectable: Boolean,
    ): Domain {
        val refs = request.candidateRefs
        if (refs.size != request.options.size || refs.map { it.index }.toSet().size != request.options.size) {
            return Domain.IncompleteDomain
        }
        val kinds = refs.map { it.kind }.toSet()
        if (kinds == setOf(PromptCandidateKind.Player)) return Domain.PlayerOnly
        if (kinds.size != 1) return Domain.MixedDomain
        if (refs.any { it.zone.equals("Library", ignoreCase = true) }) {
            return Domain.UnsupportedHiddenLibrary
        }
        if (!allCandidatesProjectable) return Domain.UnprojectableCard
        return Domain.IncompleteDomain
    }
}
