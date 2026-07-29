package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.ActionToken
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.ForgeCardId
import leyline.game.bundle.CastingTimeOptionsBuilder
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Owns pre-engine cast-cost prompts and deferred cast replay. */
internal class DeferredCastCostInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
    private val autoPassState: ClientAutoPassState,
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
                onAlternateCostChoiceResponse(greMsg, pending, autoPass)
                return true
            }
            is PendingClientInteraction.OptionalCost -> {
                onOptionalCostResponse(greMsg, pending, autoPass)
                return true
            }
            is PendingClientInteraction.HybridManaType -> {
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
        actionToken: ActionToken,
        cardId: ForgeCardId,
        castAbilityIndex: Int?,
        acceptedActionEffects: AcceptedActionEffects,
    ): Boolean {
        if (action.alternativeGrpId != 0) return false
        val bridge = ctx.bridge
        clearDeferredCastCostStashes()

        val facts = bridge.hybridCastCostFacts(counters.seatId, cardId, castAbilityIndex) ?: return false

        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                playerIdToPrompt = counters.seatId.value,
                hybridColors = facts.promptColors,
                manaCost = facts.manaCost,
            )
        setPendingInteraction(
            PendingClientInteraction.HybridManaType(
                pendingActionId = pendingActionId,
                actionToken = actionToken,
                cardId = cardId,
                acceptedActionEffects = acceptedActionEffects,
                clientAction = action,
                castAbilityIndex = castAbilityIndex,
                ctoIds = ctoIds,
                promptColors = facts.promptColors,
                paymentColors = facts.paymentColors,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(ctx.snapshot(), counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} card=${facts.cardName}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkOptionalCosts(
        action: Action,
        pendingActionId: String,
        actionToken: ActionToken,
        cardId: ForgeCardId,
        castAbilityIndex: Int?,
        acceptedActionEffects: AcceptedActionEffects,
        deferredHybridChoices: List<ManaColor> = emptyList(),
        clearExistingStashes: Boolean = true,
    ): Boolean {
        val bridge = ctx.bridge
        if (clearExistingStashes) clearDeferredCastCostStashes()
        val facts = bridge.optionalCastCostFacts(counters.seatId, cardId, castAbilityIndex, action.grpId) ?: return false
        val optionalCostEntries = facts.entries.filter { it.keywordName == null }
        val keywordEntries = facts.entries.filter { it.keywordName != null }

        log.info(
            "DeferredCastCostInteractionHandler: card '{}' has {} optional costs and {} keyword costs — sending prompt",
            facts.cardName,
            optionalCostEntries.size,
            keywordEntries.size,
        )
        val combinedCostEntries = facts.entries.map { it.type to it.abilityGrpId }
        val (ctoReq, costCtoIds) =
            CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                optionalCosts = combinedCostEntries,
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = facts.baseManaCost,
            )
        val keywordCtoIdMap =
            keywordEntries
                .mapIndexed { idx, entry ->
                    val ctoIdx = optionalCostEntries.size + idx
                    val ctoId = costCtoIds.getOrNull(ctoIdx) ?: return@mapIndexed null
                    ctoId to checkNotNull(entry.keywordName)
                }.filterNotNull()
                .toMap()

        setPendingInteraction(
            PendingClientInteraction.OptionalCost(
                pendingActionId = pendingActionId,
                actionToken = actionToken,
                cardId = cardId,
                acceptedActionEffects = acceptedActionEffects,
                costCtoIds = costCtoIds,
                hybridManaChoices = deferredHybridChoices,
                keywordCostsByCtoId = keywordCtoIdMap,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(ctx.snapshot(), counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${counters.seatId} card=${facts.cardName}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkAlternateAdditionalCostChoice(
        action: Action,
        pendingActionId: String,
        cardId: ForgeCardId,
        acceptedActionEffects: AcceptedActionEffects,
    ): Boolean {
        val bridge = ctx.bridge
        val facts = bridge.alternateCastCostFacts(counters.seatId, cardId) ?: return false
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = action.instanceId,
                grpId = action.grpId,
                playerIdToPrompt = counters.seatId.value,
                optionCount = facts.optionCount,
                optionPromptIds = facts.optionPromptIds,
            )
        val commands = bridge.registerAlternateCastCommands(counters.seatId, pendingActionId, cardId, ctoIds) ?: return false
        setPendingInteraction(
            PendingClientInteraction.AlternateCostChoice(
                pendingActionId = pendingActionId,
                cardId = cardId,
                acceptedActionEffects = acceptedActionEffects,
                defaultActionToken = commands.defaultToken,
                actionTokensByCtoId = commands.tokensByCtoId,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(ctx.snapshot(), counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (alternate additional cost) seat=${counters.seatId} card=${facts.cardName}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun clearDeferredCastCostStashes() {
        val journal =
            ctx.bridge
                .seat(counters.seatId)
                .prompt.journal
        journal.clearKeywordCostStash()
        journal.clearHybridManaStash()
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
        val decisions = pending.keywordCostsByCtoId.entries.associate { (ctoId, kwName) -> kwName to (chosenCtoId == ctoId) }
        submitDeferredAction(
            pendingActionId = pending.pendingActionId,
            actionToken = pending.actionToken,
            responseName = "optional cost",
            autoPass = autoPass,
        ) {
            setPendingInteraction(null)
            if (pending.hybridManaChoices.isNotEmpty()) {
                seatBridge.prompt.journal.record(PromptSideEffect.HybridManaStash(pending.hybridManaChoices))
            }
            TargetingHandler.stashOptionalCostIndices(seatBridge.prompt, acceptedIndices)
            if (decisions.isNotEmpty()) {
                seatBridge.prompt.journal.record(PromptSideEffect.KeywordCostStash(decisions))
                log.info("DeferredCastCostInteractionHandler: keyword cost decisions stashed: {}", decisions)
            }
            pending.acceptedActionEffects.apply(autoPassState, bridge)
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
        if (!seatBridge.action.acceptsActionToken(pending.pendingActionId, pending.actionToken)) {
            rejectDeferredResponse("hybrid mana")
            return
        }
        log.info("DeferredCastCostInteractionHandler: hybrid mana type choices accepted: prompt={} payment={}", promptChoices, choices)

        if (
            checkOptionalCosts(
                pending.clientAction,
                pending.pendingActionId,
                pending.actionToken,
                pending.cardId,
                pending.castAbilityIndex,
                pending.acceptedActionEffects,
                deferredHybridChoices = choices,
                clearExistingStashes = false,
            )
        ) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
            return
        }

        submitDeferredAction(
            pendingActionId = pending.pendingActionId,
            actionToken = pending.actionToken,
            responseName = "hybrid mana",
            autoPass = autoPass,
        ) {
            setPendingInteraction(null)
            seatBridge.prompt.journal.record(PromptSideEffect.HybridManaStash(choices))
            pending.acceptedActionEffects.apply(autoPassState, bridge)
        }
    }

    private fun onAlternateCostChoiceResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.AlternateCostChoice,
        autoPass: () -> Unit,
    ) {
        val optionResp = greMsg.castingTimeOptionsResp.castingTimeOptionResp
        val selectedIndex = optionResp?.selectNResp?.idsList?.firstOrNull()
        val chosenCtoId = optionResp?.ctoId ?: 0
        val actionToken =
            selectedIndex?.let { pending.actionTokensByCtoId[it] }
                ?: pending.actionTokensByCtoId[chosenCtoId]
                ?: pending.defaultActionToken
        submitDeferredAction(
            pendingActionId = pending.pendingActionId,
            actionToken = actionToken,
            responseName = "alternate cost choice",
            autoPass = autoPass,
        ) {
            setPendingInteraction(null)
            pending.acceptedActionEffects.apply(autoPassState, ctx.bridge)
        }
    }

    private fun submitDeferredAction(
        pendingActionId: String,
        actionToken: ActionToken,
        responseName: String,
        autoPass: () -> Unit,
        onAccepted: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val submitted =
            bridge
                .seat(counters.seatId)
                .action
                .submitActionToken(pendingActionId, actionToken, onAccepted = onAccepted)
        if (!submitted) {
            rejectDeferredResponse(responseName)
            return
        }
        ctx.engine.awaitPriority()
        autoPass()
    }

    private fun rejectDeferredResponse(responseName: String) {
        log.warn("DeferredCastCostInteractionHandler: {} response does not match its pending engine action", responseName)
        DevCheck.failOnAutoPass { "$responseName response does not match its pending engine action" }
    }

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
}
