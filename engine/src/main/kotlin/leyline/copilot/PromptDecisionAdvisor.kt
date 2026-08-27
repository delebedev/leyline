package leyline.copilot

import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

/** Where a complete desired decision came from. */
internal enum class PromptDecisionSource {
    ForgeAi,
    Default,
}

/** Why a Forge-AI-owned prompt family could not produce a desired decision. */
internal enum class PromptUnavailableReason {
    UnsupportedPrompt,
    NoForgeChoice,
    RejectedAttempt,
    ConsultFailed,
}

internal sealed interface PromptDecisionResult {
    val forgeAiAttempted: Boolean

    data class Chosen(
        val decision: SimDecision,
        val source: PromptDecisionSource,
        override val forgeAiAttempted: Boolean = source == PromptDecisionSource.ForgeAi,
    ) : PromptDecisionResult

    data class Unavailable(
        val reason: PromptUnavailableReason,
        val detail: String,
        override val forgeAiAttempted: Boolean = false,
    ) : PromptDecisionResult
}

/**
 * Read-only prompt decision seam shared by simclient and Copilot.
 *
 * The advisor chooses a complete desired response. Simclient submits that
 * response as a whole, while Copilot diffs it against the host's committed
 * state and delivers one native response at a time. Host strategy, retry
 * suppression, and response delivery stay outside this class.
 */
