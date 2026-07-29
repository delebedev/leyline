package leyline.game.mapping

import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.ManaRequirementValue
import leyline.game.PreparedPriorityWindow
import leyline.game.PriorityActionSet
import leyline.game.PriorityActionValue
import leyline.game.PriorityAutoTapActionValue
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.PriorityCastKind
import leyline.game.PriorityManaColor
import leyline.game.PriorityManaInfoValue
import leyline.game.PriorityManaPaymentOptionValue
import leyline.game.PriorityManaSelectionOptionValue
import leyline.game.PriorityManaSelectionValidation
import leyline.game.PriorityManaSelectionValue
import leyline.game.PriorityManaSpec
import leyline.game.PriorityPlayKind
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AutoTapAction
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaColorCount
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import wotc.mtgo.gre.external.messaging.Messages.ManaSelection
import wotc.mtgo.gre.external.messaging.Messages.ManaSelectionOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/** Terminal owner-side translation from neutral priority values to protocol messages. */
internal object PriorityActionProjector {
    fun project(
        window: PreparedPriorityWindow,
        idResolver: (ForgeCardId) -> InstanceId,
    ): ActionMapper.ActionProjection {
        val active = window.actions.actions.map { project(it, idResolver) }
        val actions =
            ActionsAvailableReq
                .newBuilder()
                .addAllActions(active)
                .addAllInactiveActions(window.actions.inactiveActions.map { project(it, idResolver) })
                .build()
        val offers =
            window.offers.zip(active) { offer, action ->
                ActionOffer(
                    action = action,
                    token = offer.token,
                    cardId = offer.cardId,
                    abilityId = offer.abilityId,
                    stackAbilityGrpId = offer.stackAbilityGrpId,
                    forgeAbilityId = offer.forgeAbilityId,
                    spellGrpId = offer.spellGrpId,
                )
            }
        return ActionMapper.ActionProjection(actions, offers)
    }

    fun project(
        values: PriorityActionSet,
        idResolver: (ForgeCardId) -> InstanceId,
    ): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .addAllActions(values.actions.map { project(it, idResolver) })
            .addAllInactiveActions(values.inactiveActions.map { project(it, idResolver) })
            .build()

