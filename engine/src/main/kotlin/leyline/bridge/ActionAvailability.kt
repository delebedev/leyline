package leyline.bridge

import forge.ai.ComputerUtilAbility
import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.SpellAbility

/** Shared executable-action checks for priority decisions and active action offers. */
internal object ActionAvailability {
    fun canExecute(
        sa: SpellAbility,
        player: Player,
    ): Boolean =
        ActionManaCosts.affordabilityProbe(
            probe = {
                NonInteractiveScope.bestEffort {
                    sa.activatingPlayer = player
                    sa.canPlay() &&
                        sa.payCosts?.canPay(sa, player, false) != false &&
                        hasLegalTargetsAndModes(sa) &&
                        canPayMana(sa, player)
                }
            },
            fallback = { false },
        )

    fun hasLegalTargetsAndModes(sa: SpellAbility): Boolean =
        (ComputerUtilAbility.isFullyTargetable(sa) || hasSequentialSingleTargets(sa)) &&
            (sa.api != ApiType.Charm || CharmEffect.makePossibleOptions(sa).isNotEmpty())

    /**
     * Independent candidate counts can reject a child whose controller depends on an
     * unchosen parent target. Retry single-target chains in order on an untracked copy;
     * the offered ability must stay unselected. Other cardinalities retain Forge's probe.
     */
    private fun hasSequentialSingleTargets(sa: SpellAbility): Boolean {
        val chain = generateSequence(sa) { it.subAbility }.filter { it.usesTargeting() }.toList()
        if (chain.isEmpty() ||
            chain.any {
                !it.targets.isEmpty() || it.minTargets != 1 || it.targetRestrictions.getMaxTargets(it.hostCard, it) != 1
            }
        ) {
            return false
        }
        return selectSingleTargets(sa.copy(sa.hostCard, sa.activatingPlayer, true))
    }

    private fun selectSingleTargets(sa: SpellAbility?): Boolean {
        if (sa == null) return true
        if (!sa.usesTargeting()) return selectSingleTargets(sa.subAbility)
        return sa.targetRestrictions.getAllCandidates(sa).any { candidate ->
            sa.targets.add(candidate)
            try {
                selectSingleTargets(sa.subAbility)
            } finally {
                sa.resetTargets()
            }
        }
    }

    private fun canPayMana(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val host = sa.hostCard
        val convoke = host?.hasKeyword(Keyword.CONVOKE) == true
        val improvise = host?.hasKeyword(Keyword.IMPROVISE) == true
        return if (convoke || improvise) {
            ActionManaCosts.canPayWithPaymentSourceReducer(sa, player, artifacts = improvise, creatures = convoke)
        } else {
            ActionManaCosts.canPayManaCost(sa, player)
        }
    }
}
