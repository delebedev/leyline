package leyline.match

import forge.card.mana.ManaCost
import leyline.DevCheck
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.InstanceId
import leyline.bridge.types.ManaColorMapping
import leyline.game.bundle.CastingTimeOptionsBuilder
import leyline.game.bundle.CastingTimeOptionsBuilder.ManaRequirementSpec
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Owns pre-engine cast-cost prompts and deferred cast replay. */
internal class DeferredCastCostInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
    private val getPendingInteraction: () -> PendingClientInteraction?,
    private val setPendingInteraction: (PendingClientInteraction?) -> Unit,
) {
    private val log = LoggerFactory.getLogger(DeferredCastCostInteractionHandler::class.java)

    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        when (val pending = getPendingInteraction()) {
            is PendingClientInteraction.AlternateCostChoice -> {
                setPendingInteraction(null)
                onAlternateCostChoiceResponse(greMsg, pending, autoPass)
                return true
            }
            is PendingClientInteraction.OptionalCost -> {
                setPendingInteraction(null)
                onOptionalCostResponse(greMsg, pending, autoPass)
                return true
            }
            is PendingClientInteraction.HybridManaType -> {
                setPendingInteraction(null)
                onHybridManaTypeResponse(greMsg, pending, autoPass)
                return true
            }
            is PendingClientInteraction.ModalChoice,
            is PendingClientInteraction.Search,
            is PendingClientInteraction.TargetSelection,
            null,
            -> return false
        }
    }

    fun checkHybridManaTypeOptions(
        action: Action,
        pendingActionId: String,
        castAbilityIndex: Int?,
    ): Boolean {
        if (action.alternativeGrpId != 0) return false
        val bridge = ctx.bridge
        val game = ctx.game
        val seatBridge = bridge.seat(counters.seatId)
        seatBridge.prompt.journal.clearHybridManaStash()

        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false
        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = getAllCastableAbilities(card, player)
        val sa = castAbilityIndex?.let { castable.getOrNull(it) } ?: castable.firstOrNull() ?: return false
        sa.setActivatingPlayer(player)
        val effectiveCost = ActionMapper.computeEffectiveCost(sa, player) ?: return false
        val paymentColors = effectiveCost.hybridOrTwoGenericColors()
        if (paymentColors.isEmpty()) return false
        val baseCost = sa.payCosts?.totalMana
        val promptCost = baseCost?.takeIf { it.hybridOrTwoGenericColors().size == paymentColors.size } ?: effectiveCost
        val promptColors = promptCost.hybridOrTwoGenericColors()

        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                playerIdToPrompt = counters.seatId.value,
                hybridColors = promptColors,
                manaCost = promptCost.toManaRequirementSpecs(),
            )
        setPendingInteraction(
            PendingClientInteraction.HybridManaType(
                pendingActionId = pendingActionId,
                action = PlayerAction.CastSpell(cardId, castAbilityIndex),
                clientAction = action,
                castAbilityIndex = castAbilityIndex,
                ctoIds = ctoIds,
                promptColors = promptColors,
                paymentColors = paymentColors,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkOptionalCosts(
        action: Action,
        pendingActionId: String,
        castAbilityIndex: Int?,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false

        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = getAllCastableAbilities(card, player)
        val sa = castAbilityIndex?.let { castable.getOrNull(it) } ?: castable.firstOrNull() ?: return false
        sa.setActivatingPlayer(player)
        clearDeferredCastCostStashes(clearHybrid = !preserveHybridStash)

        val optionalCosts = forge.game.GameActionUtil.getOptionalCostValues(sa)
        val keywordCostEntries = collectKeywordCostEntries(card)
        if (optionalCosts.isEmpty() && keywordCostEntries.isEmpty()) return false

        log.info(
            "DeferredCastCostInteractionHandler: card '{}' has {} optional costs and {} keyword costs — sending prompt",
            card.name,
            optionalCosts.size,
            keywordCostEntries.size,
        )

        val cardData = bridge.cardRepository.findByGrpId(action.grpId)
        val keywordCount =
            if (cardData != null) {
                bridge.abilityRegistryFor(card, cardData)?.slotLayout?.keywordCount ?: 0
            } else {
                0
            }
        val optionalCostEntries =
            optionalCosts.mapIndexed { i, cost ->
                val ctoType =
                    when (cost.type) {
                        forge.game.spellability.OptionalCost.Kicker1,
                        forge.game.spellability.OptionalCost.Kicker2,
                        -> CastingTimeOptionType.Kicker
                        else -> CastingTimeOptionType.AdditionalCost
                    }
                val abilityGrpId =
                    if (cost.type == forge.game.spellability.OptionalCost.Bargain) {
                        findKeywordSlot(card, "Bargain", keywordCount)
                            ?.let { cardData?.abilityIds?.getOrNull(it)?.first }
                            ?: 0
                    } else {
                        cardData
                            ?.abilityIds
                            ?.getOrNull(keywordCount + i)
                            ?.first ?: 0
                    }
                Pair(ctoType, abilityGrpId)
            }

        val keywordEntries =
            keywordCostEntries.mapNotNull { kw ->
                val slot = findKeywordSlot(card, kw.name, keywordCount) ?: return@mapNotNull null
                val abilityGrpId = cardData?.abilityIds?.getOrNull(slot)?.first ?: 0
                Triple(CastingTimeOptionType.AdditionalCost, abilityGrpId, kw.name)
            }

        val combinedCostEntries = optionalCostEntries + keywordEntries.map { (ctoType, gid, _) -> ctoType to gid }
        val (ctoReq, costCtoIds) =
            CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                optionalCosts = combinedCostEntries,
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = cardData?.manaCost ?: emptyList(),
            )
        val keywordCtoIdMap =
            keywordEntries
                .mapIndexed { idx, (_, _, kwName) ->
                    val ctoIdx = optionalCostEntries.size + idx
                    val ctoId = costCtoIds.getOrNull(ctoIdx) ?: return@mapIndexed null
                    ctoId to kwName
                }.filterNotNull()
                .toMap()

        setPendingInteraction(
            PendingClientInteraction.OptionalCost(
                pendingActionId = pendingActionId,
                action = PlayerAction.CastSpell(cardId, castAbilityIndex),
                costCtoIds = costCtoIds,
                keywordCostsByCtoId = keywordCtoIdMap,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkAlternateAdditionalCostChoice(
        action: Action,
        pendingActionId: String,
    ): Boolean {
        val bridge = ctx.bridge
        val game = ctx.game
        val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return false
        val card = game.findById(cardId.value) ?: return false
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) return false

        val player = bridge.getPlayer(counters.seatId) ?: return false
        val castable = getAllCastableAbilities(card, player)
        if (castable.size <= 1) return false

        val optionPromptIds = alternateAdditionalCostPromptIds(castable)
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                playerIdToPrompt = counters.seatId.value,
                optionCount = castable.size,
                optionPromptIds = optionPromptIds,
            )
        setPendingInteraction(
            PendingClientInteraction.AlternateCostChoice(
                pendingActionId = pendingActionId,
                cardId = cardId,
                abilityIndicesByCtoId = ctoIds.mapIndexed { index, ctoId -> ctoId to index }.toMap(),
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (alternate additional cost) seat=${counters.seatId} card=${card.name}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun clearDeferredCastCostStashes(clearHybrid: Boolean = true) {
        val journal =
            ctx.bridge
                .seat(counters.seatId)
                .prompt.journal
        journal.clearKeywordCostStash()
        if (clearHybrid) journal.clearHybridManaStash()
        journal.clearCollectEvidenceCost()
    }

    private fun onOptionalCostResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.OptionalCost,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val chosenCtoId = greMsg.castingTimeOptionsResp.castingTimeOptionResp?.ctoId ?: 0
        val accepted = chosenCtoId != 0 && chosenCtoId in pending.costCtoIds
        val isOptionalCostPick = accepted && chosenCtoId !in pending.keywordCostsByCtoId
        val acceptedIndices = if (isOptionalCostPick) listOf(chosenCtoId - 1) else emptyList()

        log.info(
            "DeferredCastCostInteractionHandler: optional cost response ctoId={} accepted={} indices={} keywordPick={}",
            chosenCtoId,
            accepted,
            acceptedIndices,
            chosenCtoId in pending.keywordCostsByCtoId,
        )

        val seatBridge = bridge.seat(counters.seatId)
        TargetingHandler.stashOptionalCostIndices(seatBridge.prompt, acceptedIndices)

        if (pending.keywordCostsByCtoId.isNotEmpty()) {
            val decisions = pending.keywordCostsByCtoId.entries.associate { (ctoId, kwName) -> kwName to (chosenCtoId == ctoId) }
            seatBridge.prompt.journal.record(PromptSideEffect.KeywordCostStash(decisions))
            log.info("DeferredCastCostInteractionHandler: keyword cost decisions stashed: {}", decisions)
        }

        val pendingAction = seatBridge.action.getPending()
        if (pendingAction != null) {
            seatBridge.action.submitAction(pendingAction.actionId, pending.action)
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn("DeferredCastCostInteractionHandler: optional cost response but no pending engine action (likely timeout race)")
            DevCheck.failOnAutoPass { "optional cost response but no pending engine action" }
        }
    }

    private fun onHybridManaTypeResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.HybridManaType,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val resp = greMsg.castingTimeOptionsResp
        val optionResponses =
            if (resp.castingTimeOptionRespsCount >
                0
            ) {
                resp.castingTimeOptionRespsList
            } else {
                listOf(resp.castingTimeOptionResp)
            }
        val byCtoId = optionResponses.associateBy { it.ctoId }
        val promptChoices =
            pending.ctoIds.mapIndexed { index, ctoId ->
                byCtoId[ctoId]
                    ?.takeIf { it.hasSelectManaTypeResp() }
                    ?.selectManaTypeResp
                    ?.manaColor
                    ?: optionResponses
                        .getOrNull(index)
                        ?.takeIf { it.hasSelectManaTypeResp() }
                        ?.selectManaTypeResp
                        ?.manaColor
                    ?: pending.promptColors.getOrNull(index)
                    ?: ManaColor.TwoGeneric
            }
        val choices = promptChoices.reorderHybridChoices(pending.promptColors, pending.paymentColors)
        val seatBridge = bridge.seat(counters.seatId)
        seatBridge.prompt.journal.record(PromptSideEffect.HybridManaStash(choices))
        log.info("DeferredCastCostInteractionHandler: hybrid mana type choices stashed: prompt={} payment={}", promptChoices, choices)

        if (checkOptionalCosts(pending.clientAction, pending.pendingActionId, pending.castAbilityIndex, preserveHybridStash = true)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
            return
        }

        val pendingAction = seatBridge.action.getPending()
        if (pendingAction != null) {
            seatBridge.action.submitAction(pendingAction.actionId, pending.action)
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn("DeferredCastCostInteractionHandler: hybrid mana response but no pending engine action (likely timeout race)")
            DevCheck.failOnAutoPass { "hybrid mana response but no pending engine action" }
        }
    }

    private fun onAlternateCostChoiceResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.AlternateCostChoice,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val optionResp = greMsg.castingTimeOptionsResp.castingTimeOptionResp
        val selectedIndex = optionResp?.selectNResp?.idsList?.firstOrNull()
        val chosenCtoId = optionResp?.ctoId ?: 0
        val abilityIndex = selectedIndex?.let { pending.abilityIndicesByCtoId[it] } ?: pending.abilityIndicesByCtoId[chosenCtoId] ?: 0
        val seatBridge = bridge.seat(counters.seatId)
        val pendingAction = seatBridge.action.getPending()
        if (pendingAction != null) {
            seatBridge.action.submitAction(pendingAction.actionId, PlayerAction.CastSpell(pending.cardId, abilityIndex))
            bridge.awaitPriority()
            autoPass()
        } else {
            log.warn(
                "DeferredCastCostInteractionHandler: alternate cost choice response but no pending engine action (likely timeout race)",
            )
            DevCheck.failOnAutoPass { "alternate cost choice response but no pending engine action" }
        }
    }

    private fun alternateAdditionalCostPromptIds(castable: List<forge.game.spellability.SpellAbility>): List<Int> {
        val promptIds = castable.map { sa -> promptIdForAdditionalCostBranch(sa) }
        return if (promptIds.all { it != null }) promptIds.filterNotNull() else emptyList()
    }

    private fun promptIdForAdditionalCostBranch(sa: forge.game.spellability.SpellAbility): Int? {
        val costs = sa.payCosts ?: return null
        if (costs.isOnlyManaCost) return PromptIds.CHOOSE_OR_COST_PAY_MANA
        val costPartNames = costs.costParts.map { it.javaClass.simpleName }
        return when {
            costPartNames.any { it.contains("Sacrifice") } -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            costPartNames.any { it.contains("Exile") } -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            else -> null
        }
    }

    private fun ManaCost.hybridOrTwoGenericColors(): List<ManaColor> = mapNotNull { shard -> ManaColorMapping.fromOrTwoGenericShard(shard) }

    private fun List<ManaColor>.reorderHybridChoices(
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ): List<ManaColor> {
        val used = BooleanArray(size)
        return paymentColors.map { paymentColor ->
            val promptIndex = promptColors.indices.firstOrNull { index -> !used[index] && promptColors[index] == paymentColor }
            if (promptIndex == null) {
                paymentColor
            } else {
                used[promptIndex] = true
                getOrNull(promptIndex) ?: paymentColor
            }
        }
    }

    private fun ManaCost.toManaRequirementSpecs(): List<ManaRequirementSpec> =
        buildList {
            for (shard in this@toManaRequirementSpecs) {
                val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
                val color = hybridColor ?: ManaColorMapping.fromShard(shard) ?: continue
                add(
                    ManaRequirementSpec(
                        colors =
                            if (hybridColor !=
                                null
                            ) {
                                listOf(ManaColor.TwoGeneric, color)
                            } else {
                                listOf(color)
                            },
                    ),
                )
            }
            if (genericCost > 0) {
                add(ManaRequirementSpec(colors = listOf(ManaColor.Generic), count = genericCost))
            }
        }

    private val binaryKeywordCostNames =
        setOf(
            forge.game.keyword.Keyword.OFFSPRING,
            forge.game.keyword.Keyword.CASUALTY,
            forge.game.keyword.Keyword.CONSPIRE,
        )

    private data class KeywordCostEntry(
        val name: String,
    )

    private fun collectKeywordCostEntries(card: forge.game.card.Card): List<KeywordCostEntry> {
        val out = mutableListOf<KeywordCostEntry>()
        for (ki in card.keywords) {
            val keyword = ki.keyword ?: continue
            if (keyword in binaryKeywordCostNames) {
                out += KeywordCostEntry(keyword.toString())
            }
        }
        return out
    }

    private fun findKeywordSlot(
        card: forge.game.card.Card,
        keywordName: String,
        slotBound: Int,
    ): Int? {
        val keywordStrings =
            card.rules
                ?.mainPart
                ?.keywords
                ?.toList() ?: return null
        for ((idx, kwText) in keywordStrings.withIndex()) {
            if (idx >= slotBound) return null
            if (kwText.startsWith(keywordName)) return idx
        }
        return null
    }
}
