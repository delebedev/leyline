package leyline.game

import forge.game.Game
import forge.game.combat.CombatUtil
import leyline.bridge.ForgeCardId
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptSemantic
import leyline.bridge.SeatId
import leyline.game.mapper.PromptIds
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
     * See `docs/plans/player-targeting.md` for protocol details from proxy recording.
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
        val sourceInstanceId = if (sourceEntityId != null) {
            bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
        } else {
            0
        }
        if (sourceInstanceId != 0) {
            builder.setSourceId(sourceInstanceId)
        }

        for (ref in prompt.request.candidateRefs) {
            val instanceId: Int
            val highlight: HighlightType
            if (ref.kind == "player") {
                val seatId = playerEntityIdToSeatId(ref.entityId, bridge) ?: continue
                instanceId = seatId
                highlight = if (seatId == opponentSeatId) HighlightType.Hot else HighlightType.Cold
            } else {
                instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
                // Tepid = blue/cyan "legal target" glow (matches real Arena).
                // Cold (1) = yellowish-green, Hot (3) = bright/suggested,
                // None (0) = no glow at all (proto default, field omitted).
                highlight = HighlightType.Tepid
            }
            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target.newBuilder()
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
     * Real server re-prompt differences from initial:
     * - Only selected targets remain (unselected removed)
     * - `legalAction: Unselect` (was Select)
     * - `highlight` absent (proto default = None)
     * - `selectedTargets` count set
     *
     * Wire spec: docs/plans/2026-03-14-submit-targets-wire-spec.md
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
        val sourceInstanceId = if (sourceEntityId != null) {
            bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
        } else {
            0
        }
        if (sourceInstanceId != 0) builder.setSourceId(sourceInstanceId)

        // Only include selected targets with Unselect action, no highlight
        for (iid in selectedInstanceIds) {
            selBuilder.addTargets(
                wotc.mtgo.gre.external.messaging.Messages.Target.newBuilder()
                    .setTargetInstanceId(iid)
                    .setLegalAction(SelectAction.Unselect),
            )
        }

        builder.addTargets(selBuilder)
        return builder.build()
    }

    /**
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for "choose N cards" prompts (discard, sacrifice, legend rule, etc.).
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     *
     * Context/listType vary by prompt type:
     * - `legend_rule`: context=Resolution, listType=Dynamic
     * - `choose_cards` (discard): context=Discard, listType=Static
     */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val semantic = prompt.request.semantic
        val (context, listType, optionContext) = when (semantic) {
            PromptSemantic.SelectNDiscard -> Triple(
                SelectionContext.Discard_a163,
                SelectionListType.Static,
                OptionContext.Payment,
            )
            else -> Triple(
                SelectionContext.Resolution_a163,
                SelectionListType.Dynamic,
                OptionContext.Resolution_a9d7,
            )
        }
        val builder = SelectNReq.newBuilder()
            .setMinSel(prompt.request.min)
            .setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
            .setContext(context)
            .setListType(listType)
            .setIdType(IdType.InstanceId_ab2c)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(optionContext)
        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            builder.addIds(instanceId)
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
            else -> {
                builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            }
        }
        return builder.build()
    }

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player seat).
     *
     * @param committedAttackerIds instanceIds of attackers already selected (echo-back).
     *   Committed attackers get [selectedDamageRecipient] set to the opponent player.
     *   Initial request passes empty set (no pre-selection).
     *   Conformance: recording 2026-03-06_22-37-41 frames 202 vs 205.
     */
    fun buildDeclareAttackersReq(
        game: Game,
        seatId: Int,
        bridge: GameBridge,
        committedAttackerIds: Set<Int> = emptySet(),
    ): DeclareAttackersReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return DeclareAttackersReq.getDefaultInstance()
        val builder = DeclareAttackersReq.newBuilder()

        val opponentSeatId = if (seatId == 1) 2 else 1
        val defaultRecipient = DamageRecipient.newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(opponentSeatId)
            .build()

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val attacker = Attacker.newBuilder()
                .setAttackerInstanceId(instanceId)
                .addLegalDamageRecipients(defaultRecipient)
            if (instanceId in committedAttackerIds) {
                attacker.setSelectedDamageRecipient(defaultRecipient)
            }
            builder.addAttackers(attacker)
            // qualifiedAttackers never has selectedDamageRecipient (confirmed in recordings)
            val qualified = Attacker.newBuilder()
                .setAttackerInstanceId(instanceId)
                .addLegalDamageRecipients(defaultRecipient)
            builder.addQualifiedAttackers(qualified)
        }
        builder.setCanSubmitAttackers(true)
        // Conformance: real server always includes an empty manaCost entry.
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
     *   Conformance: recording 2026-03-01_00-18-46 idx 238, 2026-03-14_17-28-50 idx 106.
     */
    fun buildDeclareBlockersReq(
        game: Game,
        seatId: Int,
        bridge: GameBridge,
        blockerAssignments: Map<Int, Int> = emptyMap(),
    ): DeclareBlockersReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return DeclareBlockersReq.getDefaultInstance()
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()

        // Collect attacker instanceIds
        val attackerInstanceIds = combat.attackers.map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val blocker = Blocker.newBuilder()
                .setBlockerInstanceId(instanceId)
                .setMaxAttackers(1)

            val assignedAttacker = blockerAssignments[instanceId]
            if (assignedAttacker != null) {
                // Committed: selectedAttackerInstanceIds set, attackerInstanceIds cleared
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                // Available: attackerInstanceIds lists what this blocker can block
                blocker.addAllAttackerInstanceIds(attackerInstanceIds)
            }
            builder.addBlockers(blocker)
        }
        // Conformance: real server includes empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, blockerAssignments.size)
        return builder.build()
    }

    /**
     * Map a Forge [forge.game.player.Player.id] to the Arena seatId (1=human, 2=AI).
     * Returns null if the entityId doesn't match either player.
     */
    private fun playerEntityIdToSeatId(entityId: Int, bridge: GameBridge): Int? {
        val p1 = bridge.getPlayer(SeatId(1))
        val p2 = bridge.getPlayer(SeatId(2))
        return when (entityId) {
            p1?.id -> 1
            p2?.id -> 2
            else -> null
        }
    }
}
