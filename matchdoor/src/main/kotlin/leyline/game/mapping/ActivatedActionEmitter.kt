package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaColorCount
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSelection
import wotc.mtgo.gre.external.messaging.Messages.ManaSelectionOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

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

    @Suppress("LongParameterList")
    fun emitPlayableNonManaActivatedAbilities(
        builder: ActionsAvailableReq.Builder,
        card: Card,
        player: Player,
        instanceId: () -> Int,
        grpId: () -> Int,
        cardData: (Int) -> CardData?,
        envelope: Envelope,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        autoTapSolution: (ManaCost) -> AutoTapSolution? = { null },
        skipDisguiseTurnFaceUp: Boolean = false,
    ) {
        for (ability in getNonManaActivatedAbilities(card, player)) {
            if (!ability.canPlay()) continue
            if (skipDisguiseTurnFaceUp && ability.isDisguiseUp) continue
            val canPay = ActionManaCosts.canPayManaCost(ability, player)
            val abilityCost = ability.payCosts?.totalMana
            val autoTap =
                if (canPay && abilityCost != null && !abilityCost.isNoCost) {
                    autoTapSolution(abilityCost)
                } else {
                    null
                }
            val actionInstanceId = instanceId()
            val actionGrpId = grpId()
            val actionCardData = cardData(actionGrpId)
            val registry = abilityRegistryLookup(card, actionCardData)
            val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
            emitActivatedAbilityAction(
                builder = builder,
                instanceId = actionInstanceId,
                grpId = actionGrpId,
                abilityGrpId = abilityGrpId,
                uniqueAbilityId = uniqueAbilityIdFor(actionCardData, abilityGrpId),
                abilityCost = abilityCost,
                autoTapSolution = autoTap,
                canPay = canPay,
                envelope = envelope,
            )
        }
    }

    fun emitActivatedAbilityAction(
        builder: ActionsAvailableReq.Builder,
        instanceId: Int,
        grpId: Int,
        abilityGrpId: Int,
        uniqueAbilityId: Int?,
        abilityCost: ManaCost?,
        autoTapSolution: AutoTapSolution? = null,
        canPay: Boolean,
        envelope: Envelope,
    ) {
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.Activate_add3)
                .setInstanceId(instanceId)
        if (envelope.includesSourceIdentity) {
            actionBuilder
                .setGrpId(grpId)
                .setFacetId(instanceId)
        }
        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
        uniqueAbilityId?.let(actionBuilder::setUniqueAbilityId)
        if (canPay && envelope.activeShouldStop) {
            actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
        }
        if ((!canPay || envelope.activeManaCost) && abilityCost != null && !abilityCost.isNoCost) {
            ActionManaCosts.addManaCostFromForge(abilityCost, actionBuilder, abilityGrpId)
        }
        autoTapSolution?.let(actionBuilder::setAutoTapSolution)
        if (canPay) {
            builder.addActions(actionBuilder)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    fun buildActivateManaAction(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): List<Action> {
        val cardData = cardDataLookup(leyline.bridge.types.GrpId(grpId))
        val registry = abilityRegistryLookup(card, cardData)
        return getPlayableManaAbilities(card, card.controller).mapNotNull { sa ->
            val abilityGrpId = registry?.forSpellAbility(sa.id) ?: basicLandAbilityGrpId(card)
            val colors = producedManaColors(sa)
            if (colors.isEmpty()) return@mapNotNull null

            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(ActionType.ActivateMana)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setIsBatchable(true)
            if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)
            uniqueAbilityIdFor(cardData, abilityGrpId)?.let(actionBuilder::setUniqueAbilityId)

            for ((idx, manaColor) in colors.withIndex()) {
                val manaInfo =
                    ManaInfo
                        .newBuilder()
                        .setManaId(INITIAL_MANA_ID + idx)
                        .setColor(manaColor)
                        .setSrcInstanceId(instanceId)
                        .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                        .setAbilityGrpId(abilityGrpId)
                        .setCount(1)
                if (card.type.isSnow) {
                    manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromSnow))
                }
                actionBuilder.addManaPaymentOptions(
                    ManaPaymentOption.newBuilder().addMana(manaInfo),
                )
            }

            val selection =
                ManaSelection
                    .newBuilder()
                    .setInstanceId(instanceId)
                    .setAbilityGrpId(abilityGrpId)
                    .setSelectionCount(1)
                    .setValidationType(SelectionValidationType.NonRepeatable)
            for (manaColor in colors) {
                selection.addOptions(
                    ManaSelectionOption
                        .newBuilder()
                        .setSelectedColor(manaColor)
                        .addMana(
                            ManaColorCount.newBuilder().setColor(manaColor).setCount(1),
                        ),
                )
            }
            actionBuilder.addManaSelections(selection)

            actionBuilder.build()
        }
    }

    fun basicLandAbilityGrpId(card: Card): Int = BasicLandAbilities.byForgeSubtypeNames(card.type.subtypes) ?: 0

    fun uniqueAbilityIdFor(
        cardData: CardData?,
        abilityGrpId: Int,
    ): Int? {
        if (abilityGrpId == 0) return null
        if (cardData == null) return INITIAL_UNIQUE_ABILITY_ID
        val index =
            cardData.abilityIds.indexOfFirst { (grpId, _) ->
                grpId == abilityGrpId
            }
        return index.takeIf { it >= 0 }?.let { INITIAL_UNIQUE_ABILITY_ID + it }
    }

    fun producedManaColors(sa: forge.game.spellability.SpellAbility): List<ManaColor> {
        val mana = sa.manaPart ?: return emptyList()
        val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
        return produced.split(" ").mapNotNull { ActionManaCosts.producedToManaColor(it) }.distinct()
    }
}
