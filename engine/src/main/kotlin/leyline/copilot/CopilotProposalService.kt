package leyline.copilot

import forge.game.card.Card
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Turns the pending prompt a seat is facing into a [CopilotProposal] — the
 * Forge-AI decision brain's answer, expressed as an autoplay intent a client
 * driver can realise. Read-only: consulting the AI never submits on the seat's
 * behalf, so a live match can be observed without displacing the client.
 *
 * Every consult is wrapped so a missing game, an unmapped decision, or a Forge
 * internal throwing degrades to an `unrealizable` proposal instead of an error
 * — the loop consumes those as gesture-layer gaps rather than crashing.
 */
class CopilotProposalService(
    private val bridge: GameBridge,
    private val seatId: SeatId,
) {
    private val policy = ForgeAiPolicy({ bridge }, seatId)
    private val advisor = PromptDecisionAdvisor(policy)
    private val resolver = EntityResolver(::resolveEntity)

    /** Forge-AI heuristic position score for the seat; null when eval fails. */
    fun evaluate(): EvalScore? = policy.evaluateGameState()?.let { EvalScore(it.value, it.availableValue) }

    /** Complete Copilot-host decision before native realization. */
    internal fun decide(prompt: GREToClientMessage): PromptDecisionResult {
        val result = advisor.decide(prompt)
        if (result is PromptDecisionResult.Chosen || prompt.type != GREMessageType.ActionsAvailableReq_695e) return result
        val proactive = policy.chooseMain2ProactivePermanent(prompt.actionsAvailableReq.actionsList) ?: return result
        return PromptDecisionResult.Chosen(
            decision = SimDecision.PerformAction(proactive.action),
            source = PromptDecisionSource.CopilotSafeguard,
            forgeAiAttempted = result.forgeAiAttempted,
        )
    }

    /** Propose a response for [prompt]; null / uncovered / failed consults yield `unrealizable`. */
    fun propose(prompt: GREToClientMessage?): CopilotProposal {
        if (prompt == null) {
            return CopilotProposalRealizer.unrealizable(
                GREMessageType.PromptReq,
                seatId.value,
                "no pending prompt for seat ${seatId.value}",
            )
        }
        return runCatching { route(prompt) }
            .getOrElse { t ->
                log.warn("copilot proposal for {} failed: {}", prompt.type, t.message, t)
                CopilotProposalRealizer.unrealizable(prompt.type, seatId.value, "consult failed: ${t.message}")
            }
    }

    // GREMessageType is a large proto enum; only these families are decoded and
    // the else fallthrough to `unrealizable` is intentional, not a gap.
    @Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
    private fun route(prompt: GREToClientMessage): CopilotProposal =
        when (prompt.type) {
            // Opening-hand keep. Scripted skip-mulligan puzzles never emit this,
            // so it only fires on the live-client path.
            GREMessageType.MulliganReq_aa0d -> advisedProposal(prompt)

            GREMessageType.ChooseStartingPlayerReq_695e -> startingPlayerProposal(prompt)

            GREMessageType.ActionsAvailableReq_695e -> aarProposal(prompt)

            // Two-round-trip targeting: diff the prompt's committed picks
            // against the AI's desired set — select the missing, Submit (stamped
            // with this prompt's msgId) once they match. On the fully-committed
            // re-prompt the AI can no longer re-pick (targets echo as Unselect),
            // so an already-satisfied committed set stands as the desired set.
            GREMessageType.SelectTargetsReq_695e -> targetProposal(prompt)

            GREMessageType.SelectNreq -> advisedProposal(prompt)

            GREMessageType.CastingTimeOptionsReq_695e -> advisedProposal(prompt)

            GREMessageType.OrderReq_695e -> advisedProposal(prompt)

            GREMessageType.SearchReq_695e -> advisedProposal(prompt)

            GREMessageType.SearchFromGroupsReq_695e -> advisedProposal(prompt)

            GREMessageType.SelectReplacementReq_695e -> advisedProposal(prompt)

            GREMessageType.NumericInputReq_695e -> advisedProposal(prompt)

            GREMessageType.DistributionReq_695e -> proposalFor(DefaultDecisions.distribution(prompt), prompt)

            GREMessageType.PayCostsReq_695e ->
                advisedProposal(prompt)

            // Scry/surveil ordering: keep everything on top (always legal, moves
            // nothing) — enough to never stall the loop on this family.
            GREMessageType.GroupReq_695e -> advisedProposal(prompt)

            // Two-round-trip declaration: diff the prompt's committed set
            // against the AI's desired set — one toggle per consult, Submit
            // (stamped with this prompt's msgId) once they match.
            GREMessageType.DeclareAttackersReq_695e -> attackersProposal(prompt)

            // Combat damage assignment: echo the engine's pre-filled per-blocker
            // lethal (+ trample overflow to the player) so the attack resolves.
            GREMessageType.AssignDamageReq_695e -> advisedProposal(prompt)

            // Send the complete blocker plan once. A re-prompt carrying any
            // committed assignment is the accepted echo and submits without
            // consulting the AI against its own intermediate declaration.
            GREMessageType.DeclareBlockersReq_695e -> blockersProposal(prompt)

            GREMessageType.OptionalActionMessage_695e -> advisedProposal(prompt)

            else -> CopilotProposalRealizer.unrealizable(prompt.type, seatId.value, "prompt type ${prompt.type} has no copilot decoder")
        }

    private fun advisedProposal(prompt: GREToClientMessage): CopilotProposal =
        when (val result = advisor.decide(prompt)) {
            is PromptDecisionResult.Chosen -> proposalFor(result.decision, prompt)
            is PromptDecisionResult.Unavailable -> unavailableProposal(prompt, result)
        }

    private fun startingPlayerProposal(prompt: GREToClientMessage): CopilotProposal =
        stampPrompt(
            CopilotProposalRealizer.chooseStartingPlayer(
                promptType = prompt.type,
                seat = seatId.value,
                gsId = prompt.gameStateId,
                respId = prompt.msgId,
            ),
            prompt,
        )

    private fun aarProposal(prompt: GREToClientMessage): CopilotProposal {
        val result = decide(prompt)
        if (result is PromptDecisionResult.Chosen) return proposalFor(result.decision, prompt)
        return unavailableProposal(prompt, result as PromptDecisionResult.Unavailable)
    }

    private fun targetProposal(prompt: GREToClientMessage): CopilotProposal {
        val result = advisor.decide(prompt)
        if (result is PromptDecisionResult.Unavailable) return unavailableProposal(prompt, result)
        val desired =
            (result as PromptDecisionResult.Chosen).decision as? SimDecision.SelectTargets
                ?: return CopilotProposalRealizer.unrealizable(prompt.type, seatId.value, "advisor returned a non-target decision")
        val req = prompt.selectTargetsReq
        val committed = TargetSelectionDiff.committedTargets(req)
        val step =
            TargetSelectionDiff.step(req = req, committed = committed, desired = desired.targetGroups)
                ?: return CopilotProposalRealizer.unrealizable(
                    prompt.type,
                    seatId.value,
                    "advisor target plan cannot converge from committed groups",
                )
        return proposalFor(step, prompt)
    }

    private fun attackersProposal(prompt: GREToClientMessage): CopilotProposal {
        val result = advisor.decide(prompt)
        if (result is PromptDecisionResult.Unavailable) return unavailableProposal(prompt, result)
        val decision =
            (result as PromptDecisionResult.Chosen).decision as? SimDecision.DeclareAttackers
                ?: return CopilotProposalRealizer.unrealizable(prompt.type, seatId.value, "advisor returned a non-attacker decision")
        val req = prompt.declareAttackersReq
        val step =
            CombatDeclarationDiff.attackerStep(
                committed = CombatDeclarationDiff.committedAttackers(req),
                desired = CombatDeclarationDiff.qualifiedDesiredAttackers(req, decision.attackerInstanceIds.toSet()),
            )
        return proposalFor(step, prompt)
    }

    private fun blockersProposal(prompt: GREToClientMessage): CopilotProposal {
        val result = advisor.decide(prompt)
        if (result is PromptDecisionResult.Unavailable) return unavailableProposal(prompt, result)
        val decision = (result as PromptDecisionResult.Chosen).decision
        if (decision == SimDecision.DeclareNoBlockers) return proposalFor(decision, prompt)
        val blockers =
            decision as? SimDecision.DeclareBlockers
                ?: return CopilotProposalRealizer.unrealizable(prompt.type, seatId.value, "advisor returned a non-blocker decision")
        val req = prompt.declareBlockersReq
        val step =
            CombatDeclarationDiff.blockerStep(
                committed = CombatDeclarationDiff.committedBlocks(req),
                desired = CombatDeclarationDiff.qualifiedDesiredBlocks(req, blockers.assignments),
            )
        return proposalFor(step, prompt)
    }

    private fun unavailableProposal(
        prompt: GREToClientMessage,
        result: PromptDecisionResult.Unavailable,
    ): CopilotProposal {
        val fallback =
            when {
                result.reason !in setOf(PromptUnavailableReason.NoForgeChoice, PromptUnavailableReason.RejectedAttempt) -> null
                prompt.type == GREMessageType.ActionsAvailableReq_695e -> SimDecision.PassPriority
                prompt.type == GREMessageType.PayCostsReq_695e &&
                    prompt.payCostsReq.autoTapActionsReq.autoTapSolutionsCount == 0 -> SimDecision.CancelAction
                else -> null
            }
        return fallback?.let { proposalFor(it, prompt) }
            ?: CopilotProposalRealizer.unrealizable(
                prompt.type,
                seatId.value,
                "advisor unavailable: ${result.reason.name}: ${result.detail}",
            )
    }

    /** Realize the decision and attach its ordered delivery messages. */
    private fun proposalFor(
        decision: SimDecision,
        prompt: GREToClientMessage,
    ): CopilotProposal =
        stampPrompt(
            CopilotProposalRealizer.realize(
                decision = decision,
                promptType = prompt.type,
                seat = seatId.value,
                resolve = resolver,
                gsId = prompt.gameStateId,
                respId = prompt.msgId,
            ),
            prompt,
        )

    private fun stampPrompt(
        proposal: CopilotProposal,
        prompt: GREToClientMessage,
    ): CopilotProposal =
        proposal.copy(
            promptKey = "${prompt.gameStateId}:${prompt.msgId}",
            gameStateId = prompt.gameStateId,
            respId = prompt.msgId,
        )

    private fun resolveEntity(instanceId: Int): EntityRef {
        val card =
            bridge.getForgeCardId(InstanceId(instanceId))?.let { bridge.findCard(it) }
                ?: return EntityRef(instanceId = instanceId, kind = "player")
        return EntityRef(
            instanceId = instanceId,
            kind = "card",
            name = card.name,
            grpId = bridge.cardRepository.findGrpIdByName(card.name),
            zone = card.zone?.zoneType?.name,
            ownerSeat = ownerSeatOf(card),
        )
    }

    private fun ownerSeatOf(card: Card): Int? {
        val owner = card.owner ?: return null
        return (1..2).firstOrNull { bridge.getPlayer(SeatId(it)) == owner }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CopilotProposalService::class.java)
    }
}
