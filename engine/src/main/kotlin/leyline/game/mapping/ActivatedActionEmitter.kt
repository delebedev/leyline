package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.PriorityActionValue
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.PriorityManaColorCountValue
import leyline.game.PriorityManaInfoValue
import leyline.game.PriorityManaPaymentOptionValue
import leyline.game.PriorityManaSelectionOptionValue
import leyline.game.PriorityManaSelectionValidation
import leyline.game.PriorityManaSelectionValue
import leyline.game.PriorityManaSpec
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Prepares activated-action values after [ActionMapper] selects eligible sources.
 *
 * Forge identities stay intact until the owner-side terminal projector assigns
 * protocol instance ids.
 */
internal object ActivatedActionEmitter {
    private const val INITIAL_MANA_ID = 10
    private const val INITIAL_UNIQUE_ABILITY_ID = 50

    enum class Envelope(
        val includesSourceIdentity: Boolean,
        val activeShouldStop: Boolean,
        val activeManaCost: Boolean,
    ) {
        PERMANENT_SOURCE(includesSourceIdentity = true, activeShouldStop = true, activeManaCost = true),
        ABILITY_ONLY(includesSourceIdentity = false, activeShouldStop = false, activeManaCost = true),
    }

    data class PreparedManaAction(
        val action: PriorityActionValue.ActivateMana,
        val abilityIndex: Int,
        val ability: SpellAbility,
    )

    data class PreparedActivatedAction(
        val action: PriorityActionValue.Activate,
        val abilityIndex: Int,
        val ability: SpellAbility,
        val abilityGrpId: Int,
        val active: Boolean,
    )

    @Suppress("LongParameterList")
    fun preparePlayableNonManaActivatedAbilities(
        card: Card,
        player: Player,
        grpId: () -> Int,
        cardData: (Int) -> CardData?,
        envelope: Envelope,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        autoTapSolution: (ManaCost) -> PriorityAutoTapSolutionValue? = { null },
        skipSpecialTurnFaceUp: Boolean = false,
        abilities: List<SpellAbility> = getNonManaActivatedAbilities(card, player),
    ): List<PreparedActivatedAction> {
        val cardId = ForgeCardId(card.id)
        return buildList {
            for ((abilityIndex, ability) in abilities.withIndex()) {
                if (!ability.canPlay()) continue
                if (skipSpecialTurnFaceUp && ability.isTurnFaceUp) continue
                val canPay = ActionManaCosts.canPayManaCost(ability, player)
                val abilityCost = CastDisplayCost.of(ability, player) ?: ability.payCosts?.totalMana
                val autoTap =
                    if (canPay && abilityCost != null && !abilityCost.isNoCost) {
                        autoTapSolution(abilityCost)
                    } else {
                        null
                    }
                val actionGrpId = grpId()
                val actionCardData = cardData(actionGrpId)
                val registry = abilityRegistryLookup(card, actionCardData)
                val abilityGrpId = registry?.forSpellAbility(ability.definitionId) ?: 0
                add(
                    PreparedActivatedAction(
                        action =
                            prepareActivatedAbilityAction(
                                cardId = cardId,
                                grpId = actionGrpId.takeIf { envelope.includesSourceIdentity },
                                abilityGrpId = abilityGrpId,
                                uniqueAbilityId = uniqueAbilityIdFor(actionCardData, abilityGrpId),
                                abilityCost = abilityCost,
                                autoTapSolution = autoTap,
                                canPay = canPay,
                                envelope = envelope,
                            ),
                        abilityIndex = abilityIndex,
                        ability = ability,
                        abilityGrpId = abilityGrpId,
                        active = canPay,
                    ),
                )
            }
        }
    }

