package leyline.match

import leyline.bridge.coord.MatchActionWindowRuntime
import leyline.bridge.handoff.PromptSideEffect
import leyline.game.bundle.CastingTimeOptionsBuilder
import org.slf4j.LoggerFactory
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
                withClaim(pending.actionClaim) { complete -> onAlternateCostChoiceResponse(greMsg, pending, autoPass, complete) }
                return true
            }
            is PendingClientInteraction.OptionalCost -> {
                setPendingInteraction(null)
                withClaim(pending.actionClaim) { complete -> onOptionalCostResponse(greMsg, pending, autoPass, complete) }
                return true
            }
            is PendingClientInteraction.HybridManaType -> {
                setPendingInteraction(null)
                withClaim(pending.actionClaim) { complete -> onHybridManaTypeResponse(greMsg, pending, autoPass, complete) }
                return true
            }
            null,
            -> return false
        }
    }

    fun checkHybridManaTypeOptions(actionClaim: MatchActionWindowRuntime.ActionClaim): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val hybrid = plan.hybrid ?: return false
        val bridge = ctx.bridge
        val game = ctx.game
        val seatBridge = bridge.seat(counters.seatId)
        seatBridge.prompt.journal.clearHybridManaStash()

        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                grpId = plan.grpId,
                playerIdToPrompt = counters.seatId.value,
                hybridColors = hybrid.promptColors,
                manaCost = hybrid.manaCost,
            )
        setPendingInteraction(
            PendingClientInteraction.HybridManaType(
                actionClaim = actionClaim,
                ctoIds = ctoIds,
                promptColors = hybrid.promptColors,
                paymentColors = hybrid.paymentColors,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkOptionalCosts(
        actionClaim: MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val optional = plan.optional ?: return false
        val game = ctx.game
        clearDeferredCastCostStashes(clearHybrid = !preserveHybridStash)

        log.info(
            "DeferredCastCostInteractionHandler: grpId={} has {} deferred cost choices — sending prompt",
            plan.grpId,
            optional.entries.size,
        )
        val (ctoReq, costCtoIds) =
            CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                optionalCosts = optional.entries.map { it.type to it.abilityGrpId },
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = optional.baseManaCost,
            )
        val keywordCtoIdMap =
            optional.entries
                .mapIndexedNotNull { index, entry -> entry.keywordName?.let { costCtoIds[index] to it } }
                .toMap()

        setPendingInteraction(
            PendingClientInteraction.OptionalCost(
                actionClaim = actionClaim,
                costCtoIds = costCtoIds,
                keywordCostsByCtoId = keywordCtoIdMap,
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkAlternateAdditionalCostChoice(actionClaim: MatchActionWindowRuntime.ActionClaim): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val alternate = plan.alternate ?: return false
        val game = ctx.game
        val optionPromptIds = alternate.choices.map { it.promptId }
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                grpId = plan.grpId,
                playerIdToPrompt = counters.seatId.value,
                optionCount = alternate.choices.size,
                optionPromptIds = if (optionPromptIds.all { it != null }) optionPromptIds.filterNotNull() else emptyList(),
            )
        setPendingInteraction(
            PendingClientInteraction.AlternateCostChoice(
                actionClaim = actionClaim,
                runtimeTokensByCtoId = ctoIds.mapIndexed { index, ctoId -> ctoId to alternate.choices[index].runtimeToken }.toMap(),
            ),
        )

        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        Tap.outboundTemplate("CastingTimeOptionsReq (alternate additional cost) seat=${counters.seatId} grpId=${plan.grpId}")
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
        complete: (Long?) -> Unit,
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

        complete(null)
        bridge.awaitPriority()
        autoPass()
    }

    private fun onHybridManaTypeResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.HybridManaType,
        autoPass: () -> Unit,
        complete: (Long?) -> Unit,
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

        if (checkOptionalCosts(pending.actionClaim, preserveHybridStash = true)) {
            Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
            return
        }

        complete(null)
        bridge.awaitPriority()
        autoPass()
    }

    private fun onAlternateCostChoiceResponse(
        greMsg: ClientToGREMessage,
        pending: PendingClientInteraction.AlternateCostChoice,
        autoPass: () -> Unit,
        complete: (Long?) -> Unit,
    ) {
        val bridge = ctx.bridge
        val optionResp = greMsg.castingTimeOptionsResp.castingTimeOptionResp
        val selectedCtoId = optionResp?.selectNResp?.idsList?.firstOrNull()
        val chosenCtoId = optionResp?.ctoId ?: 0
        val runtimeToken = selectedCtoId?.let { pending.runtimeTokensByCtoId[it] } ?: pending.runtimeTokensByCtoId[chosenCtoId]
        checkNotNull(runtimeToken) { "Alternate-cost response did not select a runtime token" }
        complete(runtimeToken)
        bridge.awaitPriority()
        autoPass()
    }

    private inline fun withClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        block: ((Long?) -> Unit) -> Unit,
    ) {
        var completed = false
        try {
            block { childToken ->
                check(ctx.bridge.cutCoordinator.completeActionClaim(claim, childToken)) { "Deferred action claim did not complete" }
                completed = true
            }
        } catch (ex: Exception) {
            if (completed) {
                ctx.bridge.cutCoordinator.fail(ex)
            } else {
                ctx.bridge.cutCoordinator.failActionClaim(claim, ex)
            }
        }
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
