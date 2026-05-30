package leyline.acceptance

import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
            is ModalChoiceStep -> modalChoice(harness, step, context)
            is StaticChoiceStep -> staticChoice(harness, step, context)
            is OptionalActionStep -> harness.respondToOptionalAction(step.accept)
            is TargetStep -> target(harness, step.target, context)
            is SelectCostStep -> selectCost(harness, step)
            is SelectCardStep -> selectCard(harness, step, context)
            is BlockStep -> block(harness, step, context)
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
    ) {
        val prompt = latestPromptMessage(harness)
        require(prompt?.hasSelectNReq() == true) {
            "$context expected latest prompt SelectNReq"
        }
        val selectedId = resolveCardInZone(harness, step.side, step.zone, step.card)
        require(selectedId in prompt.selectNReq.idsList) {
            "$context selected ${step.card} iid=$selectedId is not in SelectNReq candidates ${prompt.selectNReq.idsList}"
        }
        if (step.zone == AcceptanceZone.Sideboard) {
            require(prompt.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || prompt.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
                "$context sideboard selection expected Learn prompt, got promptId=${prompt.prompt.promptId}"
            }
        }
        harness.respondToSelectN(listOf(selectedId))
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
        val reached =
            harness.passUntil(maxPasses = step.maxPasses) {
                step.conditions.all { matchesCondition(harness, it) }
            }
        require(reached) {
            "$context did not reach: ${step.conditions.joinToString { it.label }}"
        }
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

            is PhaseCondition -> "${condition.label}; actual phase=${harness.phase()}"
            is PromptCondition ->
                "${condition.label}; actual latest prompt=${latestPromptName(harness) ?: "none"}"
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
            is BattlefieldStatsAtLeastCondition -> battlefieldStatsAtLeast(harness, condition)
            is PhaseCondition -> phaseMatches(harness.phase(), condition.phase)
            is PromptCondition -> promptSeen(harness, condition.prompt)
            StackEmptyCondition -> harness.game().stackZone.size() == 0
        }

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
            action.actionType == expectedType && actionCardName(harness, action).equals(condition.card, ignoreCase = true)
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
                AcceptanceAltCost.Overload -> KeywordAbilityIds.OVERLOAD
                AcceptanceAltCost.Escape -> KeywordAbilityIds.ESCAPE
                AcceptanceAltCost.JumpStart -> KeywordAbilityIds.JUMP_START
            }
        val abilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, keywordId)
        return abilityGrpId != null && (action.alternativeGrpId == abilityGrpId || action.abilityGrpId == abilityGrpId)
    }

    private fun zoneContains(
        harness: MatchFlowHarness,
        condition: ZoneContainsCondition,
    ): Boolean =
        player(condition.side, harness)
            .getZone(condition.zone.toForgeZone())
            .cards
            .any { it.name.equals(condition.card, ignoreCase = true) }

    private fun zoneNotContains(
        harness: MatchFlowHarness,
        condition: ZoneNotContainsCondition,
    ): Boolean =
        player(condition.side, harness)
            .getZone(condition.zone.toForgeZone())
            .cards
            .none { it.name.equals(condition.card, ignoreCase = true) }

    private fun zoneCountAtLeast(
        harness: MatchFlowHarness,
        condition: ZoneCountAtLeastCondition,
    ): Boolean = player(condition.side, harness).getZone(condition.zone.toForgeZone()).size() >= condition.count

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

    private fun promptSeen(
        harness: MatchFlowHarness,
        prompt: String,
    ): Boolean = latestPromptMatches(harness, prompt)

    private fun latestPromptMatches(
        harness: MatchFlowHarness,
        prompt: String,
    ): Boolean = latestPromptMessage(harness)?.matchesPrompt(prompt) == true

    private fun latestPromptMessage(harness: MatchFlowHarness): GREToClientMessage? =
        harness.allMessages
            .asReversed()
            .firstOrNull { it.isPromptMessage() }

    private fun latestPromptName(harness: MatchFlowHarness): String? = latestPromptMessage(harness)?.promptName()

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

    private fun resolveCardInZone(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        zone: AcceptanceZone,
        cardName: String,
    ): Int {
        val card =
            player(side, harness)
                .getZone(zone.toForgeZone())
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) }
                ?: error("could not find $cardName in ${side.yamlName} ${zone.yamlName}")
        return harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
    }

    private fun zoneCardNames(
        harness: MatchFlowHarness,
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<String> = player(side, harness).getZone(zone.toForgeZone()).cards.map { it.name }

    private fun actionSummary(
        harness: MatchFlowHarness,
        action: Action,
    ): String = "${action.actionType.name}:${actionCardName(harness, action) ?: "?"}"

    private companion object {
        val OUR_SEAT = SeatId(1)
        val OPPONENT_SEAT = SeatId(2)
    }
}
