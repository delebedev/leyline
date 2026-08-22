package leyline.tooling.headless

import forge.game.zone.ZoneType
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal class MatchFlowCombatDriver(
    private val seatId: SeatId,
    private val bridge: () -> GameBridge,
    private val submit: (ClientToGREMessage) -> Unit,
    private val messageSnapshot: () -> Int,
    private val messagesSince: (Int) -> List<GREToClientMessage>,
    private val submitWithGsId: (ClientToGREMessage) -> ClientToGREMessage,
    private val drainSink: () -> Unit,
) {
    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> {
        val bridge = bridge()
        val player = bridge.getPlayer(seatId) ?: return emptyList()
        return player
            .getZone(ZoneType.Battlefield)
            .cards
            .filter { it.isCreature }
            .map { bridge.instanceId(it) to it.name }
    }

    fun declareAttackers(attackerInstanceIds: List<Int>) {
        declareAttackers(attackerInstanceIds, defaultDamageRecipients(attackerInstanceIds))
    }

    fun declareAttackers(
        attackerInstanceIds: List<Int>,
        damageRecipients: Map<Int, DamageRecipient>,
    ) {
        submit(
            submitWithGsId(
                declareAttackersResp(
                    attackers = attackerInstanceIds,
                    damageRecipients = damageRecipients,
                ),
            ),
        )
        drainSink()

        submit(submitWithGsId(submitAttackersReq(seatId.value)))
        drainSink()
    }

    fun declareNoAttackers() {
        declareAttackers(emptyList())
    }

    fun toggleAttackers(
        attackerInstanceIds: List<Int>,
        attackerAlternatives: Map<Int, Int> = emptyMap(),
        damageRecipients: Map<Int, DamageRecipient> = defaultDamageRecipients(attackerInstanceIds),
    ): List<GREToClientMessage> {
        val recipients = damageRecipients.ifEmpty { defaultDamageRecipients(attackerInstanceIds) }
        val snap = messageSnapshot()
        submit(
            submitWithGsId(
                declareAttackersResp(
                    attackers = attackerInstanceIds,
                    attackerAlternatives = attackerAlternatives,
                    damageRecipients = recipients,
                ),
            ),
        )
        drainSink()
        return messagesSince(snap)
    }

    fun deselectAttackers(attackerInstanceIds: List<Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submit(
            submitWithGsId(declareAttackersResp(attackers = attackerInstanceIds)),
        )
        drainSink()
        return messagesSince(snap)
    }

    fun submitAttackers() {
        submit(submitWithGsId(submitAttackersReq(seatId.value)))
        drainSink()
    }

    fun declareAllAttackers() {
        submit(
            submitWithGsId(declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2)),
        )
        drainSink()
    }

    fun declareBlockers(assignments: Map<Int, Int>) {
        submit(submitWithGsId(declareBlockersResp(assignments)))
        drainSink()

        submit(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    fun declareNoBlockers() {
        submit(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submit(submitWithGsId(declareBlockersResp(assignments)))
        drainSink()
        return messagesSince(snap)
    }

    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submit(
            submitWithGsId(declareBlockersRespDeselect(blockerInstanceId)),
        )
        drainSink()
        return messagesSince(snap)
    }

    fun submitBlockers() {
        submit(submitWithGsId(submitBlockersReq(seatId.value)))
        drainSink()
    }

    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) {
        submit(submitWithGsId(assignDamageResp(assigners)))
        drainSink()
    }

    private fun defaultDamageRecipients(attackerInstanceIds: List<Int>): Map<Int, DamageRecipient> =
        attackerInstanceIds.associateWith {
            DamageRecipient
                .newBuilder()
                .setType(wotc.mtgo.gre.external.messaging.Messages.DamageRecType.Player_a0e5)
                .setPlayerSystemSeatId(seatId.opponent.value)
                .build()
        }
}