    fun project(
        value: PriorityActionValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action =
        when (value) {
            is PriorityActionValue.Cast -> projectCast(value, idResolver)
            is PriorityActionValue.Activate -> projectActivate(value, idResolver)
            is PriorityActionValue.ActivateMana -> projectActivateMana(value, idResolver)
            is PriorityActionValue.PlayLand -> projectPlay(value, idResolver)
            is PriorityActionValue.TurnFaceUp -> projectTurnFaceUp(value, idResolver)
            PriorityActionValue.Pass -> Action.newBuilder().setActionType(ActionType.Pass).build()
            PriorityActionValue.FloatMana -> Action.newBuilder().setActionType(ActionType.FloatMana).build()
        }

    private fun projectCast(
        value: PriorityActionValue.Cast,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val instanceId = idResolver(value.cardId).value
        return Action
            .newBuilder()
            .setActionType(value.kind.toProtocol())
            .setInstanceId(instanceId)
            .apply {
                value.grpId?.let {
                    grpId = it
                    facetId = idResolver(value.facetCardId ?: value.cardId).value
                }
                if (value.abilityGrpId != 0) abilityGrpId = value.abilityGrpId
                value.sourceCardId?.let { sourceId = idResolver(it).value }
                if (value.alternativeGrpId != 0) alternativeGrpId = value.alternativeGrpId
                addAllManaCost(value.manaCost.map(::project))
                if (value.shouldStop) shouldStop = true
                value.alternativeSourceCardId?.let { alternativeSourceZcid = idResolver(it).value }
                value.autoTapSolution?.let { autoTapSolution = projectAutoTap(it, idResolver) }
            }.build()
    }

    private fun projectActivate(
        value: PriorityActionValue.Activate,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val instanceId = idResolver(value.cardId).value
        return Action
            .newBuilder()
            .setActionType(ActionType.Activate_add3)
            .setInstanceId(instanceId)
            .apply {
                value.grpId?.let {
                    grpId = it
                    facetId = instanceId
                }
                if (value.abilityGrpId != 0) abilityGrpId = value.abilityGrpId
                if (value.uniqueAbilityId != 0) uniqueAbilityId = value.uniqueAbilityId
                addAllManaCost(value.manaCost.map(::project))
                if (value.shouldStop) shouldStop = true
                value.autoTapSolution?.let { autoTapSolution = projectAutoTap(it, idResolver) }
            }.build()
    }

    private fun projectActivateMana(
        value: PriorityActionValue.ActivateMana,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val instanceId = idResolver(value.cardId).value
        return Action
            .newBuilder()
            .setActionType(ActionType.ActivateMana)
            .setInstanceId(instanceId)
            .setGrpId(value.grpId)
            .setFacetId(instanceId)
            .apply {
                if (value.abilityGrpId != 0) abilityGrpId = value.abilityGrpId
                if (value.uniqueAbilityId != 0) uniqueAbilityId = value.uniqueAbilityId
                addAllManaPaymentOptions(value.manaPaymentOptions.map { project(it, idResolver) })
                addAllManaCost(value.manaCost.map(::project))
                addAllManaSelections(value.manaSelections.map { project(it, idResolver) })
                if (value.batchable) isBatchable = true
            }.build()
    }

    private fun projectPlay(
        value: PriorityActionValue.PlayLand,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val instanceId = idResolver(value.cardId).value
        return Action
            .newBuilder()
            .setActionType(
                when (value.kind) {
                    PriorityPlayKind.LAND -> ActionType.Play_add3
                    PriorityPlayKind.MDFC -> ActionType.PlayMdfc
                },
            ).setInstanceId(instanceId)
            .apply {
                value.grpId?.let {
                    grpId = it
                    facetId = instanceId
                }
                if (value.shouldStop) shouldStop = true
            }.build()
    }

    private fun projectTurnFaceUp(
        value: PriorityActionValue.TurnFaceUp,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val instanceId = idResolver(value.cardId).value
        return Action
            .newBuilder()
            .setActionType(ActionType.SpecialTurnFaceUp_add3)
            .setInstanceId(instanceId)
            .setAlternativeGrpId(value.alternativeGrpId)
            .setAlternativeSourceZcid(instanceId)
            .addAllManaCost(value.manaCost.map(::project))
            .setShouldStop(value.shouldStop)
            .build()
    }

    private fun PriorityCastKind.toProtocol(): ActionType =
        when (this) {
            PriorityCastKind.CAST -> ActionType.Cast
            PriorityCastKind.ADVENTURE -> ActionType.CastAdventure
            PriorityCastKind.MDFC -> ActionType.CastMdfc
            PriorityCastKind.LEFT_ROOM -> ActionType.CastLeftRoom
            PriorityCastKind.RIGHT_ROOM -> ActionType.CastRightRoom
            PriorityCastKind.OMEN -> ActionType.CastOmen
        }

    private fun project(value: ManaRequirementValue): ManaRequirement =
        ManaRequirement
            .newBuilder()
            .addAllColor(value.colors.mapNotNull(ManaColor::forNumber))
            .setCount(value.count)
            .apply {
                if (value.abilityGrpId != 0) abilityGrpId = value.abilityGrpId
            }.build()

    private fun project(
        value: PriorityManaPaymentOptionValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): ManaPaymentOption =
        ManaPaymentOption
            .newBuilder()
            .addAllMana(value.mana.map { project(it, idResolver) })
            .build()

    private fun project(
        value: PriorityManaInfoValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): ManaInfo =
        ManaInfo
            .newBuilder()
            .setManaId(value.manaId)
            .setColor(value.color.toProtocol())
            .setSrcInstanceId(idResolver(value.sourceCardId).value)
            .addAllSpecs(
                value.specs.map { spec ->
                    ManaInfo.Spec
                        .newBuilder()
                        .setType(spec.toProtocol())
                        .build()
                },
            ).setAbilityGrpId(value.abilityGrpId)
            .setCount(value.count)
            .build()

    private fun project(
        value: PriorityManaSelectionValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): ManaSelection =
        ManaSelection
            .newBuilder()
            .setInstanceId(idResolver(value.cardId).value)
            .setAbilityGrpId(value.abilityGrpId)
            .setSelectionCount(value.selectionCount)
            .setValidationType(value.validation.toProtocol())
            .addAllOptions(value.options.map(::project))
            .build()

    private fun project(value: PriorityManaSelectionOptionValue): ManaSelectionOption =
        ManaSelectionOption
            .newBuilder()
            .setSelectedColor(value.selectedColor.toProtocol())
            .addAllMana(
                value.mana.map { color ->
                    ManaColorCount
                        .newBuilder()
                        .setColor(color.color.toProtocol())
                        .setCount(color.count)
                        .build()
                },
            ).build()

    fun projectAutoTap(
        value: PriorityAutoTapSolutionValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): AutoTapSolution =
        AutoTapSolution
            .newBuilder()
            .addAllAutoTapActions(value.actions.map { project(it, idResolver) })
            .build()

    private fun project(
        value: PriorityAutoTapActionValue,
        idResolver: (ForgeCardId) -> InstanceId,
    ): AutoTapAction =
        AutoTapAction
            .newBuilder()
            .setInstanceId(idResolver(value.cardId).value)
            .setAbilityGrpId(value.abilityGrpId)
            .setManaPaymentOption(project(value.manaPaymentOption, idResolver))
            .build()

    private fun PriorityManaColor.toProtocol(): ManaColor =
        when (this) {
            PriorityManaColor.WHITE -> ManaColor.White_afc9
            PriorityManaColor.BLUE -> ManaColor.Blue_afc9
            PriorityManaColor.BLACK -> ManaColor.Black_afc9
            PriorityManaColor.RED -> ManaColor.Red_afc9
            PriorityManaColor.GREEN -> ManaColor.Green_afc9
            PriorityManaColor.GENERIC -> ManaColor.Generic
            PriorityManaColor.COLORLESS -> ManaColor.Colorless_afc9
            PriorityManaColor.SNOW -> ManaColor.Snow_afc9
            PriorityManaColor.TWO_GENERIC -> ManaColor.TwoGeneric
        }

    private fun PriorityManaSpec.toProtocol(): ManaSpecType =
        when (this) {
            PriorityManaSpec.PREDICTIVE -> ManaSpecType.Predictive
            PriorityManaSpec.FROM_SNOW -> ManaSpecType.FromSnow
        }

    private fun PriorityManaSelectionValidation.toProtocol(): SelectionValidationType =
        when (this) {
            PriorityManaSelectionValidation.NON_REPEATABLE -> SelectionValidationType.NonRepeatable
        }
}
