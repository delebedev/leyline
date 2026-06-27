package leyline.acceptance

import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Suppress("LargeClass") // Executor grows one small adapter per backend-neutral DSL verb.
class MatchdoorAcceptanceExecutor(
    private val seed: Long = 42L,
) {
    fun runScenario(scenario: AcceptanceScenario): Int {
        require(scenario.steps.isNotEmpty()) { "scenario ${scenario.id} has no executable steps" }
        val harness = MatchFlowHarness(seed = seed)
        try {
            harness.connectAndKeepPuzzleText(readPuzzleText(scenario.puzzle))
            scenario.steps.forEachIndexed { index, step ->
                executeStep(harness, scenario, index, step)
                harness.accumulator.assertConsistent("${scenario.id} step ${index + 1} ${step.label}")
            }
            return scenario.steps.size
        } finally {
            harness.shutdown()
        }
    }

    private fun executeStep(
        harness: MatchFlowHarness,
        scenario: AcceptanceScenario,
        index: Int,
        step: AcceptanceStep,
    ) {
        val context = "${scenario.id} step ${index + 1} (${step.label})"
        when (step) {
            is WaitStep -> assertConditions(harness, step.conditions, context)
            is ExpectStep -> assertConditions(harness, step.conditions, context)
            is PassUntilStep -> passUntil(harness, step, context)
            is ActivateStep -> activate(harness, step, context)
            is ChooseStep -> choose(harness, step, context)
            is ManaTypeChoicesStep -> manaTypeChoices(harness, step, context)
            is ModalChoiceStep -> modalChoice(harness, step, context)
            is StaticChoiceStep -> staticChoice(harness, step, context)
            is OptionalActionStep -> harness.respondToOptionalAction(step.accept)
            is TargetStep -> target(harness, step.target, context)
            is SelectCostStep -> selectCost(harness, step)
            is SelectCardStep -> selectCard(harness, step, context)
            is SelectCardsStep -> selectCards(harness, step, context)
            is SearchCardsStep -> searchCards(harness, step, context)
            is OrderCardsStep -> orderCards(harness, step, context)
            is BlockStep -> block(harness, step, context)
            is AttackStep -> attack(harness, step, context)
            is TurnFaceUpStep -> turnFaceUp(harness, step, context)
            is PlayLandStep -> requireAction(context) { harness.playLand(step.card) }
            is CastStep -> cast(harness, step, context)
            ResolveStackStep -> resolveStack(harness, context)
            AttackAllStep -> {
                harness.declareAllAttackers()
                harness.submitAttackers()
            }
        }
    }

    private fun cast(
        harness: MatchFlowHarness,
        step: CastStep,
        context: String,
    ) {
        val action =
            harness.accumulator.actions?.actionsList.orEmpty().firstOrNull { action ->
                action.actionType == ActionType.Cast &&
                    actionCardName(harness, action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(harness, action, step.zone) &&
                    actionMatchesAltCost(harness, action, step.altCost)
            } ?: error("$context no cast action for ${step.card} in ${step.zone.yamlName}")
        submitAction(harness, action)
    }

    private fun selectCost(
        harness: MatchFlowHarness,
        step: SelectCostStep,
    ) {
        val ids = step.cards.map { resolveCardInZone(harness, step.side, step.zone, it) }
        harness.respondToEffectCost(ids)
    }

    private fun selectCard(
        harness: MatchFlowHarness,
        step: SelectCardStep,
        context: String,
    ) = selectCards(harness, SelectCardsStep(step.side, step.zone, listOf(step.card)), context)

    private fun selectCards(
        harness: MatchFlowHarness,
        step: SelectCardsStep,
        context: String,
    ) {
        val prompt = latestPromptMessage(harness)
        require(prompt?.hasSelectNReq() == true) {
            "$context expected latest prompt SelectNReq"
        }
        val selectedIds = step.cards.map { resolveCardInZone(harness, step.side, step.zone, it) }
        selectedIds.zip(step.cards).forEach { (selectedId, card) ->
            require(selectedId in prompt.selectNReq.idsList) {
                "$context selected $card iid=$selectedId is not in SelectNReq candidates ${prompt.selectNReq.idsList}"
            }
        }
        if (step.zone == AcceptanceZone.Sideboard) {
            require(prompt.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || prompt.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
                "$context sideboard selection expected Learn prompt, got promptId=${prompt.prompt.promptId}"
            }
        }
        harness.respondToSelectN(selectedIds)
    }

    private fun searchCards(
        harness: MatchFlowHarness,
        step: SearchCardsStep,
        context: String,
    ) {
        val prompt = latestPromptMessage(harness)
        require(prompt?.hasSearchReq() == true) {
            "$context expected latest prompt SearchReq"
        }
        val selectedIds = step.cards.map { resolveCardInZone(harness, step.side, AcceptanceZone.Library, it) }
        selectedIds.zip(step.cards).forEach { (selectedId, card) ->
            require(selectedId in prompt.searchReq.itemsSoughtList) {
                "$context selected $card iid=$selectedId is not in SearchReq candidates ${prompt.searchReq.itemsSoughtList}"
            }
        }
        harness.respondToSearch(selectedIds)
    }

    private fun orderCards(
        harness: MatchFlowHarness,
        step: OrderCardsStep,
        context: String,
    ) {
        val prompt = latestPromptMessage(harness)
        require(prompt?.hasOrderReq() == true) {
            "$context expected latest prompt OrderReq"
        }
        val orderedIds = resolvePromptCardOrder(harness, prompt.orderReq.idsList, step.cards, context)
        harness.respondToOrder(orderedIds)
    }

    private fun activate(
        harness: MatchFlowHarness,
        step: ActivateStep,
        context: String,
    ) {
        val matching =
            harness.accumulator.actions?.actionsList.orEmpty().filter { action ->
                action.actionType == ActionType.Activate_add3 &&
                    actionCardName(harness, action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(harness, action, step.zone)
            }
        val action =
            matching.getOrNull(step.abilityIndex)
                ?: error(
                    "$context no activate action index ${step.abilityIndex} for ${step.card} in ${step.zone.yamlName}",
                )
        submitAction(harness, action)
    }

    private fun choose(
        harness: MatchFlowHarness,
        step: ChooseStep,
        context: String,
    ) {
        if (step.ctoId != null) {
            harness.respondToOptionalCost(step.ctoId)
            return
        }
        val option =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.firstOrNull { it.castingTimeOptionType == step.optionalCost!!.toProtoType() }
                ?: error(
                    "$context missing ${step.optionalCost?.yamlName} option in latest CastingTimeOptionsReq",
                )
        harness.respondToOptionalCost(option.ctoId)
    }

    private fun manaTypeChoices(
        harness: MatchFlowHarness,
        step: ManaTypeChoicesStep,
        context: String,
    ) {
        val options =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.filter { it.castingTimeOptionType == CastingTimeOptionType.ManaType }
                ?: error("$context missing ManaType options in latest CastingTimeOptionsReq")
        require(options.size == step.choices.size) {
            "$context expected ${step.choices.size} ManaType choices, got ${options.size}"
        }
        harness.respondToManaTypeChoices(options.zip(step.choices).map { (option, choice) -> option.ctoId to choice.toManaColor() })
    }

    private fun AcceptanceManaTypeChoice.toManaColor(): ManaColor =
        when (this) {
            AcceptanceManaTypeChoice.TwoGeneric -> ManaColor.TwoGeneric
            AcceptanceManaTypeChoice.White -> ManaColor.White_afc9
            AcceptanceManaTypeChoice.Blue -> ManaColor.Blue_afc9
            AcceptanceManaTypeChoice.Black -> ManaColor.Black_afc9
            AcceptanceManaTypeChoice.Red -> ManaColor.Red_afc9
            AcceptanceManaTypeChoice.Green -> ManaColor.Green_afc9
        }

    private fun modalChoice(
        harness: MatchFlowHarness,
        step: ModalChoiceStep,
        context: String,
    ) {
        val option =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.flatMap { it.modalReq.modalOptionsList }
                ?.getOrNull(step.index)
                ?: error("$context missing modal option index ${step.index}")
        harness.respondModalChoice(listOf(option.grpId))
    }

    private fun staticChoice(
        harness: MatchFlowHarness,
        step: StaticChoiceStep,
        context: String,
    ) {
        val prompt = latestPromptMessage(harness)
        require(prompt?.hasSelectNReq() == true) {
            "$context expected latest prompt SelectNReq"
        }
        val req = prompt.selectNReq
        require(req.listType == SelectionListType.Static || req.listType == SelectionListType.StaticSubset) {
            "$context expected static SelectNReq, got listType=${req.listType}"
        }
        require(req.idsList.isEmpty() || step.id in req.idsList) {
            "$context static choice id=${step.id} not in SelectNReq ids ${req.idsList}"
        }
        harness.respondToSelectN(listOf(step.id))
    }

    private fun target(
        harness: MatchFlowHarness,
        target: AcceptanceTargetSpec,
        context: String,
    ) {
        require(latestPromptMatches(harness, "SelectTargetsReq")) {
            "$context expected latest prompt SelectTargetsReq"
        }
        harness.selectTargets(listOf(resolveTargetInstanceId(harness, target)))
    }

    private fun block(
        harness: MatchFlowHarness,
        step: BlockStep,
        context: String,
    ) {
        require(latestPromptMatches(harness, "DeclareBlockersReq")) {
            "$context expected latest prompt DeclareBlockersReq"
        }
        val blockerId = resolveBattlefieldCard(harness, AcceptanceSide.Ours, step.blocker)
        val attackerId =
            resolveBattlefieldCard(harness, AcceptanceSide.Opponent, step.attacker)
        harness.declareBlockers(mapOf(blockerId to attackerId))
    }

    private fun attack(
        harness: MatchFlowHarness,
        step: AttackStep,
        context: String,
    ) {
        require(latestPromptMatches(harness, "DeclareAttackersReq")) {
            "$context expected latest prompt DeclareAttackersReq"
        }
        val attackerIds = step.cards.map { resolveBattlefieldCard(harness, AcceptanceSide.Ours, it) }
        val alternatives = step.altCost?.let { altCost -> attackerIds.associateWith { keywordAbilityId(altCost) } }.orEmpty()
        val damageRecipients =
            step.target
                ?.let { target -> attackerIds.associateWith { damageRecipientForAttackTarget(harness, target, context) } }
                .orEmpty()
        harness.toggleAttackers(attackerIds, alternatives, damageRecipients)
        harness.submitAttackers()
    }

    private fun damageRecipientForAttackTarget(
        harness: MatchFlowHarness,
        target: AcceptanceTargetSpec,
        context: String,
    ): DamageRecipient =
        when (target) {
            is PlayerTargetSpec ->
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(seat(target.side).value)
                    .build()

            is CardTargetSpec -> {
                require(target.side == AcceptanceSide.Opponent && target.zone == AcceptanceZone.Battlefield) {
                    "$context attack target must be an opponent battlefield planeswalker, got ${target.label}"
                }
                val card =
                    cardsInZone(harness, target.side, target.zone)
                        .firstOrNull { it.name.equals(target.card, ignoreCase = true) }
                        ?: error("$context could not find attack target ${target.label}")
                require(card.isPlaneswalker) { "$context attack target ${target.card} is not a planeswalker" }
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.PlanesWalker)
                    .setPlaneswalkerInstanceId(harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
                    .build()
            }
        }

    private fun turnFaceUp(
        harness: MatchFlowHarness,
        step: TurnFaceUpStep,
        context: String,
    ) {
        val card =
            player(AcceptanceSide.Ours, harness)
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(step.card, ignoreCase = true) || it.isFaceDown }
                ?: error("$context could not find ${step.card} or a face-down card on battlefield")
        submitAction(
            harness,
            Action
                .newBuilder()
                .setActionType(ActionType.SpecialTurnFaceUp_add3)
                .setInstanceId(harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
                .build(),
        )
    }

    private fun resolveStack(
        harness: MatchFlowHarness,
        context: String,
    ) {
        repeat(12) { index ->
            if (index > 0 && harness.game().stackZone.size() == 0) return
            if (harness.isGameOver()) return
            harness.passPriority()
            if (harness.game().stackZone.size() == 0) return
        }
        error(
            "$context did not resolve stack; stack size=${harness.game().stackZone.size()}",
        )
    }

    private fun passUntil(
        harness: MatchFlowHarness,
        step: PassUntilStep,
        context: String,
    ) {
        val reached = harness.passUntil(maxPasses = step.maxPasses) { passUntilConditionReached(harness, step) }
        require(reached) {
            "$context did not reach: ${step.conditions.joinToString { it.label }}; " +
                "latest prompt=${latestPromptNameWithId(harness) ?: "none"}; " +
                "prompts=${harness.allMessages.filter { it.isPromptMessage() }.map { it.promptName() + "#" + it.prompt.promptId }}; " +
                "actions=${harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(harness, it) }}"
        }
    }

    private fun passUntilConditionReached(
        harness: MatchFlowHarness,
        step: PassUntilStep,
    ): Boolean =
        try {
            GameLoopPoller.awaitCondition(timeoutMs = 200, pollIntervalMs = 20) {
                harness.drainSink()
                step.conditions.all { matchesCondition(harness, it) }
            }
            true
        } catch (_: AssertionError) {
            false
        }

    private fun assertConditions(
        harness: MatchFlowHarness,
        conditions: List<AcceptanceCondition>,
        context: String,
    ) {
        val missing = conditions.filterNot { matchesCondition(harness, it) }
        require(missing.isEmpty()) {
            "$context failed: ${missing.joinToString("; ") { explainConditionFailure(harness, it) }}"
        }
    }

    private fun explainConditionFailure(
        harness: MatchFlowHarness,
        condition: AcceptanceCondition,
    ): String =
        when (condition) {
            is ActionAvailableCondition ->
                "${condition.label}; actual actions=${
                    harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(harness, it) }
                }"

            is ZoneContainsCondition ->
                "${condition.label}; actual ${condition.side.yamlName} ${condition.zone.yamlName}=${
                    zoneCardNames(harness, condition.side, condition.zone)
                }"

            is ZoneNotContainsCondition ->
                "${condition.label}; actual ${condition.side.yamlName} ${condition.zone.yamlName}=${
                    zoneCardNames(harness, condition.side, condition.zone)
                }"

            is ZoneCountAtLeastCondition ->
                "${condition.label}; actual count=${zoneCardNames(harness, condition.side, condition.zone).size} cards=${
                    zoneCardNames(harness, condition.side, condition.zone)
                }"

            is LifeTotalCondition ->
                "${condition.label}; actual ${condition.side.yamlName} life=${player(condition.side, harness).life}"

            is BattlefieldStatsAtLeastCondition -> {
                val card =
                    player(condition.side, harness)
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .firstOrNull { it.name.equals(condition.card, ignoreCase = true) }
                if (card == null) {
                    "${condition.label}; actual battlefield=${
                        zoneCardNames(harness, condition.side, AcceptanceZone.Battlefield)
                    }"
                } else {
                    "${condition.label}; actual stats=${card.netPower}/${card.netToughness}"
                }
            }

            is BattlefieldStatsCondition -> {
                val card =
                    player(condition.side, harness)
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .firstOrNull { it.name.equals(condition.card, ignoreCase = true) }
                if (card == null) {
                    "${condition.label}; actual battlefield=${
                        zoneCardNames(harness, condition.side, AcceptanceZone.Battlefield)
                    }"
                } else {
                    "${condition.label}; actual stats=${card.netPower}/${card.netToughness}"
                }
            }

            is PhaseCondition -> "${condition.label}; actual phase=${harness.phase()}"
            is PromptCondition ->
                "${condition.label}; actual latest prompt=${latestPromptNameWithId(harness) ?: "none"}"
            is AnnotationSeenCondition -> "${condition.label}; actual annotations=${annotationTypes(harness).distinct()}"
            StackEmptyCondition -> "${condition.label}; actual stack size=${harness.game().stackZone.size()}"
        }

    private fun matchesCondition(
        harness: MatchFlowHarness,
        condition: AcceptanceCondition,
    ): Boolean =
        when (condition) {
            is ActionAvailableCondition -> actionAvailable(harness, condition)
            is ZoneContainsCondition -> zoneContains(harness, condition)
            is ZoneNotContainsCondition -> zoneNotContains(harness, condition)
            is ZoneCountAtLeastCondition -> zoneCountAtLeast(harness, condition)
            is LifeTotalCondition -> player(condition.side, harness).life == condition.value
            is BattlefieldStatsCondition -> battlefieldStats(harness, condition)
            is BattlefieldStatsAtLeastCondition -> battlefieldStatsAtLeast(harness, condition)
            is PhaseCondition -> phaseMatches(harness.phase(), condition.phase)
            is PromptCondition -> promptSeen(harness, condition.prompt, condition.promptId)
            is AnnotationSeenCondition -> annotationSeen(harness, condition.type)
            StackEmptyCondition -> harness.game().stackZone.size() == 0
        }

    private fun annotationSeen(
        harness: MatchFlowHarness,
        type: String,
    ): Boolean {
        val expected = AnnotationType.valueOf(type)
        return harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .any { expected in it.typeList }
    }

    private fun annotationTypes(harness: MatchFlowHarness): List<String> =
        harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .flatMap { it.typeList }
            .map { it.name }

    private fun actionAvailable(
        harness: MatchFlowHarness,
        condition: ActionAvailableCondition,
    ): Boolean {
        val expectedType =
            when (condition.type) {
                AcceptanceActionType.PlayLand -> ActionType.Play_add3
                AcceptanceActionType.Cast -> ActionType.Cast
                AcceptanceActionType.Activate -> ActionType.Activate_add3
            }
        return harness.accumulator.actions?.actionsList.orEmpty().any { action ->
            action.actionType == expectedType &&
                actionCardName(harness, action).equals(condition.card, ignoreCase = true) &&
                (condition.altCost == null || actionMatchesAltCost(harness, action, condition.altCost))
        }
    }

    private fun actionCardName(
        harness: MatchFlowHarness,
        action: Action,
    ): String? {
        val grpName = harness.bridge.cardRepository.findNameByGrpId(action.grpId)
        if (grpName != null) return grpName
        val forgeCardId = harness.bridge.getForgeCardId(InstanceId(action.instanceId))
        if (forgeCardId != null) return harness.game().findById(forgeCardId.value)?.name
        return null
    }

    private fun actionMatchesZone(
        harness: MatchFlowHarness,
        action: Action,
        expectedZone: AcceptanceZone,
    ): Boolean {
        val forgeCardId = harness.bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return expectedZone == AcceptanceZone.Hand
        val card = harness.game().findById(forgeCardId.value) ?: return false
        return card.zone.zoneType == expectedZone.toForgeZone()
    }

    private fun actionMatchesAltCost(
        harness: MatchFlowHarness,
        action: Action,
        altCost: AcceptanceAltCost?,
    ): Boolean {
        if (altCost == null) return true
        val cardGrpId =
            action.grpId.takeIf { it != 0 }
                ?: harness.bridge
                    .getForgeCardId(InstanceId(action.instanceId))
                    ?.let { harness.game().findById(it.value) }
                    ?.let { harness.bridge.resolveGrpId(it, action.instanceId) }
                ?: return false
        val keywordId =
            when (altCost) {
                AcceptanceAltCost.Cleave -> KeywordAbilityIds.CLEAVE
                AcceptanceAltCost.Disguise -> KeywordAbilityIds.DISGUISE
                AcceptanceAltCost.Overload -> KeywordAbilityIds.OVERLOAD
                AcceptanceAltCost.Escape -> KeywordAbilityIds.ESCAPE
                AcceptanceAltCost.Foretell -> KeywordAbilityIds.FORETELL
                AcceptanceAltCost.Impending -> KeywordAbilityIds.IMPENDING
                AcceptanceAltCost.JumpStart -> KeywordAbilityIds.JUMP_START
                AcceptanceAltCost.Plot -> KeywordAbilityIds.PLOT
                AcceptanceAltCost.Warp -> KeywordAbilityIds.WARP
                AcceptanceAltCost.Enlist -> KeywordAbilityIds.ENLIST
                AcceptanceAltCost.Airbend -> KeywordAbilityIds.AIRBEND
            }
        val abilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, keywordId)
        return action.alternativeGrpId == keywordId ||
            action.abilityGrpId == keywordId ||
            (abilityGrpId != null && (action.alternativeGrpId == abilityGrpId || action.abilityGrpId == abilityGrpId))
    }

    private fun keywordAbilityId(altCost: AcceptanceAltCost): Int =
        when (altCost) {
            AcceptanceAltCost.Cleave -> KeywordAbilityIds.CLEAVE
            AcceptanceAltCost.Disguise -> KeywordAbilityIds.DISGUISE
            AcceptanceAltCost.Overload -> KeywordAbilityIds.OVERLOAD
            AcceptanceAltCost.Escape -> KeywordAbilityIds.ESCAPE
            AcceptanceAltCost.Foretell -> KeywordAbilityIds.FORETELL
            AcceptanceAltCost.Impending -> KeywordAbilityIds.IMPENDING
            AcceptanceAltCost.JumpStart -> KeywordAbilityIds.JUMP_START
            AcceptanceAltCost.Plot -> KeywordAbilityIds.PLOT
            AcceptanceAltCost.Warp -> KeywordAbilityIds.WARP
            AcceptanceAltCost.Enlist -> KeywordAbilityIds.ENLIST
            AcceptanceAltCost.Airbend -> KeywordAbilityIds.AIRBEND
        }

    private fun zoneContains(
        harness: MatchFlowHarness,
        condition: ZoneContainsCondition,
    ): Boolean =
        cardsInZone(harness, condition.side, condition.zone)
            .any { it.name.equals(condition.card, ignoreCase = true) }

    private fun zoneNotContains(
        harness: MatchFlowHarness,
        condition: ZoneNotContainsCondition,
    ): Boolean =
        cardsInZone(harness, condition.side, condition.zone)
            .none { it.name.equals(condition.card, ignoreCase = true) }

    private fun zoneCountAtLeast(
        harness: MatchFlowHarness,
        condition: ZoneCountAtLeastCondition,
    ): Boolean = cardsInZone(harness, condition.side, condition.zone).size >= condition.count

    private fun battlefieldStatsAtLeast(
        harness: MatchFlowHarness,
        condition: BattlefieldStatsAtLeastCondition,
    ): Boolean {
        val card =
            player(condition.side, harness)
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(condition.card, ignoreCase = true) }
                ?: return false
        return card.netPower >= condition.power && card.netToughness >= condition.toughness
    }

    private fun battlefieldStats(
        harness: MatchFlowHarness,
        condition: BattlefieldStatsCondition,
    ): Boolean {
        val card =
            player(condition.side, harness)
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(condition.card, ignoreCase = true) }
                ?: return false
        return card.netPower == condition.power && card.netToughness == condition.toughness
    }

    private fun promptSeen(
        harness: MatchFlowHarness,
        prompt: String,
        promptId: Int?,
    ): Boolean = harness.allMessages.any { it.matchesPrompt(prompt, promptId) }

    private fun latestPromptMatches(
        harness: MatchFlowHarness,
        prompt: String,
        promptId: Int? = null,
    ): Boolean = latestPromptMessage(harness)?.matchesPrompt(prompt, promptId) == true

    private fun latestPromptMessage(harness: MatchFlowHarness): GREToClientMessage? =
        harness.allMessages
            .asReversed()
            .firstOrNull { it.isPromptMessage() }

    private fun latestPromptNameWithId(harness: MatchFlowHarness): String? =
        latestPromptMessage(harness)?.let { msg -> "${msg.promptName()}#${msg.prompt.promptId}" }

    private fun phaseMatches(
        actual: String?,
        expected: String,
    ): Boolean = actual == expected.toForgePhaseName()

    private fun player(
        side: AcceptanceSide,
        harness: MatchFlowHarness,
    ): Player =
        when (side) {
            AcceptanceSide.Ours -> harness.bridge.getPlayer(OUR_SEAT)
            AcceptanceSide.Opponent -> harness.bridge.getPlayer(OPPONENT_SEAT)
        } ?: error("missing ${side.yamlName} player")

    private fun seat(side: AcceptanceSide): SeatId =
        when (side) {
            AcceptanceSide.Ours -> OUR_SEAT
            AcceptanceSide.Opponent -> OPPONENT_SEAT
        }

    private fun cardsInZone(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<Card> =
        when (zone) {
            AcceptanceZone.Stack ->
                harness
                    .game()
                    .stack
                    .map { it.sourceCard }
                    .filter { harness.bridge.seatOf(it.owner) == seat(side) }

            AcceptanceZone.Battlefield,
            AcceptanceZone.Hand,
            AcceptanceZone.Graveyard,
            AcceptanceZone.Exile,
            AcceptanceZone.Library,
            AcceptanceZone.Sideboard,
            -> player(side, harness).getZone(zone.toForgeZone()).cards.toList()
        }

    private fun requireAction(
        context: String,
        action: () -> Boolean,
    ) {
        require(action()) { "$context action failed" }
    }

    private fun submitAction(
        harness: MatchFlowHarness,
        action: Action,
    ) {
        harness.session.onPerformAction(
            harness.submitWithGsId(
                wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.PerformActionResp_097b)
                    .setPerformActionResp(PerformActionResp.newBuilder().addActions(action))
                    .build(),
            ),
        )
        harness.drainSink()
    }

    private fun readPuzzleText(puzzle: String): String = Files.readString(resolvePuzzlePath(puzzle))

    private fun resolvePuzzlePath(puzzle: String): Path {
        val fileName = if (puzzle.endsWith(".pzl")) puzzle else "$puzzle.pzl"
        val candidates =
            listOf(
                Paths.get("puzzles/$fileName"),
                Paths.get("../puzzles/$fileName"),
                Paths.get("../../puzzles/$fileName"),
            )
        return candidates.firstOrNull { Files.exists(it) }
            ?: error("puzzle not found: $fileName in $candidates (cwd=${Paths.get("").toAbsolutePath()})")
    }

    private fun resolveTargetInstanceId(
        harness: MatchFlowHarness,
        target: AcceptanceTargetSpec,
    ): Int =
        when (target) {
            is PlayerTargetSpec ->
                when (target.side) {
                    AcceptanceSide.Ours -> OUR_SEAT.value
                    AcceptanceSide.Opponent -> OPPONENT_SEAT.value
                }

            is CardTargetSpec -> resolveCardInZone(harness, target.side, target.zone, target.card)
        }

    private fun resolveBattlefieldCard(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        card: String,
    ): Int = resolveCardInZone(harness, side, AcceptanceZone.Battlefield, card)

    private fun resolvePromptCardOrder(
        harness: MatchFlowHarness,
        candidateIds: List<Int>,
        cards: List<String>,
        context: String,
    ): List<Int> {
        require(candidateIds.size == cards.size) {
            "$context ordered ${cards.size} cards but OrderReq has ${candidateIds.size} candidates ${promptCardNames(
                harness,
                candidateIds,
            )}"
        }
        val remaining = candidateIds.toMutableList()
        return cards.map { card ->
            val id =
                remaining.firstOrNull { iid -> cardNameByInstanceId(harness, iid).equals(card, ignoreCase = true) }
                    ?: error("$context could not find $card in OrderReq candidates ${promptCardNames(harness, candidateIds)}")
            remaining.remove(id)
            id
        }
    }

    private fun resolveCardInZone(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        zone: AcceptanceZone,
        cardName: String,
    ): Int {
        val card =
            cardsInZone(harness, side, zone)
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) }
                ?: error("could not find $cardName in ${side.yamlName} ${zone.yamlName}")
        return harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
    }

    private fun promptCardNames(
        harness: MatchFlowHarness,
        ids: List<Int>,
    ): List<String> = ids.map { iid -> cardNameByInstanceId(harness, iid) ?: "iid=$iid" }

    private fun cardNameByInstanceId(
        harness: MatchFlowHarness,
        iid: Int,
    ): String? {
        val cardId = harness.bridge.getForgeCardId(InstanceId(iid)) ?: return null
        return harness.game().findById(cardId.value)?.name
    }

    private fun zoneCardNames(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<String> = cardsInZone(harness, side, zone).map { it.name }

    private fun actionSummary(
        harness: MatchFlowHarness,
        action: Action,
    ): String = "${action.actionType.name}:${actionCardName(harness, action) ?: "?"}"

    private companion object {
        val OUR_SEAT = SeatId(1)
        val OPPONENT_SEAT = SeatId(2)
    }
}
