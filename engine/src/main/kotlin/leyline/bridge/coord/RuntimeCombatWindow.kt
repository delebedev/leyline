package leyline.bridge.coord

import forge.game.Game
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.Target
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ForgePlayerId
import leyline.bridge.types.InstanceId
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal data class RuntimeAttackerSelection(
    val alternativeGrpId: Int,
    val damageRecipient: DamageRecipient,
)

/** Exact engine identities retained when a combat window is published. */
internal class RuntimeCombatWindow(
    private val attackerByInstanceId: Map<Int, ForgeCardId>,
    private val legalAlternativesByAttacker: Map<Int, Set<Int>>,
    private val blockerByInstanceId: Map<Int, ForgeCardId>,
    private val targetCardByInstanceId: Map<Int, ForgeCardId>,
    private val playerBySeatId: Map<Int, ForgePlayerId>,
    private val defaultDefender: ForgePlayerId?,
) {
    private val attackers = linkedMapOf<Int, RuntimeAttackerSelection>()
    private val blockers = linkedMapOf<Int, Int>()

    fun hasLegalAttackers(): Boolean = attackerByInstanceId.isNotEmpty()

    fun replaceAttackers(next: Map<Int, RuntimeAttackerSelection>) {
        attackers.clear()
        attackers.putAll(next)
    }

    fun replaceBlockers(next: Map<Int, Int>) {
        blockers.clear()
        blockers.putAll(next)
    }

    fun selectedAttackerInstanceIds(): List<Int> = attackers.keys.toList()

    fun selectedAttackAlternatives(): Map<Int, Int> = attackers.mapValues { it.value.alternativeGrpId }

    fun selectedDamageRecipients(): Map<Int, DamageRecipient> = attackers.mapValues { it.value.damageRecipient }

    fun selectedBlockAssignments(): Map<Int, Int> = blockers.toMap()

    @Suppress("ReturnCount")
    fun nextAttackers(answer: DeclarationAnswer.Attackers): Map<Int, RuntimeAttackerSelection>? {
        if (answer.attackerInstanceIds.distinct().size != answer.attackerInstanceIds.size) return null
        if (answer.attackerInstanceIds.any { it !in attackerByInstanceId }) return null
        if (answer.attackAlternativeByAttacker.keys.any { it !in answer.attackerInstanceIds }) return null
        if (answer.attackerInstanceIds.any { instanceId ->
                (answer.attackAlternativeByAttacker[instanceId] ?: 0) !in legalAlternativesByAttacker[instanceId].orEmpty()
            }
        ) {
            return null
        }

        if (answer.autoDeclare) {
            val defaultSeat = playerBySeatId.entries.firstOrNull { it.value == defaultDefender }?.key ?: return null
            val defaultRecipient =
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(defaultSeat)
                    .build()
            return attackerByInstanceId.keys.associateWith {
                RuntimeAttackerSelection(answer.attackAlternativeByAttacker[it] ?: 0, defaultRecipient)
            }
        }

        val next = attackers.toMutableMap()
        answer.attackerInstanceIds.forEach { instanceId ->
            val recipient =
                answer.defenderByAttacker[instanceId] ?: run {
                    if (instanceId !in next) return null
                    next.remove(instanceId)
                    return@forEach
                }
            val wireRecipient = resolveDamageRecipient(recipient) ?: return null
            next[instanceId] = RuntimeAttackerSelection(answer.attackAlternativeByAttacker[instanceId] ?: 0, wireRecipient)
        }
        return next
    }

    fun nextBlockers(answer: DeclarationAnswer.Blockers): Map<Int, Int>? {
        if (answer.touchedBlockerInstanceIds.distinct().size != answer.touchedBlockerInstanceIds.size) return null
        if (answer.touchedBlockerInstanceIds.any { it !in blockerByInstanceId }) return null
        if (answer.blockAssignments.keys.any { it !in answer.touchedBlockerInstanceIds }) return null
        if (answer.blockAssignments.values.any { it !in attackerByInstanceId }) return null
        val next = blockers.toMutableMap()
        answer.touchedBlockerInstanceIds.forEach { blockerId ->
            val attackerId = answer.blockAssignments[blockerId]
            if (attackerId == null) next.remove(blockerId) else next[blockerId] = attackerId
        }
        return next
    }

    private fun resolveDamageRecipient(target: DeclarationAnswer.Target): DamageRecipient? =
        when (target) {
            is DeclarationAnswer.Target.Player ->
                target.seatId
                    .takeIf { it in playerBySeatId }
                    ?.let {
                        DamageRecipient
                            .newBuilder()
                            .setType(DamageRecType.Player_a0e5)
                            .setPlayerSystemSeatId(it)
                            .build()
                    }
            is DeclarationAnswer.Target.Planeswalker ->
                target.instanceId
                    .takeIf { it in targetCardByInstanceId }
                    ?.let {
                        DamageRecipient
                            .newBuilder()
                            .setType(DamageRecType.PlanesWalker)
                            .setPlaneswalkerInstanceId(it)
                            .build()
                    }
        }

    fun resolveDeclaration(kind: PendingActionKind): PlayerAction? =
        when (kind) {
            PendingActionKind.DECLARE_ATTACKERS -> {
                val attackerIds = attackers.keys.mapNotNull(attackerByInstanceId::get)
                val alternatives =
                    attackers
                        .mapNotNull { (instanceId, selection) ->
                            val forgeId = attackerByInstanceId[instanceId] ?: return@mapNotNull null
                            forgeId to selection.alternativeGrpId
                        }.filter { it.second != 0 }
                        .toMap()
                val recipients =
                    attackers
                        .mapNotNull { (instanceId, selection) ->
                            val forgeId = attackerByInstanceId[instanceId] ?: return@mapNotNull null
                            val target =
                                when (selection.damageRecipient.type) {
                                    DamageRecType.Player_a0e5 ->
                                        Target.Player(
                                            ForgePlayerId(selection.damageRecipient.playerSystemSeatId),
                                        )
                                    DamageRecType.PlanesWalker ->
                                        targetCardByInstanceId[selection.damageRecipient.planeswalkerInstanceId]?.let(Target::Card)
                                    else -> null
                                }
                            target?.let { forgeId to it }
                        }.toMap()
                PlayerAction.DeclareAttackers(
                    attackerIds,
                    alternatives,
                    defaultDefender?.let(Target::Player),
                    recipients,
                )
            }
            PendingActionKind.DECLARE_BLOCKERS ->
                PlayerAction.DeclareBlockers(
                    blockers
                        .mapNotNull { (blocker, attacker) ->
                            val blockerId = blockerByInstanceId[blocker] ?: return@mapNotNull null
                            val attackerId = attackerByInstanceId[attacker] ?: return@mapNotNull null
                            blockerId to attackerId
                        }.toMap(),
                )
            PendingActionKind.PRIORITY,
            PendingActionKind.SYNC_ONLY,
            -> null
        }

    companion object {
        fun capture(
            owner: MatchCutCoordinator,
            game: Game,
            messages: List<GREToClientMessage>,
        ): RuntimeCombatWindow? {
            val attackersReq = messages.firstOrNull { it.hasDeclareAttackersReq() }?.declareAttackersReq
            val blockersReq = messages.firstOrNull { it.hasDeclareBlockersReq() }?.declareBlockersReq
            if (attackersReq == null && blockersReq == null) return null

            val attackerIds =
                (
                    attackersReq?.attackersList?.map { it.attackerInstanceId }.orEmpty() +
                        blockersReq?.blockersList?.flatMap { it.attackerInstanceIdsList }.orEmpty() +
                        game.phaseHandler.combat
                            ?.attackers
                            ?.map { owner.bridge.instanceId(it) }
                            .orEmpty()
                ).distinct()
            val legalAlternatives =
                attackersReq
                    ?.attackersList
                    ?.groupBy { it.attackerInstanceId }
                    ?.mapValues { (_, options) -> options.map { it.alternativeGrpId }.toSet() }
                    .orEmpty()
            val blockerIds =
                blockersReq
                    ?.blockersList
                    ?.map { it.blockerInstanceId }
                    .orEmpty()
                    .distinct()
            val targetIds =
                attackersReq
                    ?.attackersList
                    ?.flatMap { it.legalDamageRecipientsList }
                    ?.filter { it.type == DamageRecType.PlanesWalker }
                    ?.map { it.planeswalkerInstanceId }
                    .orEmpty()
                    .distinct()
            val blockTargetIds =
                blockersReq
                    ?.blockersList
                    ?.flatMap { it.attackerInstanceIdsList }
                    .orEmpty()
                    .distinct()
            val cardByInstanceId =
                (attackerIds + blockerIds + targetIds + blockTargetIds)
                    .distinct()
                    .associateWith { instanceId ->
                        owner.bridge.getForgeCardId(InstanceId(instanceId))
                            ?: error("Combat window references unknown instanceId=$instanceId")
                    }
            val players =
                listOf(owner.bridge.seating.humanSeat, owner.bridge.seating.familiarSeat)
                    .mapNotNull { seat -> owner.bridge.getPlayer(seat)?.let { seat.value to ForgePlayerId(it.id) } }
                    .toMap()
            val active = game.phaseHandler.playerTurn
            val defaultDefender =
                active?.let { activePlayer ->
                    players.values.firstOrNull { it.value != activePlayer.id }
                }
            return RuntimeCombatWindow(
                attackerByInstanceId = attackerIds.associateWith(cardByInstanceId::getValue),
                legalAlternativesByAttacker = legalAlternatives,
                blockerByInstanceId = blockerIds.associateWith(cardByInstanceId::getValue),
                targetCardByInstanceId = targetIds.associateWith(cardByInstanceId::getValue),
                playerBySeatId = players,
                defaultDefender = defaultDefender,
            )
        }
    }
}
