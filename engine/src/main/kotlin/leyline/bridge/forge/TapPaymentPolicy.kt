package leyline.bridge.forge

import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CardLists
import forge.game.cost.CostPart
import forge.game.cost.CostTapType
import forge.game.cost.CostUntapType
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.bridge.types.ForgeCardId
import org.slf4j.LoggerFactory

/** Grounded tap-payment routing facts derived from one exact Forge callback. */
internal object TapPaymentPolicy {
    data class Plan(
        val descriptor: TapPaymentDescriptor,
        val promptSource: PayCostsPromptSourceInput,
    )

    fun exact(
        cost: CostPart,
        required: Int,
        ability: SpellAbility,
    ): Plan? {
        if (cost is CostTapType && ability.isKeyword(Keyword.STATION)) return null
        val kind =
            when (cost) {
                is CostTapType -> TapPaymentKind.TapExact
                is CostUntapType -> TapPaymentKind.UntapExact
                else -> return null
            }
        return grounded(kind, required, ability)
    }

    fun totalPower(
        required: Int,
        ability: SpellAbility,
    ): Plan? {
        if (ability.isKeyword(Keyword.STATION)) return null
        return grounded(TapPaymentKind.TotalPower, required, ability)
    }

    fun totalPowerWeights(
        cards: CardCollectionView,
        ability: SpellAbility,
    ): List<Int> = cards.map { card -> CardLists.getTotalPower(CardCollection(card), ability) }

    private fun grounded(
        kind: TapPaymentKind,
        required: Int,
        ability: SpellAbility,
    ): Plan? {
        val descriptor =
            TapPaymentDescriptor.grounded(kind, required)
                ?: return auditResidual(kind, required, ability)
        val root = ability.rootAbility
        val source =
            if (root.isSpell) {
                PayCostsPromptSourceInput.StackCard(ForgeCardId(root.hostCard.id))
            } else {
                PayCostsPromptSourceInput.StackAbility(
                    forgeAbilityId = ability.id,
                    sourceForgeCardId = ForgeCardId(root.hostCard.id),
                    abilityDefinitionId = root.definitionId,
                    targetForgeCardIds =
                        root.targets
                            ?.targetCards
                            .orEmpty()
                            .map { ForgeCardId(it.id) },
                )
            }
        return Plan(descriptor, source)
    }

    private fun auditResidual(
        kind: TapPaymentKind,
        required: Int,
        ability: SpellAbility,
    ): Plan? {
        val event =
            log
                .atWarn()
                .addKeyValue("event", "payment.tap_unclassified")
                .addKeyValue("kind", kind.name)
                .addKeyValue("required", required)
        val sourcedEvent = ability.hostCard?.name?.let { event.addKeyValue("source_card", it) } ?: event
        sourcedEvent.log("Tap payment is unclassified")
        return null
    }

    private val log = LoggerFactory.getLogger(TapPaymentPolicy::class.java)
}