    @Suppress("LongParameterList")
    fun prepareActivatedAbilityAction(
        cardId: ForgeCardId,
        grpId: Int?,
        abilityGrpId: Int,
        uniqueAbilityId: Int?,
        abilityCost: ManaCost?,
        autoTapSolution: PriorityAutoTapSolutionValue? = null,
        canPay: Boolean,
        envelope: Envelope,
    ): PriorityActionValue.Activate =
        PriorityActionValue.Activate(
            cardId = cardId,
            grpId = grpId,
            abilityGrpId = abilityGrpId,
            uniqueAbilityId = uniqueAbilityId ?: 0,
            manaCost =
                if ((!canPay || envelope.activeManaCost) && abilityCost != null && !abilityCost.isNoCost) {
                    ActionManaCosts.forgeManaCostToValues(abilityCost, abilityGrpId)
                } else {
                    emptyList()
                },
            shouldStop = canPay && envelope.activeShouldStop && ShouldStopEvaluator.shouldStop(ActionType.Activate_add3),
            autoTapSolution = autoTapSolution,
        )

    fun prepareActivateManaActions(
        card: Card,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        abilities: List<SpellAbility> = getPlayableManaAbilities(card, card.controller),
    ): List<PreparedManaAction> {
        val cardId = ForgeCardId(card.id)
        val cardData = cardDataLookup(leyline.bridge.types.GrpId(grpId))
        val registry = abilityRegistryLookup(card, cardData)
        val basicLandAbilityGrpId = basicLandAbilityGrpId(card)
        return abilities.mapIndexedNotNull { abilityIndex, sa ->
            val abilityGrpId = registry?.forSpellAbility(sa.definitionId) ?: basicLandAbilityGrpId(card)
            val colors = producedManaColors(sa)
            if (colors.isEmpty()) return@mapIndexedNotNull null

            val value =
                PriorityActionValue.ActivateMana(
                    cardId = cardId,
                    grpId = grpId,
                    abilityGrpId = abilityGrpId,
                    uniqueAbilityId =
                        uniqueAbilityIdFor(
                            cardData,
                            abilityGrpId,
                            fallbackWhenUnmapped = abilityGrpId == basicLandAbilityGrpId,
                        ) ?: 0,
                    manaPaymentOptions =
                        colors.mapIndexed { index, manaColor ->
                            PriorityManaPaymentOptionValue(
                                mana =
                                    listOf(
                                        PriorityManaInfoValue(
                                            manaId = INITIAL_MANA_ID + index,
                                            color = manaColor.toPriorityManaColor(),
                                            sourceCardId = cardId,
                                            specs =
                                                buildSet {
                                                    add(PriorityManaSpec.PREDICTIVE)
                                                    if (card.type.isSnow) add(PriorityManaSpec.FROM_SNOW)
                                                },
                                            abilityGrpId = abilityGrpId,
                                            count = 1,
                                        ),
                                    ),
                            )
                        },
                    manaSelections =
                        listOf(
                            PriorityManaSelectionValue(
                                cardId = cardId,
                                abilityGrpId = abilityGrpId,
                                selectionCount = 1,
                                validation = PriorityManaSelectionValidation.NON_REPEATABLE,
                                options =
                                    colors.map { manaColor ->
                                        val color = manaColor.toPriorityManaColor()
                                        PriorityManaSelectionOptionValue(
                                            selectedColor = color,
                                            mana = listOf(PriorityManaColorCountValue(color, 1)),
                                        )
                                    },
                            ),
                        ),
                    batchable = true,
                )
            PreparedManaAction(value, abilityIndex, sa)
        }
    }

