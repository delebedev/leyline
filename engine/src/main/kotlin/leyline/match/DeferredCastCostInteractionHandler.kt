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
            is DeferredCastAdmission.Hybrid -> {
                val plan = deferredCast.deferredCostPlan(admission.receipt)
                if (plan != null && checkOptionalCosts(admission.receipt, plan, preserveHybridStash = true)) {
                    Tap.outboundTemplate("Cast deferred — optional cost prompt sent after hybrid mana type")
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
        val hybrid = plan.hybrid ?: return false
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                grpId = plan.grpId,
                playerIdToPrompt = counters.seatId.value,
                hybridColors = hybrid.promptColors,
                manaCost = hybrid.manaCost,
            )
        ctx.bridge.cutCoordinator.deferredCast.publishHybrid(
            claim = actionClaim,
            request = ctoReq,
            ctoIds = ctoIds,
            promptColors = hybrid.promptColors,
            paymentColors = hybrid.paymentColors,
        )

        Tap.outboundTemplate("CastingTimeOptionsReq (hybrid mana type) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendPriorityState(ctx.bridge)
        return true
    }

    fun checkOptionalCosts(
        actionClaim: MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        val prompt = prepareOptionalCosts(plan) ?: return false
        deferredCast.publishOptional(actionClaim, prompt.request, prompt.ctoIds, preserveHybridStash)
        deliverOptionalPrompt(plan)
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
        deliverOptionalPrompt(plan)
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
                optionalCosts = optional.entries.map { it.type to it.abilityGrpId },
                playerIdToPrompt = counters.seatId.value,
                baseManaCost = optional.baseManaCost,
            )
        return OptionalCostPrompt(ctoReq, costCtoIds)
    }

    private fun deliverOptionalPrompt(plan: leyline.bridge.handoff.DeferredCastCostPlan) {
        Tap.outboundTemplate("CastingTimeOptionsReq (optional costs) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendPriorityState(ctx.bridge)
    }

    fun checkAlternateAdditionalCostChoice(actionClaim: MatchActionWindowRuntime.ActionClaim): Boolean {
        val plan = actionClaim.deferredCostPlan ?: return false
        val alternate = plan.alternate ?: return false
        val optionPromptIds = alternate.choices.map { it.promptId }
        val (ctoReq, ctoIds) =
            CastingTimeOptionsBuilder.buildChooseOrCostCastingTimeOptionsReq(
                instanceId = plan.instanceId,
                grpId = plan.grpId,
                playerIdToPrompt = counters.seatId.value,
                optionCount = alternate.choices.size,
                optionPromptIds = if (optionPromptIds.all { it != null }) optionPromptIds.filterNotNull() else emptyList(),
            )
        ctx.bridge.cutCoordinator.deferredCast.publishAlternate(
            claim = actionClaim,
            request = ctoReq,
            ctoIds = ctoIds,
        )

        Tap.outboundTemplate("CastingTimeOptionsReq (alternate additional cost) seat=${counters.seatId} grpId=${plan.grpId}")
        sink.sendPriorityState(ctx.bridge)
        return true
    }
}
