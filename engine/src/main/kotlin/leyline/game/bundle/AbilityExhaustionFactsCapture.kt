package leyline.game.bundle

import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbilityAdditionalActivations
import leyline.bridge.getNonManaActivatedAbilities
import leyline.game.data.CardData
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.GameBridge

/** Live shell adapter for final ability-exhaustion display rows at one frame cut. */
object AbilityExhaustionFactsCapture {
    fun capture(
        snapshot: GsmSnapshot,
        bridge: GameBridge,
    ): AbilityExhaustionFacts {
        val rows = mutableListOf<AbilityExhaustionFacts.Row>()
        for (bound in snapshot.boundCards.values) {
            val card = bridge.findCard(bound.forgeCardId) ?: continue
            val player = card.controller ?: continue
            val exhausted = exhaustedAbilities(card, player)
            if (exhausted.isEmpty()) continue
            val registry = bridge.abilityRegistryFor(card, bound.data) ?: continue
            for (ability in exhausted) {
                val abilityGrpId = registry.forSpellAbility(ability.definitionId)?.takeIf { it != 0 } ?: continue
                rows +=
                    AbilityExhaustionFacts.Row(
                        sourceForgeCardId = bound.forgeCardId,
                        abilityGrpId = abilityGrpId,
                        usesRemaining = remainingUses(card, ability, player),
                        uniqueAbilityId = uniqueAbilityIdFor(bound.data, abilityGrpId, ability),
                    )
            }
        }
        return AbilityExhaustionFacts(rows)
    }

    private fun exhaustedAbilities(
        card: Card,
        player: Player,
    ): List<SpellAbility> =
        (
            card.allSpellAbilities.orEmpty() +
                card.manaAbilities.orEmpty() +
                getNonManaActivatedAbilities(card, player)
        ).distinctBy { it.id }
            .filter { ability ->
                when {
                    ability.isBoast -> ability.activationsThisTurn > 0
                    ability.isExhaust -> ability.activationsThisGame > 0
                    else -> false
                }
            }

    private fun remainingUses(
        card: Card,
        ability: SpellAbility,
        player: Player,
    ): Int {
        val used = if (ability.isBoast) ability.activationsThisTurn else ability.activationsThisGame
        return (StaticAbilityAdditionalActivations.getLimit(card, ability, player) - used).coerceAtLeast(0)
    }

    private fun uniqueAbilityIdFor(
        cardData: CardData?,
        abilityGrpId: Int,
        ability: SpellAbility,
    ): Int {
        if (ability.isBoast) return BOAST_UNIQUE_ABILITY_ID
        return cardData
            ?.abilityIds
            .orEmpty()
            .indexOfFirst { (grpId, _) -> grpId == abilityGrpId }
            .takeIf { it >= 0 }
            ?.let { INITIAL_UNIQUE_ABILITY_ID + it }
            ?: INITIAL_UNIQUE_ABILITY_ID
    }

    private const val INITIAL_UNIQUE_ABILITY_ID = 50
    private const val BOAST_UNIQUE_ABILITY_ID = 374
}
