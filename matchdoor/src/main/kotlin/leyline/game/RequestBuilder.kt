package leyline.game

import forge.game.Game
import forge.game.combat.CombatUtil
import leyline.bridge.ForgeCardId
import leyline.bridge.InteractivePromptBridge
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
     * Build a [SelectNReq] from a pending prompt with candidateRefs.
     * Used for "choose N cards" prompts (discard, sacrifice selection, etc.).
     *
     * Maps prompt candidate entity IDs to client instanceIds. The client
     * responds with SelectNResp containing selected instanceIds.
     */
    fun buildSelectNReq(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): SelectNReq {
        val builder = SelectNReq.newBuilder()
            .setMinSel(prompt.request.min)
            .setMaxSel(prompt.request.max.coerceAtLeast(prompt.request.min))
            .setContext(SelectionContext.Discard_a163) // TODO: map promptType → context
            .setListType(SelectionListType.Static)
            .setIdType(IdType.InstanceId_ab2c)
            .setValidationType(SelectionValidationType.NonRepeatable)
        for (ref in prompt.request.candidateRefs) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).value
            builder.addIds(instanceId)
        }
        builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
        return builder.build()
    }

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player seat).
     */
    fun buildDeclareAttackersReq(game: Game, seatId: Int, bridge: GameBridge): DeclareAttackersReq {
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
            // No selectedDamageRecipient — creatures are eligible, not pre-selected.
            // Client shows them as available; player clicks to declare each attacker.
            builder.addAttackers(attacker)
            builder.addQualifiedAttackers(attacker)
        }
        builder.setCanSubmitAttackers(true)

        log.info("buildDeclareAttackersReq: seat={} attackers={}", seatId, builder.attackersCount)
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing all creatures that can legally block.
     *
     * @param assignedBlockerIds blocker instanceIds already assigned in the current
     *   iterative declaration. Real server clears `attackerInstanceIds` for these
     *   (confirmed across 4 recordings) — the client uses this to distinguish
     *   committed vs available blockers.
     */
    fun buildDeclareBlockersReq(
        game: Game,
        seatId: Int,
        bridge: GameBridge,
        assignedBlockerIds: Set<Int> = emptySet(),
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
            // Already-assigned blockers get empty attacker list (committed to their assignment)
            val availableAttackers = if (instanceId in assignedBlockerIds) emptyList() else attackerInstanceIds
            val blocker = Blocker.newBuilder()
                .setBlockerInstanceId(instanceId)
                .addAllAttackerInstanceIds(availableAttackers)
                .setMinAttackers(0)
                .setMaxAttackers(1)
            builder.addBlockers(blocker)
        }

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, assignedBlockerIds.size)
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
