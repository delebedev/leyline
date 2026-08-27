package leyline.match

import leyline.bridge.coord.DeferredCastAdmission
import leyline.bridge.coord.DeferredCastOptionResponse
import leyline.bridge.coord.DeferredCastReceipt
import leyline.bridge.coord.DeferredCastRejection
import leyline.bridge.coord.DeferredCastResponse
import leyline.bridge.coord.MatchActionWindowRuntime
import leyline.game.bundle.CastingTimeOptionsBuilder
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason

/** Owns pre-engine cast-cost prompts and deferred cast replay. */
internal class DeferredCastCostInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(DeferredCastCostInteractionHandler::class.java)

    fun onCastingTimeOptions(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ): Boolean {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        if (!deferredCast.hasPrompt()) return false
        val resp = greMsg.castingTimeOptionsResp
        val optionResponses =
            if (resp.castingTimeOptionRespsCount > 0) {
                resp.castingTimeOptionRespsList
            } else {
                listOf(resp.castingTimeOptionResp)
            }
        val admission =
            deferredCast.admit(
                DeferredCastResponse(
                    gameStateId = greMsg.gameStateId,
                    ctoId = resp.castingTimeOptionResp?.ctoId ?: 0,
                    selectedCtoId =
                        resp.castingTimeOptionResp
                            ?.selectNResp
                            ?.idsList
                            ?.firstOrNull(),
                    options =
                        optionResponses.map { option ->
                            DeferredCastOptionResponse(
                                ctoId = option.ctoId,
                                manaColor = option.selectManaTypeResp.takeIf { option.hasSelectManaTypeResp() }?.manaColor,
                            )
                        },
                ),
            )
        when (admission) {
            is DeferredCastAdmission.Rejected -> {
                ResponseEnvelopeGuard.reject(
                    greMsg,
                    if (admission.reason == DeferredCastRejection.Stale) {
                        FailureReason.ReqRespMismatch
                    } else {
                        FailureReason.InvalidOptionSelection
                    },
                    counters.counter,
                    sink,
                )
            }
            is DeferredCastAdmission.Optional -> {
                bridgeAfterDeferredResponse(autoPass)
            }
            is DeferredCastAdmission.Hybrid -> {
                val plan = deferredCast.deferredCostPlan(admission.receipt)
                if (plan != null && checkOptionalCosts(admission.receipt, plan, preserveHybridStash = true)) {
                    Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
                } else {
                    check(deferredCast.complete(admission.receipt)) { "Deferred hybrid action claim did not complete" }
                    bridgeAfterDeferredResponse(autoPass)
                }
            }
            is DeferredCastAdmission.Alternate -> {
                bridgeAfterDeferredResponse(autoPass)
            }
        }
        return true
    }

    private fun bridgeAfterDeferredResponse(autoPass: () -> Unit) {
        ctx.bridge.awaitPriority()
        autoPass()
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
        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        ctx.bridge.cutCoordinator.deferredCast.publishHybrid(
            claim = actionClaim,
            promptGameStateId = result.messages.first { it.hasCastingTimeOptionsReq() }.gameStateId,
            ctoIds = ctoIds,
            promptColors = hybrid.promptColors,
            paymentColors = hybrid.paymentColors,
        )

        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendBundledGRE(result.messages)
        return true
    }

    fun checkOptionalCosts(
        actionClaim: MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        return publishOptionalCosts(plan, preserveHybridStash) { gameStateId, ctoIds ->
            deferredCast.publishOptional(actionClaim, gameStateId, ctoIds)
            true
        }
    }

    private fun checkOptionalCosts(
        receipt: DeferredCastReceipt,
        plan: leyline.bridge.handoff.DeferredCastCostPlan,
        preserveHybridStash: Boolean,
    ): Boolean {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        return publishOptionalCosts(plan, preserveHybridStash) { gameStateId, ctoIds ->
            deferredCast.publishOptional(receipt, gameStateId, ctoIds)
        }
    }

    private fun publishOptionalCosts(
        plan: leyline.bridge.handoff.DeferredCastCostPlan,
        preserveHybridStash: Boolean,
        publish: (Int, List<Int>) -> Boolean,
    ): Boolean {
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
        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        val promptGameStateId = result.messages.first { it.hasCastingTimeOptionsReq() }.gameStateId
        if (!publish(promptGameStateId, costCtoIds)) return false

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
        val result = bundles.bundleBuilder.castingTimeOptionsBundle(game, counters.counter, ctoReq)
        ctx.bridge.cutCoordinator.deferredCast.publishAlternate(
            claim = actionClaim,
            promptGameStateId = result.messages.first { it.hasCastingTimeOptionsReq() }.gameStateId,
            ctoIds = ctoIds,
        )

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
}
