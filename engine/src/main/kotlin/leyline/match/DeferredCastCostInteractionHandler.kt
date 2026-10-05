package leyline.match

import leyline.bridge.coord.DeferredCastAdmission
import leyline.bridge.coord.DeferredCastOptionResponse
import leyline.bridge.coord.DeferredCastReceipt
import leyline.bridge.coord.DeferredCastRejection
import leyline.bridge.coord.DeferredCastResponse
import leyline.bridge.coord.MatchActionWindowRuntime
import leyline.game.bundle.CastingTimeOptionsBuilder
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason

/** Owns pre-engine cast-cost prompts and deferred cast replay. */
internal class DeferredCastCostInteractionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
    private val matchId: String,
) {
    private data class OptionalCostPrompt(
        val request: CastingTimeOptionsReq,
        val ctoIds: List<Int>,
    )

    private val log = LoggerFactory.getLogger(DeferredCastCostInteractionHandler::class.java)

    fun onCastingTimeOptions(greMsg: ClientToGREMessage): HandlerResult {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        if (!deferredCast.hasPrompt()) return HandlerResult.NotHandled
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
        return when (admission) {
            is DeferredCastAdmission.Rejected -> {
                ctx.bridge.cutCoordinator.publishIllegalRequest(
                    counters.seatId,
                    greMsg,
                    if (admission.reason ==
                        DeferredCastRejection.Stale
                    ) {
                        FailureReason.ReqRespMismatch
                    } else {
                        FailureReason.InvalidOptionSelection
                    },
                )
                sink.sendPriorityState(ctx.bridge)
                HandlerResult.Waiting
            }
            is DeferredCastAdmission.Optional -> {
                bridgeAfterDeferredResponse()
                HandlerResult.Resume
            }
            is DeferredCastAdmission.ManaAfterOptional -> {
                val plan = deferredCast.deferredCostPlan(admission.receipt)
                checkNotNull(plan) { "Deferred optional action plan unavailable" }
                val (request, ctoIds) = manaPrompt(plan, admission.plan)
                check(deferredCast.publishHybrid(admission.receipt, request, ctoIds, admission.plan)) {
                    "Deferred optional action claim did not publish mana choices"
                }
                sink.sendPriorityState(ctx.bridge)
                HandlerResult.Waiting
            }
            is DeferredCastAdmission.Hybrid -> {
                val plan = deferredCast.deferredCostPlan(admission.receipt)
                if (!admission.optionalSelected &&
                    plan != null &&
                    checkOptionalCosts(admission.receipt, plan, preserveHybridStash = true)
                ) {
                    HandlerResult.Waiting
                } else {
                    check(deferredCast.complete(admission.receipt)) { "Deferred hybrid action claim did not complete" }
                    bridgeAfterDeferredResponse()
                    HandlerResult.Resume
                }
            }
            is DeferredCastAdmission.Alternate -> {
                bridgeAfterDeferredResponse()
                HandlerResult.Resume
            }
        }
    }

    private fun bridgeAfterDeferredResponse() = Unit

    fun checkHybridManaTypeOptions(actionClaim: MatchActionWindowRuntime.ActionClaim): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        if (plan.manaAfterOptional) return false
        val hybrid = plan.hybrid ?: return false
        val (ctoReq, ctoIds) = manaPrompt(plan, hybrid)
        ctx.bridge.cutCoordinator.deferredCast.publishHybrid(
            claim = actionClaim,
            request = ctoReq,
            ctoIds = ctoIds,
            plan = hybrid,
        )

        sink.sendPriorityState(ctx.bridge)
        Tap.outboundTemplate(
            "casting_time_options_hybrid_mana",
            matchId = matchId,
            seat = counters.seatId.value,
        )
        return true
    }

    private fun manaPrompt(
        plan: leyline.bridge.handoff.DeferredCastCostPlan,
        hybrid: leyline.bridge.handoff.DeferredCastCostPlan.HybridManaPlan,
    ): Pair<CastingTimeOptionsReq, List<Int>> =
        CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
            instanceId = plan.instanceId,
            grpId = plan.grpId,
            playerIdToPrompt = counters.seatId.value,
            hybridColors = hybrid.promptColors,
            manaCost = hybrid.manaCost,
            alternatives = hybrid.alternatives,
        )

    fun checkOptionalCosts(
        actionClaim: MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        val prompt = prepareOptionalCosts(plan) ?: return false
        deferredCast.publishOptional(actionClaim, prompt.request, prompt.ctoIds, preserveHybridStash)
        deliverOptionalPrompt()
        return true
    }

    private fun checkOptionalCosts(
        receipt: DeferredCastReceipt,
        plan: leyline.bridge.handoff.DeferredCastCostPlan,
        preserveHybridStash: Boolean,
    ): Boolean {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        val prompt = prepareOptionalCosts(plan) ?: return false
        if (!deferredCast.publishOptional(receipt, prompt.request, prompt.ctoIds, preserveHybridStash)) return false
        deliverOptionalPrompt()
        return true
    }

    private fun prepareOptionalCosts(plan: leyline.bridge.handoff.DeferredCastCostPlan): OptionalCostPrompt? {
        val optional = plan.optional ?: return null

        log.info(
            "DeferredCastCostInteractionHandler: grpId={} has {} deferred cost choices — sending prompt",
            plan.grpId,
            optional.entries.size,
        )
        val (ctoReq, costCtoIds) =
            CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                optionalCosts = optional.entries,
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = optional.baseManaCost,
                presentation = ctx.bridge.engineSettings.costChoicePresentation,
            )
        return OptionalCostPrompt(ctoReq, costCtoIds)
    }

    private fun deliverOptionalPrompt() {
        sink.sendPriorityState(ctx.bridge)
        Tap.outboundTemplate(
            "casting_time_options_optional_costs",
            matchId = matchId,
            seat = counters.seatId.value,
        )
    }

    fun checkAlternateAdditionalCostChoice(actionClaim: MatchActionWindowRuntime.ActionClaim): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val alternate = plan.alternate ?: return false
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                grpId = plan.grpId,
                playerIdToPrompt = counters.seatId.value,
                choices = alternate.choices,
                presentation = ctx.bridge.engineSettings.costChoicePresentation,
            )
        ctx.bridge.cutCoordinator.deferredCast.publishAlternate(
            claim = actionClaim,
            request = ctoReq,
            ctoIds = ctoIds,
        )

        sink.sendPriorityState(ctx.bridge)
        Tap.outboundTemplate(
            "casting_time_options_alternate_cost",
            matchId = matchId,
            seat = counters.seatId.value,
        )
        return true
    }
}
