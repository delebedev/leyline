package leyline.tooling.simclient

import forge.ai.AiCostDecision
import forge.ai.PlayerControllerAi
import forge.card.ColorSet
import forge.card.MagicColor
import forge.game.GameActionUtil
import forge.game.GameObject
import forge.game.IEntityMap
import forge.game.ability.effects.CharmEffect
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.combat.Combat
import forge.game.cost.CostSacrifice
import forge.game.player.Player
import forge.game.spellability.AbilitySub
import forge.game.spellability.LandAbility
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptAbilityAdvisor
import leyline.bridge.handoff.PromptAdviceRequest
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.CastRails
import leyline.game.mapping.resolveAltGrpId
import leyline.game.snapshot.BoundCard
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/**
 * Forge-AI advisor that picks an AAR action to submit on behalf of the
 * simclient seat.
 *
 * Builds a parallel [PlayerControllerAi] for the seat (NOT registered as the
 * Forge `Player`'s actual controller — leyline's bridged controller stays in
 * place and continues to emit GRE prompts). The advisor is consulted as a
 * read-only decision source: at a priority window we ask the AI what it would
 * play, then translate the chosen [forge.game.spellability.SpellAbility] into
 * a matching `ActionsAvailableReq` action and submit via the existing
 * `session.onPerformAction` path.
 *
 * ## Load-bearing rules — DO NOT BREAK when adding a new translator method
 *
 * 1. **Don't register [PlayerControllerAi] as the player's controller.**
 *    Leyline's bridged `PlayerController` (extends `PlayerControllerHuman`)
 *    MUST stay registered — it's what emits GRE prompts and blocks on
 *    response futures. Swap it out and the simclient stops producing prompt
 *    traces.
 *
 * 2. **Wrap every Forge-AI call in [askAi].** Forge AI internals (e.g.
 *    `forge.ai.AiCostDecision`, `forge.ai.ability.AttachAi`) cast
 *    `player.getController()` to `PlayerControllerAi`. Outside that scope
 *    you'll get `ClassCastException` because the registered controller is
 *    leyline's bridge. Forge's own `runWithController` is NOT enough: the
 *    bridge registers its controller at `Long.MAX_VALUE - 1`, above any
 *    `getNextTimestamp()` layer, so [askAi] layers the AI controller at
 *    `Long.MAX_VALUE` and removes it in `finally`.
 *
 * 3. **Skip AI consult on Pass-only AARs** (caller side — see
 *    `SimClientDriver.hasCastableActionsInAar`). Forge AI's search costs
 *    50-200ms; during that window leyline's auto-pass loop consumes the
 *    priority window, and our subsequent submit lands "no pending action",
 *    causing a state resync that pollutes the trace with a spurious GSM.
 *    The driver's race guard handles this; translators here don't need to.
 *
 * Scope: one consult method per adapter in [ForgeAiPromptPolicy]'s registry
 * (AAR, attackers/blockers, SelectN, SelectTargets, CTO, sacrifice
 * PayCosts). Prompt types without an adapter — and any consult that fails
 * closed — fall through to the greedy responder in [SimClientDriver].
 */
