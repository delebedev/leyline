package leyline.bridge

import forge.ai.ComputerUtilAbility
import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.StrictPromptRefusalException

/** Whether a priority action can be paid for, as far as Leyline can tell before payment. */
internal enum class Affordability {
    Affordable,
    Unaffordable,

    /** Exact estimates refuse or fail, but [ManaAffordabilityBound] still allows payment. */
    Unsure,
    ;

    val offered: Boolean get() = this != Unaffordable
}

/**
 * Shared executable-action checks for priority decisions and active action offers.
 *
 * Hiding a payable action removes a legal play, while offering an unpayable one
 * costs only a refused payment that Forge rolls back. Actions are therefore
 * offered, and block automatic passing, unless they are proven unaffordable.
 */
internal object ActionAvailability {
    private val observer = ThreadLocal<((SpellAbility, Affordability) -> Unit)?>()

    fun canExecute(
        sa: SpellAbility,
        player: Player,
    ): Boolean = affordability(sa, player).offered

    /**
     * Timing, non-mana costs, targets and modes must pass. Mana is affordable when
     * an exact estimate pays it and unaffordable only when the generous bound also
     * fails. A probe that throws is unsure.
     */
    fun affordability(
        sa: SpellAbility,
        player: Player,
    ): Affordability {
        val verdict =
            try {
                NonInteractiveScope.bestEffort {
                    sa.activatingPlayer = player
                    when {
                        !sa.canPlay() || sa.payCosts?.canPay(sa, player, false) == false || !hasLegalTargetsAndModes(sa) ->
                            Affordability.Unaffordable
                        canPayMana(sa, player) -> Affordability.Affordable
                        ManaAffordabilityBound.allows(sa, player) -> Affordability.Unsure
                        else -> Affordability.Unaffordable
                    }
                }
            } catch (refusal: StrictPromptRefusalException) {
                throw refusal
            } catch (_: Exception) {
                Affordability.Unsure
            }
        observer.get()?.invoke(sa, verdict)
        return verdict
    }

    /** Run [block] while reporting each verdict on this thread to [onVerdict]. */
    fun <T> observing(
        onVerdict: (SpellAbility, Affordability) -> Unit,
        block: () -> T,
    ): T {
        val previous = observer.get()
        observer.set(onVerdict)
        try {
            return block()
        } finally {
            observer.set(previous)
        }
    }

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
