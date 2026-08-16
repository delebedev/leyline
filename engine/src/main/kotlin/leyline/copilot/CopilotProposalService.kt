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
    private val resolver = EntityResolver(::resolveEntity)

    /** Forge-AI heuristic position score for the seat; null when eval fails. */
    fun evaluate(): EvalScore? = policy.evaluateGameState()?.let { EvalScore(it.value, it.summonSickValue) }

    /** Propose a response for [prompt]; null / uncovered / failed consults yield `unrealizable`. */
    fun propose(prompt: GREToClientMessage?): CopilotProposal {
        if (prompt == null) {
            return ProposalTranslator.unrealizable(GREMessageType.PromptReq, seatId.value, "no pending prompt for seat ${seatId.value}")
        }
        return runCatching { route(prompt) }
            .getOrElse { t ->
                log.warn("copilot proposal for {} failed: {}", prompt.type, t.message, t)
                ProposalTranslator.unrealizable(prompt.type, seatId.value, "consult failed: ${t.message}")
            }
    }

    // GREMessageType is a large proto enum; only these families are decoded and
    // the else fallthrough to `unrealizable` is intentional, not a gap.
    @Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
    private fun route(prompt: GREToClientMessage): CopilotProposal =
        when (prompt.type) {
            // Opening-hand keep. Scripted skip-mulligan puzzles never emit this,
            // so it only fires on the live-client path.
            GREMessageType.MulliganReq_aa0d -> proposalFor(SimDecision.KeepHand, prompt)

            GREMessageType.ActionsAvailableReq_695e -> {
                val actions = prompt.actionsAvailableReq.actionsList
                val choice = policy.chooseAarAction(actions) ?: policy.chooseMain2ProactivePermanent(actions)
                proposalFor(choice?.let { SimDecision.PerformAction(it.action) } ?: SimDecision.PassPriority, prompt)
            }

            // Two-round-trip targeting: diff the prompt's committed picks
            // against the AI's desired set — select the missing, Submit (stamped
            // with this prompt's msgId) once they match. On the fully-committed
            // re-prompt the AI can no longer re-pick (targets echo as Unselect),
            // so an already-satisfied committed set stands as the desired set.
            GREMessageType.SelectTargetsReq_695e ->
                mapDecision(prompt) {
                    val req = prompt.selectTargetsReq
                    val committed = TargetSelectionDiff.committedTargets(req)
                    val desired =
                        policy.chooseSelectTargets(prompt)
                            ?: committed.takeIf { TargetSelectionDiff.isValid(req, it) }
                    desired?.let { TargetSelectionDiff.step(req = req, committed = committed, desired = it) }
                }

            GREMessageType.SelectNreq ->
                mapDecision(prompt) {
                    (policy.chooseSelectN(prompt.selectNReq) ?: policy.chooseStaticColorSelectN(prompt))
                        ?.let(SimDecision::SelectN)
                        ?: DefaultDecisions.selectN(prompt)
                }

            GREMessageType.CastingTimeOptionsReq_695e ->
                mapDecision(prompt) { policy.chooseCastingTimeOptions(prompt) ?: DefaultDecisions.castingTimeOptions(prompt) }

            GREMessageType.OrderReq_695e -> proposalFor(DefaultDecisions.order(prompt), prompt)

            GREMessageType.SearchReq_695e -> proposalFor(DefaultDecisions.search(prompt), prompt)

            GREMessageType.NumericInputReq_695e -> proposalFor(DefaultDecisions.numericInput(prompt), prompt)

            GREMessageType.PayCostsReq_695e ->
                // Auto-tap mana confirm ("Auto-Pay") vs an effect's sacrifice
                // cost. The former offers tap solutions; confirm the first
                // (the client's re-solve, always legal). The latter needs the
                // AI to pick what to sacrifice.
                if (prompt.payCostsReq.autoTapActionsReq.autoTapSolutionsCount > 0) {
                    proposalFor(SimDecision.AutoTapPayment(0), prompt)
                } else {
                    // Sacrifice cost the AI can pick, else back out. A PayCostsReq
                    // that is neither an auto-tap mana solve nor a computable
                    // sacrifice (e.g. an activated ability whose cost we cannot
                    // realize) would otherwise be unrealizable — no response, and
                    // the game-loop parks on the half-activated ability. Cancelling
                    // unwinds it to a priority window so the game keeps moving.
                    proposalFor(
                        policy.chooseEffectCostPayment(prompt)?.let(SimDecision::EffectCost) ?: SimDecision.CancelAction,
                        prompt,
                    )
                }

            // Scry/surveil ordering: keep everything on top (always legal, moves
            // nothing) — enough to never stall the loop on this family.
            GREMessageType.GroupReq_695e -> proposalFor(DefaultDecisions.group(prompt), prompt)

            // Two-round-trip declaration: diff the prompt's committed set
            // against the AI's desired set — one toggle per consult, Submit
            // (stamped with this prompt's msgId) once they match.
            GREMessageType.DeclareAttackersReq_695e ->
                mapDecision(prompt) {
                    policy.chooseAttackers()?.let { desired ->
                        val req = prompt.declareAttackersReq
                        CombatDeclarationDiff.attackerStep(
                            committed = CombatDeclarationDiff.committedAttackers(req),
                            desired = CombatDeclarationDiff.qualifiedDesiredAttackers(req, desired.toSet()),
                        )
                    }
                }

            // Combat damage assignment: echo the engine's pre-filled per-blocker
            // lethal (+ trample overflow to the player) so the attack resolves.
            GREMessageType.AssignDamageReq_695e -> proposalFor(DefaultDecisions.assignDamage(prompt), prompt)

            // Same diff/submit contract as attackers; a null chooseBlockers
            // means "no blocks", converging straight to Submit.
            GREMessageType.DeclareBlockersReq_695e ->
                mapDecision(prompt) {
                    val req = prompt.declareBlockersReq
                    val committed = CombatDeclarationDiff.committedBlocks(req)
                    val desired =
                        CombatDeclarationDiff.fullyCommittedBlocks(req)
                            ?: CombatDeclarationDiff.qualifiedDesiredBlocks(
                                req,
                                policy.chooseBlockers(prompt) ?: emptyMap(),
                            )
                    CombatDeclarationDiff.blockerStep(
                        committed = committed,
                        desired = desired,
                    )
                }

            GREMessageType.OptionalActionMessage_695e ->
                proposalFor(DefaultDecisions.optionalAction(), prompt)

            else -> ProposalTranslator.unrealizable(prompt.type, seatId.value, "prompt type ${prompt.type} has no copilot decoder")
        }

    private fun mapDecision(
        prompt: GREToClientMessage,
        decide: () -> SimDecision?,
    ): CopilotProposal {
        val decision =
            decide() ?: return ProposalTranslator.unrealizable(
                prompt.type,
                seatId.value,
                "Forge AI produced no mappable response for ${prompt.type}",
            )
        return proposalFor(decision, prompt)
    }

    /** Translate the decision and attach its ordered delivery messages. */
    private fun proposalFor(
        decision: SimDecision,
        prompt: GREToClientMessage,
    ): CopilotProposal =
        ProposalTranslator.translate(decision, prompt.type, seatId.value, resolver).copy(
            promptKey = "${prompt.gameStateId}:${prompt.msgId}",
            gameStateId = prompt.gameStateId,
            respId = prompt.msgId,
            responses =
                ResponseBuilder
                    .build(decision, prompt.gameStateId, seatId.value, respId = prompt.msgId)
                    .let(ResponseBuilder::hexMessages),
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