class ForgeAiPolicy(
    private val harness: MatchFlowHarness,
    private val seatId: SeatId,
) : PromptAbilityAdvisor {
    /** Resolved on first use — bridge is `lateinit` and not ready at construction. */
    private val seatPlayer: Player by lazy {
        harness.bridge.getPlayer(seatId)
            ?: error("ForgeAiPolicy: seat $seatId has no Forge player")
    }

    private val aiController: PlayerControllerAi by lazy {
        PlayerControllerAi(harness.game(), seatPlayer, seatPlayer.lobbyPlayer)
    }

    /** Resolved action ready to submit as a `PerformAction` GRE message. */
    data class Choice(
        val action: Action,
        val instanceId: Int,
        val grpId: Int,
        val actionType: ActionType,
        val abilityGrpId: Int = 0,
    )

    /**
     * Ask the AI what to play at the current priority window. Returns a
     * [Choice] when the AI picks an ability that matches an available AAR
     * action, or null when the AI passes / no actionable match.
     *
     * Match rules (iteration 1):
     *   - LandAbility → first AAR action with `actionType=Play_add3` and same grpId
     *   - isSpell()    → first AAR action with `actionType=Cast`         and same grpId
     *   - non-spell activated ability → exact `Activate_add3` host iid + abilityGrpId match
     *   - everything else → null (driver falls back to greedy / pass)
     */
    fun chooseAarAction(
        promptActions: List<Action>,
        skipFingerprints: Set<String> = emptySet(),
    ): Choice? {
        val abilities = askAi("chooseSpellAbilityToPlay") { aiController.chooseSpellAbilityToPlay() }
        if (abilities.isNullOrEmpty()) return null

        for (sa in abilities) {
            val hostCard = sa.hostCard ?: continue
            val grpId = harness.bridge.cardRepository.findGrpIdByName(hostCard.name) ?: continue
            val actionType =
                when {
                    sa is LandAbility -> ActionType.Play_add3
                    sa.isSpell -> ActionType.Cast
                    else -> ActionType.Activate_add3
                }
            // Prefer the action whose instanceId matches Forge's card id so we cast
            // THIS specific copy. getOrAlloc is idempotent (returns the existing
            // mapping if one exists) — no harmful side effect.
            val mappedInstanceId =
                harness.bridge.getOrAllocInstanceId(ForgeCardId(hostCard.id)).value
            val match = chooseMatchingAction(sa, actionType, grpId, mappedInstanceId, promptActions, skipFingerprints) ?: continue
            return Choice(match, match.instanceId, match.grpId, actionType, match.abilityGrpId)
        }
        return null
    }

    private fun chooseMatchingAction(
        sa: SpellAbility,
        actionType: ActionType,
        grpId: Int,
        mappedInstanceId: Int,
        promptActions: List<Action>,
        skipFingerprints: Set<String>,
    ): Action? {
        if (actionType != ActionType.Activate_add3) {
            val candidates =
                promptActions.filter {
                    it.actionType == actionType && it.grpId == grpId && !it.isSkippedBy(skipFingerprints)
                }
            return chooseCastVariant(sa, grpId, candidates.filter { it.instanceId == mappedInstanceId })
                ?: chooseCastVariant(sa, grpId, candidates)
        }

        val hostCard = sa.hostCard ?: return null
        val cardData = harness.bridge.cardRepository.findByGrpId(grpId) ?: return null
        val registry = harness.bridge.abilityRegistryFor(hostCard, cardData) ?: return null
        return chooseActivatedAction(sa, registry, mappedInstanceId, promptActions, skipFingerprints)
    }

    private fun chooseCastVariant(
        sa: SpellAbility,
        grpId: Int,
        candidates: List<Action>,
    ): Action? {
        val variant = expectedCastVariant(sa, grpId)
        return chooseCastActionByVariant(candidates, variant)
    }

    private fun expectedCastVariant(
        sa: SpellAbility,
        grpId: Int,
    ): ExpectedCastVariant {
        if (!sa.isSpell) return ExpectedCastVariant.Base
        val rails = CastRails.all.filter { it.saPredicate(sa) }
        if (rails.isEmpty()) return ExpectedCastVariant.Base
        val altCosts = BoundCard.bindAltCosts(harness.bridge.cardRepository.findByGrpId(grpId), harness.bridge.cardRepository)
        val payCostPairs =
            ActionMapper
                .computeEffectiveCost(sa, seatPlayer)
                ?.takeIf { !it.isNoCost }
                ?.let { ActionMapper.forgeManaCostToPairs(it) }
                ?: emptyList()
        val alternativeGrpId =
            rails.firstNotNullOfOrNull { rail ->
                resolveAltGrpId(rail, altCosts, payCostPairs).takeIf { it > 0 }
            }
        return alternativeGrpId?.let(ExpectedCastVariant::Alternative) ?: ExpectedCastVariant.UnresolvedAlternative
    }

    /**
     * Ask the AI which creatures should attack. Uses a throwaway [Combat]
     * object so the consult does not mutate Forge's live combat before the
     * bridge receives a DeclareAttackers response.
     */
    fun chooseAttackers(): List<Int>? {
        val probeCombat = Combat(seatPlayer)
        askAi("declareAttackers") { aiController.declareAttackers(seatPlayer, probeCombat) } ?: return null
        return probeCombat.getAttackers().map { attacker ->
            harness.bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
        }
    }

    /**
     * Ask the AI which of the seat's creatures should block the current
     * attackers. Uses a probe [Combat] so the consult does not mutate Forge's
     * live combat before the bridge receives a DeclareBlockers response.
     * Returns a `blockerInstanceId → attackerInstanceId` map ready for
     * [MatchFlowHarness.declareBlockers], or null when there are no blocks.
     *
     * For multi-block (one blocker assigned to several attackers) we emit one
     * entry per (blocker, attacker) pair.
     */
    fun chooseBlockers(): Map<Int, Int>? {
        val combat: Combat = harness.game().combat ?: return null
        if (combat.getAttackers().isEmpty()) return null
        val probeCombat = Combat(combat, identityEntityMap())
        askAi("declareBlockers") { aiController.declareBlockers(seatPlayer, probeCombat) } ?: return null
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (attacker in probeCombat.getAttackers()) {
            val blockers = probeCombat.getBlockers(attacker)
            if (blockers.isNullOrEmpty()) continue
            for (blocker in blockers) {
                val blockerId = harness.bridge.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
                val attackerId = harness.bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
                pairs += blockerId to attackerId
            }
        }
        if (pairs.isEmpty()) return null
        // declareBlockers takes Map<blockerInstanceId, attackerInstanceId>; the
        // multi-block case (same blocker → multiple attackers) collapses to the
        // last assignment per blocker. Acceptable for iteration 2; refine if AI
        // ever banding-blocks.
        return pairs.toMap()
    }

    private fun identityEntityMap(): IEntityMap =
        object : IEntityMap {
            override fun getGame() = harness.game()

            override fun map(o: GameObject): GameObject = o
        }

    fun canChooseSelectN(req: SelectNReq): Boolean {
        val count = selectNCount(req)
        if (count <= 0) return false
        val cards = req.idsList.mapNotNull { cardForInstance(it) }
        return cards.size == req.idsList.size && cards.all { it.zone?.zoneType == ZoneType.Hand }
    }

    fun chooseSelectN(req: SelectNReq): List<Int>? {
        val count = selectNCount(req)
        if (count <= 0) return emptyList()
        val candidates = req.idsList.mapNotNull { cardForInstance(it) }
        if (candidates.size != req.idsList.size || candidates.any { it.zone?.zoneType != ZoneType.Hand }) return null
        val targetPlayer = candidates.firstOrNull()?.owner ?: return null
        if (candidates.any { it.owner != targetPlayer }) return null
        val sourceSa = cardForInstance(req.sourceId)?.spellAbilities?.firstOrNull()
        val validCards = CardCollection(candidates)
        val chosen =
            askAi("chooseCardsToDiscardFrom") {
                aiController.chooseCardsToDiscardFrom(targetPlayer, sourceSa, validCards, count, count, validCards)
            } ?: return null
        val chosenIds = chosen.map { instanceIdForCard(it) }.filter { it != 0 }
        return chosenIds.takeIf { it.size >= count }?.take(count)
    }

    fun canChooseStaticColorSelectN(msg: GREToClientMessage): Boolean {
        if (!msg.hasSelectNReq()) return false
        val req = msg.selectNReq
        return req.staticList == StaticList.Colors && selectNCount(req) > 0
    }

    fun chooseStaticColorSelectN(msg: GREToClientMessage): List<Int>? {
        if (!canChooseStaticColorSelectN(msg)) return null
        val req = msg.selectNReq
        val pending = harness.bridge.promptBridge(seatId).getPendingPrompt()
        val allowedIds = allowedStaticColorIds(req, pending?.request?.staticOptionIds.orEmpty())
        val prompt = pending ?: return null
        return requestPromptAdvice(
            prompt.promptId,
            PromptAdviceRequest.StaticColors(
                allowedIds = allowedIds,
                min = req.minSel.coerceAtLeast(1),
                max = (if (req.maxSel > 0) req.maxSel else req.minSel).coerceAtLeast(1),
            ),
        )
    }

    fun canChooseSacrificeCostPayment(msg: GREToClientMessage): Boolean = sacrificeCostPrompt(msg) != null

    /**
     * Recover the Forge-AI cost decision for a sacrifice cost-payment prompt.
     * The pending bridge prompt carries the [SpellAbility] being paid; the AI
     * cost visitor picks the cards it would sacrifice, and those map to the
     * prompt's candidate instance ids. Fails closed (null) when the prompt is
     * not a sacrifice cost selection or the AI choice does not map onto the
     * offered candidates.
     *
     * Assumes the pending prompt corresponds to the SA's first [CostSacrifice]
     * part; an SA with several sacrifice parts would need prompt/part
     * correlation. Candidate-id validation keeps a mismatched decision from
     * being submitted — it degrades to greedy instead.
     */
    fun chooseSacrificeCostPayment(msg: GREToClientMessage): List<Int>? {
        val prompt = sacrificeCostPrompt(msg) ?: return null
        val selection = msg.payCostsReq.effectCostReq.costSelection
        val min = selection.minSel.coerceAtLeast(0)
        val max = if (selection.maxSel > 0) selection.maxSel else min
        return requestPromptAdvice(
            prompt.promptId,
            PromptAdviceRequest.SacrificeCost(selection.idsList, min, max),
        )
    }

    private fun sacrificeCostPrompt(msg: GREToClientMessage): InteractivePromptBridge.PendingPrompt? {
        if (!msg.hasPayCostsReq() || !msg.payCostsReq.hasEffectCostReq()) return null
        if (msg.payCostsReq.effectCostReq.costSelection.idsCount == 0) return null
        val bridge = runCatching { harness.bridge }.getOrNull() ?: return null
        val pending = bridge.promptBridge(seatId).getPendingPrompt() ?: return null
        val route = pending.request.route as? ResolvedPromptRoute.PayCosts ?: return null
        if (route.descriptor.kind != PayCostsRouteKind.Sacrifice) return null
        return pending
    }

    fun canChooseSelectTargets(msg: GREToClientMessage): Boolean {
        if (!msg.hasSelectTargetsReq()) return false
        val req = msg.selectTargetsReq
        if (req.targetsCount == 0) return false
        return req.targetsList.all { group ->
            val selectableCount = group.targetsList.count { it.legalAction == SelectAction.Select_a1ad }
            val exactRequired = group.minTargets == group.maxTargets && group.minTargets >= 0
            val optionalSingle = group.minTargets == 0 && group.maxTargets == 1
            (exactRequired || optionalSingle) && selectableCount >= group.minTargets
        }
    }

    fun chooseSelectTargets(msg: GREToClientMessage): List<Int>? {
        if (!canChooseSelectTargets(msg)) return null
        val selectableIds =
            msg.selectTargetsReq.targetsList
                .asSequence()
                .flatMap { it.targetsList.asSequence() }
                .filter { it.legalAction == SelectAction.Select_a1ad }
                .map { it.targetInstanceId }
                .toSet()
        val minCount = msg.selectTargetsReq.targetsList.sumOf { it.minTargets.coerceAtLeast(0) }
        val maxCount =
            msg.selectTargetsReq.targetsList
                .sumOf { group -> group.maxTargets.takeIf { it >= group.minTargets } ?: group.minTargets }
                .coerceAtLeast(minCount)
        val prompt = harness.bridge.promptBridge(seatId).getPendingPrompt() ?: return null
        return requestPromptAdvice(
            prompt.promptId,
            PromptAdviceRequest.SelectTargets(selectableIds, minCount, maxCount),
        )
    }

    fun canChooseCastingTimeOptions(msg: GREToClientMessage): Boolean {
        if (!msg.hasCastingTimeOptionsReq()) return false
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        return isManaTypeCto(options) || isSimpleModalCto(options) || isSingleOptionalCostCto(options)
    }

    internal fun chooseCastingTimeOptions(msg: GREToClientMessage): SimDecision? {
        if (!canChooseCastingTimeOptions(msg)) return null
        return chooseManaTypeCastingTimeOptions(msg)?.let { SimDecision.ManaTypeChoices(it) }
            ?: chooseModalCastingTimeOptions(msg)?.let { SimDecision.ModalChoice(it) }
            ?: chooseOptionalCastingTimeOptions(msg)?.let { SimDecision.OptionalCost(it) }
    }

    private fun chooseManaTypeCastingTimeOptions(msg: GREToClientMessage): List<Pair<Int, ManaColor>>? {
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        if (!isManaTypeCto(options)) return null
        return options.map { option ->
            val color = option.selectManaTypeReq.manaColorsList.firstOrNull { it != ManaColor.TwoGeneric } ?: ManaColor.TwoGeneric
            option.ctoId to color
        }
    }

    private fun chooseModalCastingTimeOptions(msg: GREToClientMessage): List<Int>? {
        if (!isSimpleModalCto(msg.castingTimeOptionsReq.castingTimeOptionReqList)) return null
        val modal =
            msg.castingTimeOptionsReq.castingTimeOptionReqList
                .single()
                .modalReq
        val modalGrpIds = modal.modalOptionsList.map { it.grpId }
        val pending = harness.bridge.promptBridge(seatId).getPendingPrompt() ?: return null
        return requestPromptAdvice(
            pending.promptId,
            PromptAdviceRequest.ModalChoice(modalGrpIds),
        )
    }

    private fun requestPromptAdvice(
        promptId: String,
        request: PromptAdviceRequest,
    ): List<Int>? {
        val promptBridge = harness.bridge.promptBridge(seatId)
        promptBridge.promptAbilityAdvisor = this
        return promptBridge.requestPromptAdvice(promptId, request)
    }

    override fun advise(
        ability: SpellAbility,
        prompt: PromptRequest,
        request: PromptAdviceRequest,
    ): List<Int>? =
        when (request) {
            is PromptAdviceRequest.StaticColors -> adviseStaticColors(ability, prompt, request)
            is PromptAdviceRequest.SacrificeCost -> adviseSacrificeCost(ability, request)
            is PromptAdviceRequest.SelectTargets -> adviseSelectTargets(ability, request)
            is PromptAdviceRequest.ModalChoice -> adviseModalChoice(ability, prompt, request)
        }

    private fun adviseStaticColors(
        ability: SpellAbility,
        prompt: PromptRequest,
        request: PromptAdviceRequest.StaticColors,
    ): List<Int>? {
        val allowedColors = colorSetFromStaticIds(request.allowedIds)
        if (allowedColors.isColorless) return null
        val colors =
            askAi("chooseColors") {
                aiController.chooseColors(prompt.message, ability, request.min, request.max, allowedColors)
            } ?: return null
        return colors
            .toStaticColorIds()
            .filter { it in request.allowedIds }
            .take(request.max)
            .takeIf { it.size >= request.min }
    }

    private fun adviseSacrificeCost(
        ability: SpellAbility,
        request: PromptAdviceRequest.SacrificeCost,
    ): List<Int>? {
        val costPart =
            ability.payCosts
                ?.costParts
                ?.filterIsInstance<CostSacrifice>()
                ?.firstOrNull() ?: return null
        val decision =
            askAi("sacrificeCostDecision") {
                costPart.accept(AiCostDecision(seatPlayer, ability, false))
            } ?: return null
        val chosenIds = decision.cards.map(::instanceIdForCard)
        if (chosenIds.any { it == 0 || it !in request.selectableIds }) return null
        if (chosenIds.distinct().size != chosenIds.size) return null
        return chosenIds.takeIf { it.size in request.min..request.max }
    }

    private fun adviseSelectTargets(
        ability: SpellAbility,
        request: PromptAdviceRequest.SelectTargets,
    ): List<Int>? {
        val previousTargets = ability.targets.clone()
        val chosenTargets =
            try {
                ability.targets.clear()
                val chose = askAi("chooseTargetsFor") { aiController.chooseTargetsFor(ability) } ?: false
                if (!chose) return null
                ability.targets.toList()
            } finally {
                ability.targets.clear()
                ability.targets.addAll(previousTargets)
            }
        if (chosenTargets.size !in request.min..request.max) return null
        val selectedIds = chosenTargets.map { targetInstanceId(it) ?: return null }
        return selectedIds.takeIf { ids ->
            ids.size == ids.distinct().size && ids.all { it in request.selectableIds }
        }
    }

    private fun adviseModalChoice(
        ability: SpellAbility,
        prompt: PromptRequest,
        request: PromptAdviceRequest.ModalChoice,
    ): List<Int>? {
        val possible =
            modalPossibleAbilities(
                ability,
                prompt.modalChoice?.possible?.map { it.fullIndex },
                request.modalGrpIds.size,
            ) ?: return null
        modalChoiceGrpIds(ability.chosenList, possible, request.modalGrpIds)?.let { return it }
        modalChoiceGrpIds(subAbilityChain(ability.subAbility), possible, request.modalGrpIds)?.let { return it }
        val previousSub = ability.subAbility
        val previousChosen = ability.chosenList
        val chosen =
            try {
                ability.subAbility = null
                askAi("chooseModeForAbility") { aiController.chooseModeForAbility(ability, possible, 1, 1, false) }
            } finally {
                ability.subAbility = previousSub
                ability.chosenList = previousChosen
            } ?: return null
        return modalChoiceGrpIds(chosen, possible, request.modalGrpIds)
    }

    private fun chooseOptionalCastingTimeOptions(msg: GREToClientMessage): Int? {
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        if (!isSingleOptionalCostCto(options)) return null
        val costOption = options.first { !it.isRequired }
        val card = cardForInstance(costOption.affectedId) ?: return null
        val sa = getAllCastableAbilities(card, seatPlayer).firstOrNull() ?: return null
        sa.activatingPlayer = seatPlayer
        val optionalCosts = GameActionUtil.getOptionalCostValues(sa)
        if (optionalCosts.size != 1) return null
        val chosen = askAi("chooseOptionalCosts") { aiController.chooseOptionalCosts(sa, optionalCosts) } ?: return null
        return if (optionalCostChosen(chosen, optionalCosts.single())) costOption.ctoId else 0
    }

    private fun optionalCostChosen(
        chosen: List<OptionalCostValue>,
        option: OptionalCostValue,
    ): Boolean = chosen.any { it.type == option.type && it.cost.toString() == option.cost.toString() }

    private fun isSimpleModalCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean {
        if (options.size != 1) return false
        val option = options.single()
        if (option.castingTimeOptionType != CastingTimeOptionType.Modal_a7b4 || !option.hasModalReq()) return false
        val modal = option.modalReq
        return modal.minSel == 1 && modal.maxSel == 1 && modal.modalOptionsCount > 0
    }

    private fun isManaTypeCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean =
        options.isNotEmpty() &&
            options.all {
                it.ctoId > 0 &&
                    it.isRequired &&
                    it.castingTimeOptionType == CastingTimeOptionType.ManaType &&
                    it.hasSelectManaTypeReq()
            }

    private fun isSingleOptionalCostCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean {
        if (options.size != 2) return false
        val costOptions = options.filter { !it.isRequired }
        val doneOptions = options.filter { it.isRequired && it.castingTimeOptionType == CastingTimeOptionType.Done }
        if (costOptions.size != 1 || doneOptions.size != 1) return false
        val costOption = costOptions.single()
        return costOption.ctoId > 0 &&
            costOption.affectedId != 0 &&
            costOption.castingTimeOptionType == CastingTimeOptionType.Kicker
    }

    private fun modalChoiceGrpIds(
        chosen: List<AbilitySub>?,
        possible: List<AbilitySub>,
        modalGrpIds: List<Int>,
    ): List<Int>? {
        if (chosen?.size != 1) return null
        val selected = chosen.single()
        val index =
            possible.indexOf(selected).takeIf { it >= 0 }
                ?: selected.description?.let { description ->
                    possible.indexOfFirst { it.description == description }.takeIf { it >= 0 }
                }
                ?: return null
        return listOf(modalGrpIds.getOrNull(index) ?: return null)
    }

    private fun subAbilityChain(first: AbilitySub?): List<AbilitySub> {
        val result = mutableListOf<AbilitySub>()
        var current = first
        while (current != null) {
            result += current
            current = current.subAbility
        }
        return result
    }

    private fun modalPossibleAbilities(
        sa: SpellAbility,
        possibleFullIndices: List<Int>?,
        modalOptionCount: Int,
    ): MutableList<AbilitySub>? {
        val fullList = sa.getAdditionalAbilityList("Choices")
        if (possibleFullIndices != null) {
            if (fullList == null) return null
            val possible = possibleFullIndices.map { idx -> fullList.getOrNull(idx) ?: return null }
            return possible.toMutableList().takeIf { it.size == modalOptionCount }
        }
        val possible = CharmEffect.makePossibleOptions(sa) ?: return null
        return possible.toMutableList().takeIf { it.size == modalOptionCount }
    }

    private fun selectNCount(req: SelectNReq): Int {
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        return min.coerceAtMost(max)
    }

    private fun cardForInstance(instanceId: Int): Card? {
        if (instanceId == 0) return null
        val forgeId = harness.bridge.getForgeCardId(InstanceId(instanceId)) ?: return null
        return harness.bridge.findCard(forgeId)
    }

    private fun instanceIdForCard(card: Card): Int = harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value

    private fun targetInstanceId(target: GameObject): Int? =
        when (target) {
            is Card -> instanceIdForCard(target)
            is Player -> if (target == seatPlayer) seatId.value else 3 - seatId.value
            else -> null
        }

    private fun ColorSet.toStaticColorIds(): List<Int> =
        buildList {
            if (hasWhite()) StaticChoiceIds.colorIdForMask(MagicColor.WHITE)?.let(::add)
            if (hasBlue()) StaticChoiceIds.colorIdForMask(MagicColor.BLUE)?.let(::add)
            if (hasBlack()) StaticChoiceIds.colorIdForMask(MagicColor.BLACK)?.let(::add)
            if (hasRed()) StaticChoiceIds.colorIdForMask(MagicColor.RED)?.let(::add)
            if (hasGreen()) StaticChoiceIds.colorIdForMask(MagicColor.GREEN)?.let(::add)
        }

    private var askAiDepth = 0

    private fun <T> askAi(
        label: String,
        block: () -> T,
    ): T? {
        // The bridge registers leyline's PlayerController at Long.MAX_VALUE - 1,
        // so Forge's Player.runWithController (which layers at getNextTimestamp())
        // never out-ranks it. Layer the AI controller at Long.MAX_VALUE instead so
        // Forge-AI internals that cast player.getController() see PlayerControllerAi.
        // The fixed slot is shared, so nested consults only add/remove at depth 0.
        var result: T? = null
        var threw: Throwable? = null
        if (askAiDepth == 0) seatPlayer.addController(Long.MAX_VALUE, seatPlayer, aiController, false)
        askAiDepth += 1
        try {
            result = block()
        } catch (t: Throwable) {
            threw = t
        } finally {
            askAiDepth -= 1
            if (askAiDepth == 0) seatPlayer.removeController(Long.MAX_VALUE, false)
        }
        val failure = threw
        if (failure != null) {
            log.warn(
                "Forge AI {} threw: {}: {} at {}",
                label,
                failure::class.simpleName,
                failure.message,
                failure.stackTrace.firstOrNull(),
            )
        }
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(ForgeAiPolicy::class.java)
    }
}

