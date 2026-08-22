package leyline.acceptance

import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.tooling.headless.ActionKind
import leyline.tooling.headless.ActionSelection
import leyline.tooling.headless.CombatAction
import leyline.tooling.headless.ControlAction
import leyline.tooling.headless.DamageRecipientChoice
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.HeadlessMatchFactory
import leyline.tooling.headless.ManaColorChoice
import leyline.tooling.headless.MatchIntent
import leyline.tooling.headless.MatchSpec
import leyline.tooling.headless.PlayAction
import leyline.tooling.headless.PromptResponse
import leyline.tooling.headless.actionMatchesAlternative
import leyline.tooling.headless.cardNameByGrpId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import java.nio.file.Files

class MatchdoorAcceptanceExecutor(
    private val seed: Long = 42L,
) {
    fun runScenario(
        scenario: AcceptanceScenario,
        onComplete: (List<GREToClientMessage>) -> Unit = {},
    ): Int {
        require(scenario.steps.isNotEmpty()) { "scenario ${scenario.id} has no executable steps" }
        val match =
            HeadlessMatchFactory.create(
                MatchSpec(
                    seed = seed,
                    puzzleText = readPuzzleText(scenario.puzzle),
                    responseMode = leyline.tooling.headless.HeadlessResponseMode.AutoForTests,
                ),
            )
        val harness = match
        try {
            harness.start()
            val run = ScenarioRun(harness, scenario.id)
            var remainingOptionalActions = scenario.steps.count { it is OptionalActionStep }
            if (remainingOptionalActions > 0) harness.holdNextOptionalAction()
            scenario.steps.forEachIndexed { index, step ->
                run.executeStep(index, step)
                if (step is OptionalActionStep && --remainingOptionalActions > 0) {
                    harness.holdNextOptionalAction()
                }
                if (!harness.observe().gameOver) {
                    require(
                        harness
                            .observe()
                            .client
                            .actionInstanceIdsMissingFromObjects()
                            .isEmpty(),
                    ) {
                        "${scenario.id} step ${index + 1} ${step.label}: action references missing objects"
                    }
                }
            }
            onComplete(harness.allMessages.toList())
            return scenario.steps.size
        } finally {
            match.close()
        }
    }
}

private fun readPuzzleText(puzzle: String): String {
    val fileName = if (puzzle.endsWith(".pzl")) puzzle else "$puzzle.pzl"
    return Files.readString(AcceptancePaths.resolve("puzzles/$fileName", notFoundMessage = "puzzle not found: $fileName"))
}

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
private fun semanticColor(color: ManaColor): ManaColorChoice =
    when (color) {
        ManaColor.White_afc9 -> ManaColorChoice.White
        ManaColor.Blue_afc9 -> ManaColorChoice.Blue
        ManaColor.Black_afc9 -> ManaColorChoice.Black
        ManaColor.Red_afc9 -> ManaColorChoice.Red
        ManaColor.Green_afc9 -> ManaColorChoice.Green
        ManaColor.Colorless_afc9 -> ManaColorChoice.Colorless
        ManaColor.Phyrexian_afc9 -> ManaColorChoice.Phyrexian
        ManaColor.Generic -> ManaColorChoice.Generic
        ManaColor.X -> ManaColorChoice.X
        ManaColor.Y -> ManaColorChoice.Y
        ManaColor.TwoGeneric -> ManaColorChoice.TwoGeneric
        ManaColor.AnyColor -> ManaColorChoice.AnyColor
        ManaColor.Snow_afc9 -> ManaColorChoice.Snow
        else -> error("Unsupported semantic mana color: $color")
    }

private fun semanticRecipient(recipient: DamageRecipient): DamageRecipientChoice =
    when (recipient.type) {
        DamageRecType.Player_a0e5 -> DamageRecipientChoice.Opponent
        DamageRecType.PlanesWalker -> DamageRecipientChoice.Planeswalker(recipient.planeswalkerInstanceId)
        else -> error("Unsupported semantic damage recipient: ${recipient.type}")
    }

private val OUR_SEAT = SeatId(1)
private val OPPONENT_SEAT = SeatId(2)

private val HeadlessMatch.actions get() = observe().client.actions
private val HeadlessMatch.allMessages get() = observe().messages
private val HeadlessMatch.isGameOver get() = observe().gameOver
private val HeadlessMatch.phase get() = observe().phase

private fun HeadlessMatch.promptHistory() = observe().promptHistory

private fun HeadlessMatch.hasPendingCostSelection() = observe().pendingCostSelection

