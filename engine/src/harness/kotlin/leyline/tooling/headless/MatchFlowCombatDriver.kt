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
    private val submitOperation: (ClientToGREMessage, String, () -> Boolean) -> Boolean,
    private val submitAndAwaitPromptOperation: (ClientToGREMessage, String, (GREToClientMessage) -> Boolean, () -> Boolean) -> Boolean,
    private val messageSnapshot: () -> Int,
    private val messagesSince: (Int) -> List<GREToClientMessage>,
    private val submitWithGsId: (ClientToGREMessage) -> ClientToGREMessage,
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

    fun declareAttackers(
        attackerInstanceIds: List<Int>,
        completeWhen: () -> Boolean = { false },
    ) {
        declareAttackers(attackerInstanceIds, defaultDamageRecipients(attackerInstanceIds), completeWhen)
    }

    fun declareAttackers(
        attackerInstanceIds: List<Int>,
        damageRecipients: Map<Int, DamageRecipient>,
        completeWhen: () -> Boolean = { false },
    ) {
        if (
            submitAndAwaitPrompt(
                submitWithGsId(
                    declareAttackersResp(
                        attackers = attackerInstanceIds,
                        damageRecipients = damageRecipients,
                    ),
                ),
                "attacker selection",
                GREToClientMessage::hasDeclareAttackersReq,
                completeWhen,
            )
        ) {
            return
        }

        submit(submitWithGsId(submitAttackersReq(seatId.value)), "attacker declaration", completeWhen)
    }

    fun declareNoAttackers(completeWhen: () -> Boolean = { false }) {
        declareAttackers(emptyList(), completeWhen)
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

    fun declareBlockers(
        assignments: Map<Int, Int>,
        completeWhen: () -> Boolean = { false },
    ) {
        if (
            submitAndAwaitPrompt(
                submitWithGsId(declareBlockersResp(assignments)),
                "blocker selection",
                GREToClientMessage::hasDeclareBlockersReq,
                completeWhen,
            )
        ) {
            return
        }

        submit(submitWithGsId(submitBlockersReq(seatId.value)), "blocker declaration", completeWhen)
    }

    fun declareNoBlockers(completeWhen: () -> Boolean = { false }) {
        submit(submitWithGsId(submitBlockersReq(seatId.value)), "blocker declaration", completeWhen)
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

    private fun submit(
        message: ClientToGREMessage,
        description: String,
        completeWhen: () -> Boolean = { false },
    ): Boolean = submitOperation(message, description, completeWhen)

    private fun submitAndAwaitPrompt(
        message: ClientToGREMessage,
        description: String,
        predicate: (GREToClientMessage) -> Boolean,
        completeWhen: () -> Boolean = { false },
    ): Boolean = submitAndAwaitPromptOperation(message, description, predicate, completeWhen)

    private fun defaultDamageRecipients(attackerInstanceIds: List<Int>): Map<Int, DamageRecipient> =
        attackerInstanceIds.associateWith {
            DamageRecipient
                .newBuilder()
                .setType(wotc.mtgo.gre.external.messaging.Messages.DamageRecType.Player_a0e5)
                .setPlayerSystemSeatId(seatId.opponent.value)
                .build()
        }
}