    fun prepareInactiveActivateManaActions(
        card: Card,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): List<PriorityActionValue.ActivateMana> {
        val cardId = ForgeCardId(card.id)
        val cardData = cardDataLookup(leyline.bridge.types.GrpId(grpId))
        val registry = abilityRegistryLookup(card, cardData)
        val basicLandAbilityGrpId = basicLandAbilityGrpId(card)
        return card.manaAbilities.mapNotNull { ability ->
            ability.setActivatingPlayer(card.controller)
            if (ability.canPlay()) return@mapNotNull null
            val abilityGrpId = registry?.forSpellAbility(ability.definitionId) ?: basicLandAbilityGrpId(card)
            PriorityActionValue.ActivateMana(
                cardId = cardId,
                grpId = grpId,
                abilityGrpId = abilityGrpId,
                uniqueAbilityId =
                    uniqueAbilityIdFor(
                        cardData,
                        abilityGrpId,
                        fallbackWhenUnmapped = abilityGrpId == basicLandAbilityGrpId,
                    ) ?: 0,
                manaCost =
                    ability.payCosts
                        ?.totalMana
                        ?.takeIf { !it.isNoCost }
                        ?.let { ActionManaCosts.forgeManaCostToValues(it, abilityGrpId) }
                        .orEmpty(),
                batchable = false,
            )
        }
    }

    @Suppress("LongParameterList")
    fun emitPlayableNonManaActivatedAbilities(
        builder: ActionsAvailableReq.Builder,
        card: Card,
        player: Player,
        grpId: () -> Int,
        cardData: (Int) -> CardData?,
        envelope: Envelope,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        idResolver: (ForgeCardId) -> InstanceId,
        autoTapSolution: (ManaCost) -> PriorityAutoTapSolutionValue? = { null },
        skipSpecialTurnFaceUp: Boolean = false,
        onActive: (Action, Int, SpellAbility, Int) -> Unit = { _, _, _, _ -> },
        abilities: List<SpellAbility> = getNonManaActivatedAbilities(card, player),
    ) {
        val values = PriorityActionSetBuilder()
        preparePlayableNonManaActivatedAbilities(
            card = card,
            player = player,
            grpId = grpId,
            cardData = cardData,
            envelope = envelope,
            abilityRegistryLookup = abilityRegistryLookup,
            autoTapSolution = autoTapSolution,
            skipSpecialTurnFaceUp = skipSpecialTurnFaceUp,
            abilities = abilities,
        ).forEach { prepared ->
            if (prepared.active) {
                values.addAction(prepared.action)
                onActive(
                    PriorityActionProjector.project(prepared.action, idResolver),
                    prepared.abilityIndex,
                    prepared.ability,
                    prepared.abilityGrpId,
                )
            } else {
                values.addInactiveAction(prepared.action)
            }
        }
        val projected = PriorityActionProjector.project(values.build(), idResolver)
        builder.addAllActions(projected.actionsList)
        builder.addAllInactiveActions(projected.inactiveActionsList)
    }

    fun buildActivateManaAction(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): List<Action> {
        val idResolver: (ForgeCardId) -> InstanceId = { InstanceId(instanceId) }
        return prepareActivateManaActions(card, grpId, cardDataLookup, abilityRegistryLookup)
            .map { PriorityActionProjector.project(it.action, idResolver) }
    }

    fun basicLandAbilityGrpId(card: Card): Int = BasicLandAbilities.byForgeSubtypeNames(card.type.subtypes) ?: 0

    fun uniqueAbilityIdFor(
        cardData: CardData?,
        abilityGrpId: Int,
        fallbackWhenUnmapped: Boolean = false,
    ): Int? {
        if (abilityGrpId == 0) return null
        if (cardData == null) return INITIAL_UNIQUE_ABILITY_ID
        val index = cardData.abilityIds.indexOfFirst { (grpId, _) -> grpId == abilityGrpId }
        return when {
            index >= 0 -> INITIAL_UNIQUE_ABILITY_ID + index
            fallbackWhenUnmapped -> INITIAL_UNIQUE_ABILITY_ID
            else -> null
        }
    }

    fun producedManaColors(sa: SpellAbility): List<ManaColor> {
        val mana = sa.manaPart ?: return emptyList()
        val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
        return produced.split(" ").mapNotNull { ActionManaCosts.producedToManaColor(it) }.distinct()
    }
}