internal sealed interface ExpectedCastVariant {
    data object Base : ExpectedCastVariant

    data class Alternative(
        val alternativeGrpId: Int,
    ) : ExpectedCastVariant

    data object UnresolvedAlternative : ExpectedCastVariant
}

internal fun chooseCastActionByVariant(
    candidates: List<Action>,
    variant: ExpectedCastVariant,
): Action? =
    when (variant) {
        ExpectedCastVariant.Base -> candidates.firstOrNull { it.alternativeGrpId == 0 }
        is ExpectedCastVariant.Alternative -> candidates.firstOrNull { it.alternativeGrpId == variant.alternativeGrpId }
        ExpectedCastVariant.UnresolvedAlternative -> null
    }

/**
 * Validate AI-chosen sacrifice ids against the cost selection contract:
 * every id must be an offered candidate, chosen exactly once, and the count
 * must satisfy the selection's min/max. Null means "no usable AI decision".
 */
internal fun sacrificeCostSelectionIds(
    chosenIds: List<Int>,
    selection: SelectNReq,
): List<Int>? {
    if (chosenIds.isEmpty()) return null
    val allowed = selection.idsList.toSet()
    if (chosenIds.any { it == 0 || it !in allowed }) return null
    if (chosenIds.distinct().size != chosenIds.size) return null
    val min = selection.minSel.coerceAtLeast(0)
    val max = if (selection.maxSel > 0) selection.maxSel else min
    if (chosenIds.size !in min..max) return null
    return chosenIds
}

internal fun allowedStaticColorIds(
    req: SelectNReq,
    promptStaticOptionIds: List<Int>,
): List<Int> {
    val ids =
        req.idsList
            .ifEmpty { promptStaticOptionIds }
            .ifEmpty {
                listOfNotNull(
                    StaticChoiceIds.colorIdForMask(MagicColor.WHITE),
                    StaticChoiceIds.colorIdForMask(MagicColor.BLUE),
                    StaticChoiceIds.colorIdForMask(MagicColor.BLACK),
                    StaticChoiceIds.colorIdForMask(MagicColor.RED),
                    StaticChoiceIds.colorIdForMask(MagicColor.GREEN),
                )
            }
    return ids.distinct()
}

internal fun colorSetFromStaticIds(ids: List<Int>): ColorSet {
    val mask =
        ids.fold(0) { acc, id ->
            acc or
                when (id) {
                    StaticChoiceIds.colorIdForMask(MagicColor.WHITE) -> MagicColor.WHITE.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.BLUE) -> MagicColor.BLUE.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.BLACK) -> MagicColor.BLACK.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.RED) -> MagicColor.RED.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.GREEN) -> MagicColor.GREEN.toInt()
                    else -> 0
                }
        }
    return ColorSet.fromMask(mask)
}
