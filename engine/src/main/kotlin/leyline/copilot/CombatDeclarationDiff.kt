package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq

/**
 * One step of iterative combat declaration: compare the committed set the
 * prompt reflects against the AI's desired set and emit the next single
 * client message — one toggle per round-trip, exactly like a player clicking
 * one creature at a time, then Submit when they match.
 *
 * The committed state is read from the prompt itself (stateless): a committed
 * attacker carries `selectedDamageRecipient`; a committed blocker carries
 * `selectedAttackerInstanceIds`. Each toggle makes the engine echo a fresh
 * prompt, so repeated consults converge and finish with a Submit that answers
 * the final re-prompt.
 */
internal object CombatDeclarationDiff {
    /** Committed attacker instanceIds as reflected by the (re-)prompt. */
    fun committedAttackers(req: DeclareAttackersReq): Set<Int> =
        req.attackersList
            .filter { it.hasSelectedDamageRecipient() }
            .map { it.attackerInstanceId }
            .toSet()

    /** Committed blocker→attacker assignments as reflected by the (re-)prompt. */
    fun committedBlocks(req: DeclareBlockersReq): Map<Int, Int> =
        req.blockersList
            .filter { it.selectedAttackerInstanceIdsCount > 0 }
            .associate { it.blockerInstanceId to it.selectedAttackerInstanceIdsList.first() }

    /**
     * Next attacker-declaration step: toggle one instanceId from the symmetric
     * difference (XOR semantics cover both select and deselect), or Submit
     * when [committed] already equals [desired].
     */
    fun attackerStep(
        committed: Set<Int>,
        desired: Set<Int>,
    ): SimDecision {
        val toggle = (desired - committed).firstOrNull() ?: (committed - desired).firstOrNull()
        return toggle?.let { SimDecision.DeclareAttackers(listOf(it)) } ?: SimDecision.SubmitAttackers
    }

    /**
     * Next blocker-declaration step: assign/reassign one blocker whose
     * committed target differs from the desired one, else un-toggle one
     * blocker no longer wanted, else Submit.
     */
    fun blockerStep(
        committed: Map<Int, Int>,
        desired: Map<Int, Int>,
    ): SimDecision {
        val assign = desired.entries.firstOrNull { (blocker, attacker) -> committed[blocker] != attacker }
        if (assign != null) return SimDecision.DeclareBlockers(mapOf(assign.key to assign.value))
        val unassign = committed.keys.firstOrNull { it !in desired }
        if (unassign != null) return SimDecision.UndeclareBlocker(unassign)
        return SimDecision.SubmitBlockers
    }
}
