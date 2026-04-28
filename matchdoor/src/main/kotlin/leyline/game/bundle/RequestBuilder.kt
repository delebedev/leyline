package leyline.game.bundle

import forge.game.Game
import forge.game.GameEntity
import forge.game.combat.CombatUtil
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds outbound interactive request protos (targeting, selectN, combat).
 *
 * Pure proto construction from game state — no session state, no sending.
 * [leyline.match.CombatHandler] and [leyline.match.TargetingHandler]
 * handle the inbound responses.
 */
object RequestBuilder {
    private val log = LoggerFactory.getLogger(RequestBuilder::class.java)

    /**
     * Build a [SelectTargetsReq] from an [InteractivePromptBridge.PendingPrompt].
     *
     * Maps prompt candidate refs to client instanceIds:
     * - `kind="card"` → normal card instanceId via [GameBridge.getOrAllocInstanceId]
     * - `kind="player"` → seatId (1 or 2) as instanceId (Arena convention:
     *   players use their seatId as instanceId in target selection)
     *
     * [chooserSeatId] is the seat choosing targets — used for highlights:
     * opponent = Hot (suggested), everything else = Cold.
     *
     */
    fun buildSelectTargetsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        chooserSeatId: Int = 1,
    ): SelectTargetsReq {
        val opponentSeatId = if (chooserSeatId == 1) 2 else 1
        val builder = SelectTargetsReq.newBuilder()
        val selBuilder = TargetSelection.newBuilder()
        selBuilder.setTargetIdx(1)
        selBuilder.setTargetingPlayer(chooserSeatId)

        // sourceId: map the spell's entity ID to its client instanceId
        val sourceEntityId = prompt.request.sourceEntityId
        val sourceInstanceId =
            if (sourceEntityId != null) {
                bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
            } else {
                0
            }
        if (sourceInstanceId != 0) {
            builder.setSourceId(sourceInstanceId)
        }

        for (ref in prompt.request.candidateRefs) {
            val (instanceId, highlight) = resolveRefToIidAndHighlight(ref, bridge, opponentSeatId) ?: continue
            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target
                    .newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(SelectAction.Select_a1ad)
                    .setHighlight(highlight),
            )
        }
        selBuilder.setMinTargets(prompt.request.min)
        selBuilder.setMaxTargets(prompt.request.max)
        builder.addTargets(selBuilder)
        return builder.build()
    }

    /**
     * Build a re-prompt [SelectTargetsReq] reflecting the current selection.
     *
     * Shape:
     * - Already-selected targets: `legalAction=Unselect`, no highlight.
     * - Remaining legal candidates: `legalAction=Select_a1ad`, `highlight=Tepid`
     *   (Hot for opponent player targets, Cold for own-player).
     * - Illegal-after-selection candidates omitted entirely. Legality uses
     *   Forge's [SpellAbility.canTarget] with hypothetical selections applied
     *   via clone-and-swap on `sa.targets` (safe because the engine thread is
     *   blocked inside `requestChoice` between phase-1 and phase-2).
     * - `selectedTargets` set to selection count; minTargets/maxTargets preserved.
     *
     * When [InteractivePromptBridge.PendingPrompt.targetingSa] is null, falls back
     * to emitting all non-selected candidates as Select — unblocks the client
     * without a legality filter.
     */
    fun buildSelectTargetsRePrompt(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        selectedInstanceIds: List<Int>,
        chooserSeatId: Int = 1,
    ): SelectTargetsReq {
        val builder = SelectTargetsReq.newBuilder()
        val selBuilder = TargetSelection.newBuilder()
        selBuilder.setTargetIdx(1)
        selBuilder.setTargetingPlayer(chooserSeatId)
        selBuilder.setMinTargets(prompt.request.min)
        selBuilder.setMaxTargets(prompt.request.max)
        selBuilder.setSelectedTargets(selectedInstanceIds.size)

        val sourceEntityId = prompt.request.sourceEntityId
        val sourceInstanceId =
            if (sourceEntityId != null) {
                bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
            } else {
                0
            }
        if (sourceInstanceId != 0) builder.setSourceId(sourceInstanceId)

        val selectedSet = selectedInstanceIds.toSet()
        val opponentSeatId = if (chooserSeatId == 1) 2 else 1
        val sa = prompt.targetingSa
        val game = bridge.getGame()
        val hypotheticalSelections: List<GameEntity> =
            if (sa != null && game != null) {
                selectedInstanceIds.mapNotNull { resolveEntityByInstanceId(it, bridge, game) }
            } else {
                emptyList()
            }

        // When all target slots are filled (e.g. min=max=1 after one pick), the
        // re-prompt omits Select entries — only the Unselect echo remains.
        val slotsRemaining = selectedInstanceIds.size < prompt.request.max

        for (ref in prompt.request.candidateRefs) {
            val (instanceId, highlight) = resolveRefToIidAndHighlight(ref, bridge, opponentSeatId) ?: continue
            if (instanceId == 0) continue

            if (instanceId in selectedSet) {
                selBuilder.addTargets(
                    wotc.mtgo.gre.external.messaging.Messages.Target
                        .newBuilder()
                        .setTargetInstanceId(instanceId)
                        .setLegalAction(SelectAction.Unselect),
                )
                continue
            }

            // Remaining candidate — only emit if more slots are open and still legal.
            if (!slotsRemaining) continue
            val stillLegal =
                if (sa != null && game != null) {
                    val candidate = resolveEntityByRef(ref, bridge, game)
                    candidate == null || canTargetWithHypothetical(sa, candidate, hypotheticalSelections)
                } else {
                    true
                }
            if (!stillLegal) continue

            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target
                    .newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(SelectAction.Select_a1ad)
                    .setHighlight(highlight),
            )
        }

        builder.addTargets(selBuilder)
        return builder.build()
    }

    /**
     * Check whether [candidate] is still a legal target for [sa] given that
     * [hypothetical] are already selected. Applied via clone-and-swap on
     * `sa.targets` — never mutates the caller-visible TargetChoices.
     */
    private fun canTargetWithHypothetical(
        sa: SpellAbility,
        candidate: GameEntity,
        hypothetical: List<GameEntity>,
    ): Boolean {
        val original = sa.targets
        val clone = original.clone()
        for (e in hypothetical) clone.add(e)
        return try {
            sa.setTargets(clone)
            sa.canTarget(candidate)
        } finally {
            sa.setTargets(original)
        }
    }

    /** Map a client instanceId (seatId for player targets, card iid otherwise) back to a Forge [GameEntity]. */
    private fun resolveEntityByInstanceId(
        instanceId: Int,
        bridge: GameBridge,
        game: Game,
    ): GameEntity? {
        bridge.getPlayer(SeatId(instanceId))?.let { return it }
        val cardId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return null
        return game.findById(cardId.value)
    }

    /** Resolve a [candidateRef] (player- or card-kind) to a Forge [GameEntity]. */
    private fun resolveEntityByRef(
        ref: PromptCandidateRefDto,
        bridge: GameBridge,
        game: Game,
    ): GameEntity? {
        if (ref.kind == "player") {
            val seatId = playerEntityIdToSeatId(ref.entityId, bridge) ?: return null
            return bridge.getPlayer(SeatId(seatId))
        }
        return game.findById(ref.entityId)
    }

    /**
     * Resolve a prompt [candidateRef] to its client-facing `(instanceId, highlight)` tuple.
     * Players map to seatId with Hot/Cold highlight by friend/foe; cards map to their allocated
     * instanceId with Tepid. Returns null when the entityId doesn't resolve.
     */
    private fun resolveRefToIidAndHighlight(
        ref: PromptCandidateRefDto,
        bridge: GameBridge,
        opponentSeatId: Int,
    ): Pair<Int, HighlightType>? {
        if (ref.kind == "player") {
            val seatId = playerEntityIdToSeatId(ref.entityId, bridge) ?: return null
            val hl = if (seatId == opponentSeatId) HighlightType.Hot else HighlightType.Cold
            return seatId to hl
        }
        val iid = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
        return iid to HighlightType.Tepid
    }

    /**
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for "choose N cards" prompts (discard, sacrifice, legend rule, reveal-choose, etc.).
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     *
     * Context/listType vary by prompt type:
     * - `legend_rule`: context=Resolution, listType=Dynamic
     * - `choose_cards` (discard): context=Discard, listType=Static
     * - `reveal_choose`: context=Resolution, listType=Dynamic, +unfilteredIds +sourceId
     */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val semantic = prompt.request.semantic
        val isSacrificePrompt =
            prompt.request.promptType == "choose_cards" &&
                prompt.request.message.contains("sacrifice", ignoreCase = true)
        val (context, listType, optionContext) =
            when (semantic) {
                PromptSemantic.SelectNDiscard ->
                    Triple(
                        SelectionContext.Discard_a163,
                        SelectionListType.Static,
                        OptionContext.Payment,
                    )
                else ->
                    Triple(
                        SelectionContext.Resolution_a163,
                        SelectionListType.Dynamic,
                        OptionContext.Resolution_a9d7,
                    )
            }
        val builder =
            SelectNReq
                .newBuilder()
                .setContext(if (isSacrificePrompt) SelectionContext.Discard_a163 else context)
                .setListType(if (isSacrificePrompt) SelectionListType.Static else listType)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setOptionContext(if (isSacrificePrompt) OptionContext.Payment else optionContext)
                // Always per spec — INT32 extremes (no weight filtering on resolution picks).
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)

        // For reveal-choose with empty ids (no valid target), omit minSel/maxSel (defaults to 0).
        val hasValidChoices = prompt.request.candidateRefs.isNotEmpty()
        if (semantic != PromptSemantic.RevealChoose || hasValidChoices) {
            builder.setMinSel(prompt.request.min)
            builder.setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
        }

        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            builder.addIds(instanceId)
        }
        // unfilteredIds — all revealed cards (superset of ids) for reveal-choose prompts
        for (ref in prompt.request.unfilteredRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            builder.addUnfilteredIds(instanceId)
        }
        when (semantic) {
            PromptSemantic.SelectNLegendRule -> {
                // Empty inner prompt; the real promptId goes on the outer GRE message.
                builder.setPrompt(Prompt.newBuilder())
                builder.setSourceId(PromptIds.SELECT_N_LEGEND_RULE_SOURCE)
            }
            PromptSemantic.SelectNDiscard -> {
                builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DISCARD_COST))
            }
            PromptSemantic.RevealChoose -> {
                val sourceEntityId = prompt.request.sourceEntityId
                if (sourceEntityId != null) {
                    val sourceInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
                    builder.setSourceId(sourceInstanceId)
                }
                builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            }
            PromptSemantic.SelectNResolution -> {
                // Look-and-pick (Stock Up / Dig). Inner prompt carries a PromptId
                // Parameter, NOT a top-level promptId. Outer GRE-message prompt
                // (set in BundleBuilder.selectNBundle) carries the real promptId
                // + 2 CardId Number params (source iid, selection count).
                val sourceEntityId = prompt.request.sourceEntityId
                if (sourceEntityId != null) {
                    val sourceInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
                    builder.setSourceId(sourceInstanceId)
                }
                builder.setPrompt(
                    Prompt
                        .newBuilder()
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("Parameter")
                                .setType(ParameterType.PromptId)
                                .setPromptId(PromptIds.SELECT_N_INNER_PARAMETER),
                        ),
                )
            }
            else -> {
                val sourceEntityId = prompt.request.sourceEntityId
                if (sourceEntityId != null) {
                    val sourceInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
                    builder.setSourceId(sourceInstanceId)
                }
                builder.setPrompt(
                    Prompt.newBuilder().setPromptId(
                        if (isSacrificePrompt) PromptIds.PAY_COSTS else PromptIds.SELECT_N,
                    ),
                )
            }
        }
        return builder.build()
    }

    fun buildSacrificePayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> = buildSelectCostPayCostsReq(prompt, bridge, PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE)

    /**
     * Build a `PayCostsReq` for an additional cost paid by selecting N cards
     * (sacrifice, exile-from-grave, etc). Builder is uniform —
     * `EffectCostType.Select` + `SelectionContext.NonManaPayment` — only the
     * [promptId] differs per cost flavor.
     */
    fun buildSelectCostPayCostsReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        promptId: Int,
    ): Pair<PayCostsReq, Prompt> {
        val sourceInstanceId =
            prompt.request.sourceEntityId?.let {
                bridge.getOrAllocInstanceId(ForgeCardId(it)).value
            } ?: 0

        // Non-mana cost selections are assumed mandatory: pay exactly N.
        // Some upstream call sites pass min=0 (Forge's "non-mandatory" flag,
        // which doesn't apply to keyword-cost additional payment) — coerce to
        // max so the client treats the picker as a fixed-N payment, not a
        // variable range.
        //
        // TODO: when a "pay up to N" cost arrives (e.g. variable additional
        // costs on activated abilities), thread a `mandatory: Boolean` flag
        // through call sites so this coercion can opt out.
        val maxSel = prompt.request.max.coerceAtLeast(prompt.request.min).coerceAtLeast(1)
        val minSel = maxSel
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(minSel)
                .setMaxSel(maxSel)
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                // Canonical envelope for non-mana cost selection: client
                // expects min/max weight extremes set explicitly (proto3
                // defaults are 0, which the client treats as "no candidates
                // selectable").
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)

        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            selection.addIds(instanceId)
            selection.addWeights(1)
        }

        val req =
            PayCostsReq
                .newBuilder()
                // paymentActions is required as an empty ActionsAvailableReq on the client.
                // Without it, the picker renders but treats every card as
                // non-selectable (greyed out).
                .setPaymentActions(ActionsAvailableReq.newBuilder().build())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.Select_a59c)
                        .setCostSelection(selection),
                ).build()

        val promptProto =
            Prompt
                .newBuilder()
                .setPromptId(promptId)
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(sourceInstanceId),
                ).build()
        return req to promptProto
    }

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player seat).
     *
     * @param committedAttackerIds instanceIds of attackers already selected (echo-back).
     *   Committed attackers get [selectedDamageRecipient] set to the opponent player.
     *   Initial request passes empty set (no pre-selection).
     */
    fun buildDeclareAttackersReq(
        seatId: SeatId,
        bridge: GameBridge,
        committedAttackerIds: Set<Int> = emptySet(),
    ): DeclareAttackersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareAttackersReq.getDefaultInstance()
        val builder = DeclareAttackersReq.newBuilder()

        val opponentSeatId = seatId.opponent.value
        val defaultRecipient =
            DamageRecipient
                .newBuilder()
                .setType(DamageRecType.Player_a0e5)
                .setPlayerSystemSeatId(opponentSeatId)
                .build()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val attacker =
                Attacker
                    .newBuilder()
                    .setAttackerInstanceId(instanceId)
                    .addLegalDamageRecipients(defaultRecipient)
            if (instanceId in committedAttackerIds) {
                attacker.setSelectedDamageRecipient(defaultRecipient)
            }
            builder.addAttackers(attacker)
            // qualifiedAttackers never has selectedDamageRecipient
            val qualified =
                Attacker
                    .newBuilder()
                    .setAttackerInstanceId(instanceId)
                    .addLegalDamageRecipients(defaultRecipient)
            builder.addQualifiedAttackers(qualified)
        }
        builder.setCanSubmitAttackers(true)
        // Conformance: client expects an empty manaCost entry entry.
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareAttackersReq: seat={} attackers={} committed={}", seatId, builder.attackersCount, committedAttackerIds.size)
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing all creatures that can legally block.
     *
     * @param blockerAssignments committed blocker→attacker assignments (instanceIds).
     *   Committed blockers get `selectedAttackerInstanceIds` set and `attackerInstanceIds`
     *   cleared. Uncommitted blockers get `attackerInstanceIds` (available targets).
     */
    fun buildDeclareBlockersReq(
        game: Game,
        seatId: SeatId,
        bridge: GameBridge,
        blockerAssignments: Map<Int, Int> = emptyMap(),
    ): DeclareBlockersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareBlockersReq.getDefaultInstance()
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            // Per-attacker legality: only list attackers this creature can legally block
            // (handles flying/reach, menace, protection, etc.)
            val legalAttackers = combat.attackers.filter { CombatUtil.canBlock(it, card) }
            if (legalAttackers.isEmpty()) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val blocker =
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(instanceId)
                    .setMaxAttackers(1)

            val assignedAttacker = blockerAssignments[instanceId]
            if (assignedAttacker != null) {
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                val legalAttackerIds = legalAttackers.map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }
                blocker.addAllAttackerInstanceIds(legalAttackerIds)
            }
            builder.addBlockers(blocker)
        }
        // Conformance: client expects empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, blockerAssignments.size)
        return builder.build()
    }

    /**
     * Map a Forge [forge.game.player.Player.id] to the Arena seatId (1=human, 2=AI).
     * Returns null if the entityId doesn't match either player.
     */
    private fun playerEntityIdToSeatId(
        entityId: Int,
        bridge: GameBridge,
    ): Int? {
        val p1 = bridge.getPlayer(SeatId(1))
        val p2 = bridge.getPlayer(SeatId(2))
        return when (entityId) {
            p1?.id -> 1
            p2?.id -> 2
            else -> null
        }
    }
}