private fun HeadlessMatch.pendingSelectN() = observe().pendingInteraction as? leyline.tooling.headless.PendingInteraction.SelectN

private fun HeadlessMatch.hasPendingSelectNPrompt() = pendingSelectN() != null

private fun HeadlessMatch.hasOptionalInteraction() = observe().blockingInteraction == "Optional"

private fun HeadlessMatch.pendingActionKind() =
    observe().pendingActionKind?.let {
        runCatching { PendingActionKind.valueOf(it) }.getOrNull()
    }

private fun HeadlessMatch.stackEmpty() = observe().stackSize == 0

private fun HeadlessMatch.stackObjectsEmpty() = observe().stackObjectsSize == 0

private fun HeadlessMatch.stackSize() = observe().stackSize ?: 0

private fun HeadlessMatch.playerLife(side: AcceptanceSide) =
    observe().client.players[if (side == AcceptanceSide.Ours) 1 else 2]?.lifeTotal ?: 0

private fun HeadlessMatch.cards(
    side: AcceptanceSide,
    zone: AcceptanceZone,
) = observe().cards.filter { it.seat == (if (side == AcceptanceSide.Ours) 1 else 2) && it.zone == zone.toForgeZone().name }

private fun HeadlessMatch.holdNextOptionalAction() = submit(MatchIntent.Control(ControlAction.HoldNextOptionalAction))

private fun HeadlessMatch.passPriority() = submit(MatchIntent.Control(ControlAction.PassPriority))

private fun HeadlessMatch.playLand(name: String?) = submit(MatchIntent.Play(PlayAction.Land(name))).accepted

private fun HeadlessMatch.respondToSelectN(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.SelectN(ids)))

private fun HeadlessMatch.respondToSearch(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.Search(ids)))

private fun HeadlessMatch.respondToOrder(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.Order(ids)))

private fun HeadlessMatch.respondToEffectCost(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.EffectCost(ids)))

private fun HeadlessMatch.respondModalChoice(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.ModalChoice(ids)))

private fun HeadlessMatch.respondToOptionalCost(ctoId: Int) = submit(MatchIntent.Prompt(PromptResponse.OptionalCost(ctoId)))

private fun HeadlessMatch.respondToManaTypeChoices(choices: List<Pair<Int, ManaColor>>) =
    submit(MatchIntent.Prompt(PromptResponse.ManaTypeChoices(choices.map { it.first to semanticColor(it.second) })))

private fun HeadlessMatch.respondToOptionalAction(accept: Boolean) = submit(MatchIntent.Prompt(PromptResponse.OptionalAction(accept)))

private fun HeadlessMatch.selectTargets(ids: List<Int>) = submit(MatchIntent.Prompt(PromptResponse.Targets(ids)))

private fun HeadlessMatch.declareBlockers(assignments: Map<Int, Int>) = submit(MatchIntent.Combat(CombatAction.Blockers(assignments)))

private fun HeadlessMatch.declareAllAttackers() = submit(MatchIntent.Combat(CombatAction.AllAttackers))

private fun HeadlessMatch.submitAttackers() = submit(MatchIntent.Combat(CombatAction.SubmitAttackers))

private fun HeadlessMatch.toggleAttackers(
    ids: List<Int>,
    alternatives: Map<Int, Int>,
    recipients: Map<Int, DamageRecipient>,
) = submit(MatchIntent.Combat(CombatAction.ToggleAttackers(ids, alternatives, recipients.mapValues { it.value.let(::semanticRecipient) })))

private fun HeadlessMatch.drainSink() = observe()

private fun HeadlessMatch.passUntil(
    maxPasses: Int,
    stopWhen: HeadlessMatch.() -> Boolean,
): Boolean {
    repeat(maxPasses) {
        if (isGameOver || stopWhen()) return true
        advanceDefaultStop()
    }
    return isGameOver || stopWhen()
}

private fun HeadlessMatch.advanceDefaultStop() {
    when (observe().phase) {
        "COMBAT_DECLARE_ATTACKERS" -> submit(MatchIntent.Combat(CombatAction.NoAttackers))
        "COMBAT_DECLARE_BLOCKERS" -> submit(MatchIntent.Combat(CombatAction.NoBlockers))
        else -> passPriority()
    }
}

internal fun stackResolutionNeedsAdvance(
    passCount: Int,
    stackEmpty: Boolean,
    pendingKind: PendingActionKind?,
): Boolean =
    when {
        !stackEmpty -> true
        pendingKind == PendingActionKind.PRIORITY -> false
        passCount == 0 -> true
        pendingKind == PendingActionKind.SYNC_ONLY -> true
        else -> false
    }