internal class PromptDecisionAdvisor(
    private val forgeAi: ForgeAiPolicy,
) {
    fun decide(
        prompt: GREToClientMessage,
        context: PromptDecisionContext = PromptDecisionContext(),
    ): PromptDecisionResult =
        try {
            decideSafely(prompt, context)
        } catch (t: Throwable) {
            PromptDecisionResult.Unavailable(
                reason = PromptUnavailableReason.ConsultFailed,
                detail = "${t::class.simpleName}: ${t.message ?: "no message"}",
                forgeAiAttempted = forgeAiAttemptedFor(prompt),
            )
        }

    @Suppress("CyclomaticComplexMethod")
    private fun decideSafely(
        prompt: GREToClientMessage,
        context: PromptDecisionContext,
    ): PromptDecisionResult =
        when (prompt.type) {
            GREMessageType.MulliganReq_aa0d -> defaulted(SimDecision.KeepHand)

            GREMessageType.ActionsAvailableReq_695e -> chooseAar(prompt, context)

            GREMessageType.SelectTargetsReq_695e ->
                forgeAi.chooseSelectTargets(prompt)?.let { desired ->
                    if (TargetSelectionDiff.isValid(prompt.selectTargetsReq, desired)) {
                        forgeChosen(SimDecision.SelectTargets(desired))
                    } else {
                        unavailable(
                            PromptUnavailableReason.NoForgeChoice,
                            "Forge AI returned an invalid grouped target plan",
                            forgeAiAttempted = true,
                        )
                    }
                } ?: unavailable(
                    PromptUnavailableReason.NoForgeChoice,
                    "Forge AI returned no grouped target plan",
                    forgeAiAttempted = true,
                )

            GREMessageType.SelectNreq ->
                forgeAi.chooseSelectN(prompt.selectNReq)?.let { forgeChosen(SimDecision.SelectN(it)) }
                    ?: forgeAi.chooseStaticColorSelectN(prompt)?.let { forgeChosen(SimDecision.SelectN(it)) }
                    ?: defaulted(DefaultDecisions.selectN(prompt), forgeAiAttempted = true)

            GREMessageType.CastingTimeOptionsReq_695e ->
                forgeAi.chooseCastingTimeOptions(prompt)?.let(::forgeChosen)
                    ?: defaulted(DefaultDecisions.castingTimeOptions(prompt), forgeAiAttempted = true)

            GREMessageType.OrderReq_695e -> defaulted(DefaultDecisions.order(prompt))

            GREMessageType.SearchReq_695e ->
                defaulted(
                    context.board?.let { board ->
                        chooseBoardAwareSearch(prompt, board)?.let(SimDecision::Search)
                    } ?: DefaultDecisions.search(prompt),
                )

            GREMessageType.SearchFromGroupsReq_695e -> defaulted(DefaultDecisions.groupedSearch(prompt))

            GREMessageType.NumericInputReq_695e -> defaulted(DefaultDecisions.numericInput(prompt))

            GREMessageType.DistributionReq_695e ->
                DefaultDecisions.forcedDistribution(prompt)?.let(::defaulted)
                    ?: unavailable(PromptUnavailableReason.UnsupportedPrompt, "distribution is not forced")

            GREMessageType.PayCostsReq_695e -> choosePayCosts(prompt)

            GREMessageType.GroupReq_695e ->
                defaulted(
                    context.board?.let { board ->
                        chooseBoardAwareGroupAway(prompt, board)?.let { awayIds ->
                            SimDecision.GroupAway(
                                awayInstanceIds = awayIds,
                                allInstanceIds = prompt.groupReq.instanceIdsList,
                                context = prompt.groupReq.context,
                            )
                        }
                    } ?: DefaultDecisions.group(prompt),
                )

            GREMessageType.DeclareAttackersReq_695e ->
                forgeAi.chooseAttackers()?.let { forgeChosen(SimDecision.DeclareAttackers(it)) }
                    ?: unavailable(
                        PromptUnavailableReason.NoForgeChoice,
                        "Forge AI declared no attackers",
                        forgeAiAttempted = true,
                    )

            GREMessageType.AssignDamageReq_695e -> defaulted(DefaultDecisions.assignDamage(prompt))

            GREMessageType.DeclareBlockersReq_695e ->
                forgeAi.chooseBlockers(prompt)?.let { forgeChosen(SimDecision.DeclareBlockers(it)) }
                    ?: defaulted(SimDecision.DeclareNoBlockers, forgeAiAttempted = true)

            GREMessageType.OptionalActionMessage_695e -> defaulted(DefaultDecisions.optionalAction())

            else -> unavailable(PromptUnavailableReason.UnsupportedPrompt, "no advisor route for ${prompt.type}")
        }

    private fun chooseAar(
        prompt: GREToClientMessage,
        context: PromptDecisionContext,
    ): PromptDecisionResult {
        val actions = prompt.actionsAvailableReq.actionsList
        val choice = forgeAi.chooseAarAction(actions, context.isSkippedAction)
        if (choice != null) return forgeChosen(SimDecision.PerformAction(choice.action))

        val actionable = actions.filter(Action::isAdvisorActionable)
        val rejected = actionable.isNotEmpty() && actionable.all(context.isSkippedAction)
        return unavailable(
            if (rejected) PromptUnavailableReason.RejectedAttempt else PromptUnavailableReason.NoForgeChoice,
            if (rejected) {
                "all Forge-AI action candidates were suppressed after an attempted response"
            } else {
                "Forge AI returned no available action"
            },
            forgeAiAttempted = true,
        )
    }

    private fun choosePayCosts(prompt: GREToClientMessage): PromptDecisionResult {
        if (prompt.payCostsReq.autoTapActionsReq.autoTapSolutionsCount > 0) {
            return defaulted(SimDecision.AutoTapPayment(0))
        }
        return forgeAi.chooseEffectCostPayment(prompt)?.let { forgeChosen(SimDecision.EffectCost(it)) }
            ?: unavailable(
                PromptUnavailableReason.NoForgeChoice,
                "Forge AI returned no effect-cost selection",
                forgeAiAttempted = true,
            )
    }

    private fun defaulted(
        decision: SimDecision,
        forgeAiAttempted: Boolean = false,
    ): PromptDecisionResult.Chosen = PromptDecisionResult.Chosen(decision, PromptDecisionSource.Default, forgeAiAttempted)

    private fun forgeChosen(decision: SimDecision): PromptDecisionResult.Chosen =
        PromptDecisionResult.Chosen(decision, PromptDecisionSource.ForgeAi)

    private fun unavailable(
        reason: PromptUnavailableReason,
        detail: String,
        forgeAiAttempted: Boolean = false,
    ): PromptDecisionResult.Unavailable = PromptDecisionResult.Unavailable(reason, detail, forgeAiAttempted)

    private fun forgeAiAttemptedFor(prompt: GREToClientMessage): Boolean =
        when (prompt.type) {
            GREMessageType.ActionsAvailableReq_695e,
            GREMessageType.SelectTargetsReq_695e,
            GREMessageType.SelectNreq,
            GREMessageType.CastingTimeOptionsReq_695e,
            GREMessageType.DeclareAttackersReq_695e,
            GREMessageType.DeclareBlockersReq_695e,
            -> true
            GREMessageType.PayCostsReq_695e -> prompt.payCostsReq.autoTapActionsReq.autoTapSolutionsCount == 0
            else -> false
        }

    companion object {
        internal fun chooseBoardAwareSearchIds(
            prompt: GREToClientMessage,
            board: PromptDecisionBoard,
        ): List<Int>? = chooseBoardAwareSearch(prompt, board)

        internal fun chooseBoardAwareGroupAwayIds(
            prompt: GREToClientMessage,
            board: PromptDecisionBoard,
        ): List<Int>? = chooseBoardAwareGroupAway(prompt, board)

        private fun chooseBoardAwareSearch(
            prompt: GREToClientMessage,
            board: PromptDecisionBoard,
        ): List<Int>? {
            val req = prompt.searchReq
            val max = if (req.maxFind > 0) req.maxFind else req.minFind
            val count = max.coerceAtLeast(req.minFind).coerceAtLeast(1)
            val soughtIds = req.itemsSoughtList
            if (soughtIds.isEmpty()) return null
            val candidates = soughtIds.mapNotNull { id -> board.objects[id]?.let { SearchCandidate(id, it) } }
            if (candidates.size != soughtIds.size) return null
            val chooserSeat = board.objects[req.sourceId]?.controllerSeatId.takeIf { it != 0 } ?: 1
            val battlefieldLands = board.objects.values.count { it.isBattlefieldLand(chooserSeat) }
            val originalOrder = soughtIds.withIndex().associate { it.value to it.index }
            return candidates
                .sortedWith(
                    compareByDescending<SearchCandidate> { searchPriority(it.objectInfo, battlefieldLands) }
                        .thenBy { originalOrder[it.instanceId] ?: Int.MAX_VALUE },
                ).take(count)
                .map { it.instanceId }
        }

        private fun chooseBoardAwareGroupAway(
            prompt: GREToClientMessage,
            board: PromptDecisionBoard,
        ): List<Int>? {
            val req = prompt.groupReq
            if (req.context != GroupingContext.Scry_a0f6 && req.context != GroupingContext.Surveil) return null
            val ids = req.instanceIdsList
            if (ids.isEmpty()) return null
            val chooserSeat = board.objects[req.sourceId]?.controllerSeatId.takeIf { it != 0 } ?: 1
            val battlefieldLands = board.objects.values.count { it.isBattlefieldLand(chooserSeat) }
            return ids.filter { id ->
                board.objects[id]?.let { searchPriority(it, battlefieldLands) == GROUP_AWAY_PRIORITY } == true
            }
        }

        private data class SearchCandidate(
            val instanceId: Int,
            val objectInfo: GameObjectInfo,
        )

        private fun searchPriority(
            objectInfo: GameObjectInfo,
            battlefieldLands: Int,
        ): Int =
            when {
                CardType.Land_a80b in objectInfo.cardTypesList -> if (battlefieldLands < SEARCH_LAND_FLOOR) 300 else 50
                CardType.Creature in objectInfo.cardTypesList -> 200 + objectInfo.creatureScore()
                else -> 100
            }

        private fun GameObjectInfo.isBattlefieldLand(seat: Int): Boolean =
            zoneId == ZoneIds.BATTLEFIELD && controllerSeatId == seat && CardType.Land_a80b in cardTypesList

        private fun GameObjectInfo.creatureScore(): Int =
            (if (hasPower()) power.value else 0) + (if (hasToughness()) toughness.value else 0)

        private const val SEARCH_LAND_FLOOR = 4
        private const val GROUP_AWAY_PRIORITY = 50
    }
}

internal data class PromptDecisionContext(
    val isSkippedAction: (Action) -> Boolean = { false },
    val board: PromptDecisionBoard? = null,
)

internal data class PromptDecisionBoard(
    val objects: Map<Int, GameObjectInfo>,
)

private fun Action.isAdvisorActionable(): Boolean =
    actionType.isCopilotCastOffer() ||
        actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3 ||
        actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Activate_add3
