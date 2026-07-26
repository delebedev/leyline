package leyline.game.state

import forge.game.GameActionUtil
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.ManaCostProjection
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

internal class DeferredCastCostInspector(
    private val bridge: GameBridge,
) {
    fun hybrid(
        seatId: SeatId,
        cardId: ForgeCardId,
        castAbilityIndex: Int?,
    ): HybridCastCostFacts? {
        val card = bridge.requireGame().findById(cardId.value) ?: return null
        val player = bridge.getPlayer(seatId) ?: return null
        val castable = getAllCastableAbilities(card, player)
        val ability = castAbilityIndex?.let { castable.getOrNull(it) } ?: castable.firstOrNull() ?: return null
        ability.setActivatingPlayer(player)
        val effectiveCost = ActionMapper.computeEffectiveCost(ability, player) ?: return null
        val paymentColors = ManaCostProjection.hybridOrTwoGenericColors(effectiveCost)
        if (paymentColors.isEmpty()) return null
        val baseCost = ability.payCosts?.totalMana
        val promptCost =
            baseCost
                ?.takeIf { ManaCostProjection.hybridOrTwoGenericColors(it).size == paymentColors.size }
                ?: effectiveCost
        return HybridCastCostFacts(
            cardName = card.name,
            promptColors = ManaCostProjection.hybridOrTwoGenericColors(promptCost),
            paymentColors = paymentColors,
            manaCost = ManaCostProjection.requirements(promptCost),
        )
    }

    fun optional(
        seatId: SeatId,
        cardId: ForgeCardId,
        castAbilityIndex: Int?,
        grpId: Int,
    ): OptionalCastCostFacts? {
        val card = bridge.requireGame().findById(cardId.value) ?: return null
        val player = bridge.getPlayer(seatId) ?: return null
        val castable = getAllCastableAbilities(card, player)
        val ability = castAbilityIndex?.let { castable.getOrNull(it) } ?: castable.firstOrNull() ?: return null
        ability.setActivatingPlayer(player)
        val optionalCosts = GameActionUtil.getOptionalCostValues(ability)
        val keywordNames =
            card.keywords
                .mapNotNull { it.keyword }
                .filter { it in binaryCastCostKeywords }
                .map { it.toString() }
        if (optionalCosts.isEmpty() && keywordNames.isEmpty()) return null
        val cardData = bridge.cardRepository.findByGrpId(grpId)
        val keywordCount = cardData?.let { bridge.abilityRegistryFor(card, it)?.slotLayout?.keywordCount } ?: 0
        val optionalEntries =
            optionalCosts.mapIndexed { index, cost ->
                val type =
                    when (cost.type) {
                        OptionalCost.Kicker1,
                        OptionalCost.Kicker2,
                        -> CastingTimeOptionType.Kicker
                        else -> CastingTimeOptionType.AdditionalCost
                    }
                val abilityGrpId =
                    if (cost.type == OptionalCost.Bargain || cost.type == OptionalCost.Teamwork) {
                        findKeywordSlot(card, cost.type.name, keywordCount)
                            ?.let { cardData?.abilityIds?.getOrNull(it)?.first }
                            ?: 0
                    } else {
                        cardData?.abilityIds?.getOrNull(keywordCount + index)?.first ?: 0
                    }
                OptionalCastCostEntry(type, abilityGrpId)
            }
        val keywordEntries =
            keywordNames.mapNotNull { name ->
                val slot = findKeywordSlot(card, name, keywordCount) ?: return@mapNotNull null
                OptionalCastCostEntry(
                    CastingTimeOptionType.AdditionalCost,
                    cardData?.abilityIds?.getOrNull(slot)?.first ?: 0,
                    keywordName = name,
                )
            }
        return OptionalCastCostFacts(card.name, cardData?.manaCost.orEmpty(), optionalEntries + keywordEntries)
    }

    fun alternate(
        seatId: SeatId,
        cardId: ForgeCardId,
    ): AlternateCastCostFacts? {
        val card = bridge.requireGame().findById(cardId.value) ?: return null
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) return null
        val player = bridge.getPlayer(seatId) ?: return null
        val castable = getAllCastableAbilities(card, player)
        if (castable.size <= 1) return null
        val promptIds = castable.map(::additionalCostPromptId)
        return AlternateCastCostFacts(
            cardName = card.name,
            optionPromptIds = if (promptIds.all { it != null }) promptIds.filterNotNull() else emptyList(),
            optionCount = castable.size,
        )
    }

    fun registerAlternateCommands(
        seatId: SeatId,
        pendingActionId: String,
        cardId: ForgeCardId,
        ctoIds: List<Int>,
    ): AlternateCastCommands? {
        val card = bridge.requireGame().findById(cardId.value) ?: return null
        val player = bridge.getPlayer(seatId) ?: return null
        val castable = getAllCastableAbilities(card, player)
        if (castable.size != ctoIds.size || ctoIds.isEmpty()) return null
        val tokens =
            ctoIds
                .mapIndexed { index, ctoId ->
                    ctoId to
                        bridge
                            .seat(seatId)
                            .action
                            .registerActionCommand(
                                pendingActionId,
                                PlayerAction.CastSpell(cardId = cardId, abilityId = index, ability = castable[index]),
                            )
                }.toMap()
        return AlternateCastCommands(checkNotNull(tokens[ctoIds.first()]), tokens)
    }

    private fun additionalCostPromptId(ability: SpellAbility): Int? {
        val costs = ability.payCosts ?: return null
        if (costs.isOnlyManaCost) return PromptIds.CHOOSE_OR_COST_PAY_MANA
        val names = costs.costParts.map { it.javaClass.simpleName }
        return when {
            names.any { it.contains("Sacrifice") } -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            names.any { it.contains("Exile") } -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            else -> null
        }
    }

    private fun findKeywordSlot(
        card: Card,
        keywordName: String,
        slotBound: Int,
    ): Int? =
        card.rules
            ?.mainPart
            ?.keywords
            ?.toList()
            ?.indexOfFirst { it.startsWith(keywordName) }
            ?.takeIf { it in 0 until slotBound }

    private val binaryCastCostKeywords = setOf(Keyword.OFFSPRING, Keyword.CASUALTY, Keyword.CONSPIRE)
}
