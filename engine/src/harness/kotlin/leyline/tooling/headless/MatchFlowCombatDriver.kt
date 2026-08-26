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
    private val submit: (ClientToGREMessage, String) -> Unit,
    private val submitAndAwaitPrompt: (ClientToGREMessage, String, (GREToClientMessage) -> Boolean) -> Unit,
    private val messageSnapshot: () -> Int,
    private val messagesSince: (Int) -> List<GREToClientMessage>,
    private val submitWithGsId: (ClientToGREMessage) -> ClientToGREMessage,
    private val awaitClientOutput: (String, (GREToClientMessage) -> Boolean) -> Unit,
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
        submitAndAwaitPrompt(
            submitWithGsId(
                declareAttackersResp(
                    attackers = attackerInstanceIds,
                    damageRecipients = damageRecipients,
                ),
            ),
            "attacker selection",
            GREToClientMessage::hasDeclareAttackersReq,
        )

        submit(submitWithGsId(submitAttackersReq(seatId.value)), "attacker declaration")
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
        submitAndAwaitPrompt(
            submitWithGsId(
                declareAttackersResp(
                    attackers = attackerInstanceIds,
                    attackerAlternatives = attackerAlternatives,
                    damageRecipients = recipients,
                ),
            ),
            "attacker selection",
            GREToClientMessage::hasDeclareAttackersReq,
        )
        return messagesSince(snap)
    }

    fun deselectAttackers(attackerInstanceIds: List<Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submitAndAwaitPrompt(
            submitWithGsId(declareAttackersResp(attackers = attackerInstanceIds)),
            "attacker selection",
            GREToClientMessage::hasDeclareAttackersReq,
        )
        return messagesSince(snap)
    }

    fun submitAttackers() {
        submit(submitWithGsId(submitAttackersReq(seatId.value)), "attacker declaration")
    }

    fun declareAllAttackers() {
        submit(
            submitWithGsId(declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2)),
            "attacker selection",
        )
    }

    fun declareBlockers(assignments: Map<Int, Int>) {
        submitAndAwaitPrompt(
            submitWithGsId(declareBlockersResp(assignments)),
            "blocker selection",
            GREToClientMessage::hasDeclareBlockersReq,
        )

        submit(submitWithGsId(submitBlockersReq(seatId.value)), "blocker declaration")
    }

    fun declareNoBlockers() {
        val pendingKind =
            bridge()
                .actionBridge(seatId)
                .getPending()
                ?.state
                ?.kind
        if (pendingKind != leyline.bridge.handoff.PendingActionKind.DECLARE_BLOCKERS) {
            awaitClientOutput("client output") { true }
            return
        }
        submit(submitWithGsId(submitBlockersReq(seatId.value)), "blocker declaration")
    }

    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submitAndAwaitPrompt(
            submitWithGsId(declareBlockersResp(assignments)),
            "blocker selection",
            GREToClientMessage::hasDeclareBlockersReq,
        )
        return messagesSince(snap)
    }

    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> {
        val snap = messageSnapshot()
        submitAndAwaitPrompt(
            submitWithGsId(declareBlockersRespDeselect(blockerInstanceId)),
            "blocker selection",
            GREToClientMessage::hasDeclareBlockersReq,
        )
        return messagesSince(snap)
    }

    fun submitBlockers() {
        submit(submitWithGsId(submitBlockersReq(seatId.value)), "blocker declaration")
    }

    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) {
        submit(submitWithGsId(assignDamageResp(assigners)), "damage assignment")
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