private fun seat(side: AcceptanceSide): SeatId =
    when (side) {
        AcceptanceSide.Ours -> OUR_SEAT
        AcceptanceSide.Opponent -> OPPONENT_SEAT
    }

private fun phaseMatches(
    actual: String?,
    expected: String,
): Boolean = actual == expected.toForgePhaseName()

private fun AcceptanceManaTypeChoice.toManaColor(): ManaColor =
    when (this) {
        AcceptanceManaTypeChoice.TwoGeneric -> ManaColor.TwoGeneric
        AcceptanceManaTypeChoice.White -> ManaColor.White_afc9
        AcceptanceManaTypeChoice.Blue -> ManaColor.Blue_afc9
        AcceptanceManaTypeChoice.Black -> ManaColor.Black_afc9
        AcceptanceManaTypeChoice.Red -> ManaColor.Red_afc9
        AcceptanceManaTypeChoice.Green -> ManaColor.Green_afc9
    }

/** A condition's match outcome alongside the "actual" text used in failure diagnostics. */
private data class ConditionResult(
    val matched: Boolean,
    val actual: String,
)

/**
 * Drives one scenario's steps against a single semantic headless match. Holds the harness plus the
 * current step's error-context string so step/condition/card-resolution helpers don't have to
 * thread both through every call.
 */
@Suppress("LargeClass") // Grows one small adapter per backend-neutral DSL verb.
private class ScenarioRun(
    val harness: HeadlessMatch,
    private val scenarioId: String,
) {
    lateinit var context: String
        private set

    @Suppress("CyclomaticComplexMethod")
    fun executeStep(
        index: Int,
        step: AcceptanceStep,
    ) {
        context = "$scenarioId step ${index + 1} (${step.label})"
        when (step) {
            is WaitStep -> assertConditions(step.conditions)
            is ExpectStep -> assertConditions(step.conditions)
            is PassUntilStep -> passUntil(step)
            is ActivateStep -> activate(step)
            is ChooseStep -> choose(step)
            is ManaTypeChoicesStep -> manaTypeChoices(step)
            is ModalChoiceStep -> modalChoice(step)
            is StaticChoiceStep -> staticChoice(step)
            is OptionalActionStep -> respondToOptionalAction(step)
            is TargetStep -> target(step.target)
            is SelectCostStep -> selectCost(step)
            is SelectCardStep -> selectCard(step)
            is SelectCardsStep -> selectCards(step)
            is SearchCardsStep -> searchCards(step)
            is OrderCardsStep -> orderCards(step)
            is BlockStep -> block(step)
            is AttackStep -> attack(step)
            is TurnFaceUpStep -> turnFaceUp(step)
            is PlayLandStep -> playLand(step)
            is PlayMdfcStep -> submitNamedAction(ActionType.PlayMdfc, step.card)
            is CastStep -> cast(step)
            is CastAdventureStep -> submitNamedAction(ActionType.CastAdventure, step.card)
            is CastOmenStep -> submitNamedAction(ActionType.CastOmen, step.card)
            is CastMdfcStep -> submitNamedAction(ActionType.CastMdfc, step.card)
            ResolveStackStep -> resolveStack()
            AttackAllStep -> {
                harness.declareAllAttackers()
                harness.submitAttackers()
            }
        }
    }

    private fun playLand(step: PlayLandStep) {
        if (harness.hasPendingSelectNPrompt()) {
            val pending = requireNotNull(harness.pendingSelectN())
            val instanceId = resolveCardInZone(AcceptanceSide.Ours, AcceptanceZone.Hand, step.card)
            require(instanceId in pending.instanceIds) {
                "$context land $step.card iid=$instanceId is not in SelectN candidates ${pending.instanceIds}"
            }
            harness.respondToSelectN(listOf(instanceId))
            return
        }
        requireAction { harness.playLand(step.card) }
    }

    private fun cast(step: CastStep) {
        val action =
            harness.actions?.actionsList.orEmpty().firstOrNull { action ->
                action.actionType == ActionType.Cast &&
                    actionCardName(action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(action, step.zone) &&
                    actionMatchesAltCost(action, step.altCost)
            } ?: error("$context no cast action for ${step.card} in ${step.zone.yamlName}")
        submitAction(action)
    }

    private fun submitNamedAction(
        actionType: ActionType,
        card: String,
    ) {
        val candidates =
            harness.actions
                ?.actionsList
                .orEmpty()
                .filter { it.actionType == actionType }
        val action =
            candidates.firstOrNull { actionCardName(it).equals(card, ignoreCase = true) }
                ?: candidates
                    .singleOrNull()
                    ?.takeIf {
                        actionType in listOf(ActionType.PlayMdfc, ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen)
                    }
                ?: error("$context no named ${actionType.name} action for $card")
        submitAction(action)
    }

    private fun selectCost(step: SelectCostStep) {
        val ids = step.cards.map { resolveCardInZone(step.side, step.zone, it) }
        val prompt = latestPromptMessage()
        val activePayCosts = harness.hasPendingCostSelection()
        when {
            prompt?.hasSelectNReq() == true ->
                selectCards(SelectCardsStep(step.side, step.zone, step.cards))
            prompt?.hasSelectTargetsReq() == true -> {
                val offered =
                    prompt.selectTargetsReq.targetsList
                        .flatMap { it.targetsList }
                        .map { it.targetInstanceId }
                ids.forEach { id ->
                    require(id in offered) {
                        "$context selected cost iid=$id is not in SelectTargets candidates $offered"
                    }
                }
                harness.selectTargets(ids)
            }
            prompt?.hasPayCostsReq() == true || activePayCosts ->
                harness.respondToEffectCost(ids)
            prompt == null -> acceptDefaultedCardCost(step)
            else -> error("$context expected active PayCosts or SelectN/SelectTargets prompt")
        }
    }

    private fun acceptDefaultedCardCost(step: SelectCostStep) {
        val record = harness.promptHistory().lastOrNull()
        require(record != null) {
            "$context expected typed cost fallback, history=$record " +
                "messages=${harness.allMessages.takeLast(8).map { it.promptName() }}"
        }
        when (val route = record.route) {
            is ResolvedPromptRoute.CompatibilityCostSelection -> Unit
            is ResolvedPromptRoute.PayCosts ->
                require(route.descriptor.tapPayment != null) {
                    "$context grounded tap cost lost its TapPayment descriptor: $record"
                }
            else -> error("$context expected CompatibilityCostSelection or grounded TapPayment route: $record")
        }
        require(
            record.outcome in
                setOf(
                    PromptCallStatus.DEFAULTED_POLICY,
                    PromptCallStatus.NON_GAME_THREAD,
                    PromptCallStatus.NON_INTERACTIVE_SCOPE,
                ),
        ) {
            "$context typed cost was not defaulted safely: $record"
        }
        val expectedIndices =
            step.cards.map { card ->
                record.options.indexOfFirst { it.equals(card, ignoreCase = true) }.also {
                    require(it >= 0) { "$context defaulted cost card $card is not in options ${record.options}" }
                }
            }
        require(record.result == expectedIndices) {
            "$context defaulted cost result ${record.result} does not match $expectedIndices"
        }
    }

    private fun selectCard(step: SelectCardStep) = selectCards(SelectCardsStep(step.side, step.zone, listOf(step.card)))

    private fun selectCards(step: SelectCardsStep) {
        val prompt = latestPromptMessage()
        val pending = requireNotNull(harness.pendingSelectN()) { "$context expected active SelectN interaction" }
        require(prompt?.hasSelectNReq() == true) { "$context expected latest prompt SelectNReq" }
        val selectedIds = step.cards.map { resolveCardInZone(step.side, step.zone, it) }
        selectedIds.zip(step.cards).forEach { (selectedId, card) ->
            require(selectedId in pending.instanceIds) {
                "$context selected $card iid=$selectedId is not in SelectN candidates ${pending.instanceIds}"
            }
        }
        if (step.zone == AcceptanceZone.Sideboard) {
            require(prompt.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || prompt.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
                "$context sideboard selection expected Learn prompt, got promptId=${prompt.prompt.promptId}"
            }
        }
        harness.respondToSelectN(selectedIds)
    }

    private fun searchCards(step: SearchCardsStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasSearchReq() == true) {
            "$context expected latest prompt SearchReq"
        }
        val selectedIds = step.cards.map { resolveCardInZone(step.side, AcceptanceZone.Library, it) }
        selectedIds.zip(step.cards).forEach { (selectedId, card) ->
            require(selectedId in prompt.searchReq.itemsSoughtList) {
                "$context selected $card iid=$selectedId is not in SearchReq candidates ${prompt.searchReq.itemsSoughtList}"
            }
        }
        harness.respondToSearch(selectedIds)
    }

    private fun orderCards(step: OrderCardsStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasOrderReq() == true) {
            "$context expected latest prompt OrderReq"
        }
        val orderedIds = resolvePromptCardOrder(prompt.orderReq.idsList, step.cards)
        harness.respondToOrder(orderedIds)
    }

    private fun activate(step: ActivateStep) {
        val matching =
            harness.actions?.actionsList.orEmpty().filter { action ->
                action.actionType == ActionType.Activate_add3 &&
                    actionCardName(action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(action, step.zone)
            }
        val action =
            matching.getOrNull(step.abilityIndex)
                ?: error(
                    "$context no activate action index ${step.abilityIndex} for ${step.card} in ${step.zone.yamlName}",
                )
        submitAction(action)
    }

    private fun choose(step: ChooseStep) {
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

    private fun manaTypeChoices(step: ManaTypeChoicesStep) {
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

    private fun modalChoice(step: ModalChoiceStep) {
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

    private fun staticChoice(step: StaticChoiceStep) {
        val prompt = latestPromptMessage()
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

    private fun target(target: AcceptanceTargetSpec) {
        require(latestPromptMatches("SelectTargetsReq")) {
            "$context expected latest prompt SelectTargetsReq; actual=${latestPromptNameWithId() ?: "none"}"
        }
        harness.selectTargets(listOf(resolveTargetInstanceId(target)))
    }

    private fun block(step: BlockStep) {
        require(latestPromptMatches("DeclareBlockersReq")) {
            "$context expected latest prompt DeclareBlockersReq"
        }
        val blockerId = resolveBattlefieldCard(AcceptanceSide.Ours, step.blocker)
        val attackerId = resolveBattlefieldCard(AcceptanceSide.Opponent, step.attacker)
        harness.declareBlockers(mapOf(blockerId to attackerId))
    }

    private fun attack(step: AttackStep) {
        require(latestPromptMatches("DeclareAttackersReq")) {
            "$context expected latest prompt DeclareAttackersReq"
        }
        val attackerIds = step.cards.map { resolveBattlefieldCard(AcceptanceSide.Ours, it) }
        val alternatives = step.altCost?.let { altCost -> attackerIds.associateWith { altCost.keywordAbilityId } }.orEmpty()
        val damageRecipients =
            step.target
                ?.let { target -> attackerIds.associateWith { damageRecipientForAttackTarget(target) } }
                .orEmpty()
        harness.toggleAttackers(attackerIds, alternatives, damageRecipients)
        harness.submitAttackers()
    }

    private fun damageRecipientForAttackTarget(target: AcceptanceTargetSpec): DamageRecipient =
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
                    harness
                        .cards(target.side, target.zone)
                        .firstOrNull { it.name.equals(target.card, ignoreCase = true) }
                        ?: error("$context could not find attack target ${target.label}")
                require(card.planeswalker) { "$context attack target ${target.card} is not a planeswalker" }
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.PlanesWalker)
                    .setPlaneswalkerInstanceId(card.instanceId)
                    .build()
            }
        }

    private fun turnFaceUp(step: TurnFaceUpStep) {
        val card =
            harness
                .cards(AcceptanceSide.Ours, AcceptanceZone.Battlefield)
                .firstOrNull { it.name.equals(step.card, ignoreCase = true) || it.faceDown }
                ?: error("$context could not find ${step.card} or a face-down card on battlefield")
        val instanceId = card.instanceId
        val action =
            harness.actions
                ?.actionsList
                .orEmpty()
                .firstOrNull {
                    it.actionType == ActionType.SpecialTurnFaceUp_add3 &&
                        it.instanceId == instanceId
                } ?: error("$context no turn-face-up action for ${step.card} iid=$instanceId")
        submitAction(action)
        require(!harness.cards(AcceptanceSide.Ours, AcceptanceZone.Battlefield).any { it.instanceId == instanceId && it.faceDown }) {
            "$context turn-face-up action did not resolve; " +
                "action=${actionSummary(action)}; " +
                "latest prompt=${latestPromptNameWithId() ?: "none"}; " +
                "actions=${harness.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}"
        }
    }

    private fun resolveStack() {
        repeat(12) { index ->
            val pendingKind = harness.pendingActionKind()
            if (!stackResolutionNeedsAdvance(index, harness.stackObjectsEmpty(), pendingKind)) return
            if (harness.isGameOver) return
            harness.passPriority()
            if (harness.isGameOver) return
            if (harness.hasOptionalInteraction()) {
                return
            }
            val nextSynchronization = harness.pendingActionKind() == PendingActionKind.SYNC_ONLY
            if (harness.stackObjectsEmpty() && !nextSynchronization) return
        }
        error(
            "$context did not resolve stack; stack size=${harness.stackSize()}",
        )
    }

    private fun passUntil(step: PassUntilStep) {
        harness.passUntil(maxPasses = step.maxPasses) { passUntilConditionReached(step) }
        val reached = runCatching { step.conditions.all { matchesCondition(it) } }.getOrDefault(false)
        require(reached) {
            "$context did not reach: ${step.conditions.joinToString { it.label }}; " +
                "latest prompt=${latestPromptNameWithId() ?: "none"}; " +
                "prompts=${harness.allMessages.filter { it.isPromptMessage() }.map { it.promptName() + "#" + it.prompt.promptId }}; " +
                "actions=${harness.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}"
        }
    }

    private fun respondToOptionalAction(step: OptionalActionStep) {
        val ready =
            harness.passUntil(maxPasses = 20) {
                harness.hasOptionalInteraction()
            }
        require(ready) { "$context optional action did not become pending" }
        harness.respondToOptionalAction(step.accept)
    }

    private fun passUntilConditionReached(step: PassUntilStep): Boolean =
        try {
            GameLoopPoller.awaitCondition(timeoutMs = 200, pollIntervalMs = 20) {
                harness.drainSink()
                step.conditions.all { matchesCondition(it) }
            }
            true
        } catch (_: Throwable) {
            false
        }

    private fun assertConditions(conditions: List<AcceptanceCondition>) {
        val failures = conditions.map { it to evaluateCondition(it) }.filterNot { (_, result) -> result.matched }
        require(failures.isEmpty()) {
            "$context failed: ${failures.joinToString("; ") { (condition, result) -> "${condition.label}; actual ${result.actual}" }}"
        }
    }

    private fun matchesCondition(condition: AcceptanceCondition): Boolean = evaluateCondition(condition).matched

    @Suppress("CyclomaticComplexMethod")
    private fun evaluateCondition(condition: AcceptanceCondition): ConditionResult =
        when (condition) {
            is ActionAvailableCondition ->
                ConditionResult(
                    actionAvailable(condition),
                    "actions=${harness.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}",
                )

            is ZoneContainsCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(
                    names.any { it.equals(condition.card, ignoreCase = true) },
                    "${condition.side.yamlName} ${condition.zone.yamlName}=$names",
                )
            }

            is ZoneNotContainsCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(
                    names.none { it.equals(condition.card, ignoreCase = true) },
                    "${condition.side.yamlName} ${condition.zone.yamlName}=$names",
                )
            }

            is ZoneCountAtLeastCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(names.size >= condition.count, "count=${names.size} cards=$names")
            }

            is LifeTotalCondition -> {
                val life = harness.playerLife(condition.side)
                ConditionResult(life == condition.value, "${condition.side.yamlName} life=$life")
            }

            is WinnerCondition -> {
                val winner = finalWinnerSeat()
                ConditionResult(winner == seat(condition.side).value, "winner=${winner ?: "none"}")
            }

            is LoserCondition -> {
                val loser = finalLoserSeat()
                ConditionResult(loser == seat(condition.side).value, "loser=${loser ?: "none"}")
            }

            is BattlefieldStatsAtLeastCondition ->
                battlefieldStatsResult(condition.side, condition.card) { card ->
                    (card.power ?: 0) >= condition.power && (card.toughness ?: 0) >= condition.toughness
                }

            is BattlefieldStatsCondition ->
                battlefieldStatsResult(condition.side, condition.card) { card ->
                    card.power == condition.power && card.toughness == condition.toughness
                }

            is PhaseCondition ->
                ConditionResult(
                    phaseMatches(harness.phase, condition.phase),
                    "phase=${harness.phase ?: "none"}",
                )

            is PromptCondition ->
                ConditionResult(promptSeen(condition.prompt, condition.promptId), "latest prompt=${latestPromptNameWithId() ?: "none"}")

            is AnnotationSeenCondition ->
                ConditionResult(annotationSeen(condition), "annotations=${annotationTypes().distinct()}")

            is AnnotationSeenInPhaseCondition ->
                ConditionResult(annotationSeenInPhase(condition.type, condition.phase), "annotations=${annotationTypesByPhase()}")

            StackEmptyCondition ->
                ConditionResult(
                    harness.stackEmpty(),
                    "stack size=${harness.stackSize()}",
                )
        }

    private fun battlefieldStatsResult(
        side: AcceptanceSide,
        cardName: String,
        matches: (leyline.tooling.headless.HeadlessCard) -> Boolean,
    ): ConditionResult {
        val card =
            harness
                .cards(side, AcceptanceZone.Battlefield)
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) }
        return if (card == null) {
            ConditionResult(false, "battlefield=${zoneCardNames(side, AcceptanceZone.Battlefield)}")
        } else {
            ConditionResult(matches(card), "stats=${card.power}/${card.toughness}")
        }
    }

    private fun annotationSeen(condition: AnnotationSeenCondition): Boolean {
        val expected = AnnotationType.valueOf(condition.type)
        return harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .any { annotation ->
                expected in annotation.typeList &&
                    condition.details.all { (key, value) ->
                        annotation.detailsList
                            .firstOrNull { it.key == key }
                            ?.let { detail ->
                                (
                                    detail.valueUint32List.map(Any::toString) +
                                        detail.valueInt32List.map(Any::toString) +
                                        detail.valueUint64List.map(Any::toString) +
                                        detail.valueInt64List.map(Any::toString) +
                                        detail.valueBoolList.map(Any::toString) +
                                        detail.valueStringList +
                                        detail.valueFloatList.map(Any::toString) +
                                        detail.valueDoubleList.map(Any::toString)
                                ).contains(value)
                            } == true
                    }
            }
    }

    private fun annotationSeenInPhase(
        type: String,
        phase: String,
    ): Boolean {
        val expected = AnnotationType.valueOf(type)
        val expectedPhase = phase.toForgePhaseName()
        return harness.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
            .any { message ->
                val gsm = message.gameStateMessage
                gsm.turnInfo.phase.name
                    .toForgePhaseName() == expectedPhase &&
                    (gsm.annotationsList + gsm.persistentAnnotationsList).any { expected in it.typeList }
            }
    }

    private fun annotationTypes(): List<String> =
        harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .flatMap { it.typeList }
            .map { it.name }

    private fun annotationTypesByPhase(): List<String> =
        harness.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
            .flatMap { message ->
                val phase = message.gameStateMessage.turnInfo.phase.name
                (message.gameStateMessage.annotationsList + message.gameStateMessage.persistentAnnotationsList)
                    .flatMap { it.typeList }
                    .map { "${it.name}@$phase" }
            }

    private fun actionAvailable(condition: ActionAvailableCondition): Boolean {
        val expectedType =
            when (condition.type) {
                AcceptanceActionType.PlayLand -> ActionType.Play_add3
                AcceptanceActionType.PlayMdfc -> ActionType.PlayMdfc
                AcceptanceActionType.Cast -> ActionType.Cast
                AcceptanceActionType.CastMdfc -> ActionType.CastMdfc
                AcceptanceActionType.CastAdventure -> ActionType.CastAdventure
                AcceptanceActionType.CastOmen -> ActionType.CastOmen
                AcceptanceActionType.Activate -> ActionType.Activate_add3
            }
        val candidates =
            harness.actions
                ?.actionsList
                .orEmpty()
                .filter { it.actionType == expectedType }
        val namedMatch =
            candidates.any { action ->
                action.actionType == expectedType &&
                    actionCardName(action).equals(condition.card, ignoreCase = true) &&
                    (condition.altCost == null || actionMatchesAltCost(action, condition.altCost))
            }
        if (namedMatch) return true
        return condition.type in
            listOf(
                AcceptanceActionType.PlayMdfc,
                AcceptanceActionType.CastMdfc,
                AcceptanceActionType.CastAdventure,
                AcceptanceActionType.CastOmen,
            ) &&
            condition.altCost == null &&
            candidates.size == 1
    }

    private fun actionCardName(action: Action): String? {
        harness.cardNameByGrpId(action.grpId)?.let { return it }
        val objects = harness.observe().client.objects
        objects[action.instanceId]?.grpId?.let { harness.cardNameByGrpId(it) }?.let { return it }
        objects[action.sourceId]?.grpId?.let { harness.cardNameByGrpId(it) }?.let { return it }
        return harness
            .observe()
            .cards
            .firstOrNull { it.instanceId == action.instanceId || it.instanceId == action.sourceId }
            ?.name
    }

    private fun actionMatchesZone(
        action: Action,
        expectedZone: AcceptanceZone,
    ): Boolean {
        val card = harness.observe().cards.firstOrNull { it.instanceId == action.instanceId } ?: return expectedZone == AcceptanceZone.Hand
        return card.zone == expectedZone.toForgeZone().name
    }

    private fun actionMatchesAltCost(
        action: Action,
        altCost: AcceptanceAltCost?,
    ): Boolean = altCost == null || harness.actionMatchesAlternative(action, altCost.keywordAbilityId)

    private fun promptSeen(
        prompt: String,
        promptId: Int?,
    ): Boolean = harness.allMessages.any { it.matchesPrompt(prompt, promptId) }

    private fun latestPromptMatches(
        prompt: String,
        promptId: Int? = null,
    ): Boolean = latestPromptMessage()?.matchesPrompt(prompt, promptId) == true

    private fun latestPromptMessage(): GREToClientMessage? =
        harness.allMessages
            .asReversed()
            .firstOrNull { it.isPromptMessage() }

    private fun latestPromptNameWithId(): String? = latestPromptMessage()?.let { msg -> "${msg.promptName()}#${msg.prompt.promptId}" }

    private fun finalWinnerSeat(): Int? {
        val resultRows =
            harness.allMessages
                .asReversed()
                .asSequence()
                .filter { it.hasGameStateMessage() && it.gameStateMessage.hasGameInfo() }
                .flatMap {
                    val results = it.gameStateMessage.gameInfo.resultsList
                    results.asReversed().asSequence()
                }
        return resultRows.firstOrNull { it.winningTeamId != 0 }?.winningTeamId
    }

    private fun finalLoserSeat(): Int? = finalWinnerSeat()?.let { 3 - it }

    private fun cardsInZone(
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ) = harness.cards(side, zone)

    private fun requireAction(action: () -> Boolean) {
        require(action()) { "$context action failed" }
    }

    private fun submitAction(action: Action) {
        harness.submit(
            MatchIntent.Play(
                PlayAction.Selection(
                    ActionSelection(
                        kind = action.kind(),
                        instanceId = action.instanceId,
                        abilityGrpId = action.abilityGrpId,
                        alternativeGrpId = action.alternativeGrpId,
                    ),
                ),
            ),
        )
    }

    private fun Action.kind(): ActionKind =
        when (actionType) {
            ActionType.Pass -> ActionKind.Pass
            ActionType.Cast -> ActionKind.Cast
            ActionType.Activate_add3 -> ActionKind.Activate
            ActionType.ActivateMana -> ActionKind.ActivateMana
            ActionType.Play_add3 -> ActionKind.PlayLand
            ActionType.PlayMdfc -> ActionKind.PlayMdfc
            ActionType.CastMdfc -> ActionKind.CastMdfc
            ActionType.CastAdventure -> ActionKind.CastAdventure
            ActionType.CastOmen -> ActionKind.CastOmen
            ActionType.SpecialTurnFaceUp_add3 -> ActionKind.TurnFaceUp
            else -> error("Unsupported semantic action: $actionType")
        }

    private fun resolveTargetInstanceId(target: AcceptanceTargetSpec): Int =
        when (target) {
            is PlayerTargetSpec -> seat(target.side).value
            is CardTargetSpec -> resolveCardInZone(target.side, target.zone, target.card)
        }

    private fun resolveBattlefieldCard(
        side: AcceptanceSide,
        card: String,
    ): Int = resolveCardInZone(side, AcceptanceZone.Battlefield, card)

    private fun resolvePromptCardOrder(
        candidateIds: List<Int>,
        cards: List<String>,
    ): List<Int> {
        require(candidateIds.size == cards.size) {
            "$context ordered ${cards.size} cards but OrderReq has ${candidateIds.size} candidates ${promptCardNames(candidateIds)}"
        }
        val remaining = candidateIds.toMutableList()
        return cards.map { card ->
            val id =
                remaining.firstOrNull { iid -> cardNameByInstanceId(iid).equals(card, ignoreCase = true) }
                    ?: error("$context could not find $card in OrderReq candidates ${promptCardNames(candidateIds)}")
            remaining.remove(id)
            id
        }
    }

    private fun resolveCardInZone(
        side: AcceptanceSide,
        zone: AcceptanceZone,
        cardName: String,
    ): Int =
        harness
            .observe()
            .cards
            .firstOrNull {
                it.seat == (if (side == AcceptanceSide.Ours) 1 else 2) &&
                    it.zone == zone.toForgeZone().name &&
                    it.name.equals(cardName, ignoreCase = true)
            }?.instanceId ?: error("could not find $cardName in ${side.yamlName} ${zone.yamlName}")

    private fun promptCardNames(ids: List<Int>): List<String> = ids.map { iid -> cardNameByInstanceId(iid) ?: "iid=$iid" }

    private fun cardNameByInstanceId(iid: Int): String? =
        harness
            .observe()
            .cards
            .firstOrNull { it.instanceId == iid }
            ?.name

    private fun zoneCardNames(
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<String> = cardsInZone(side, zone).map { it.name }

    private fun actionSummary(action: Action): String = "${action.actionType.name}:${actionCardName(action) ?: "?"}"
}
