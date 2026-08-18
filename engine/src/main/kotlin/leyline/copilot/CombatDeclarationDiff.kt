package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq

/**
 * One step of iterative combat declaration: compare the committed set the
 * prompt reflects against the AI's desired set and emit the next client
 * message. Attackers toggle individually. Blockers send the complete desired
 * map once because a fresh AI consult against the intermediate combat can
 * reinterpret its own accepted selection.
 *
 * The committed state is read from the prompt itself (stateless): a committed
 * attacker carries `selectedDamageRecipient`; a committed blocker carries
 * `selectedAttackerInstanceIds`. The engine echoes a fresh prompt after the
 * declaration, and that accepted echo is submitted without another AI choice.
 */
internal object CombatDeclarationDiff {
    /** AI-desired attackers restricted to the instanceIds qualified by this prompt. */
    fun qualifiedDesiredAttackers(
        req: DeclareAttackersReq,
        desired: Set<Int>,
    ): Set<Int> {
        val qualified = req.qualifiedAttackersList.mapTo(mutableSetOf()) { it.attackerInstanceId }
        return desired.filterTo(linkedSetOf()) { it in qualified }
    }

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

    /** AI-desired blocks restricted to each blocker's choices in this prompt. */
    fun qualifiedDesiredBlocks(
        req: DeclareBlockersReq,
        desired: Map<Int, Int>,
    ): Map<Int, Int> {
        val qualified =
            req.blockersList.associate { blocker ->
                blocker.blockerInstanceId to
                    (blocker.attackerInstanceIdsList + blocker.selectedAttackerInstanceIdsList).toSet()
            }
        return desired.filterTo(linkedMapOf()) { (blocker, attacker) -> attacker in qualified[blocker].orEmpty() }
    }

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

    /** Send the complete blocker plan once, then submit its accepted echo. */
    fun blockerStep(
        committed: Map<Int, Int>,
        desired: Map<Int, Int>,
    ): SimDecision {
        if (committed.isNotEmpty()) return SimDecision.SubmitBlockers
        return desired.takeIf { it.isNotEmpty() }?.let(SimDecision::DeclareBlockers) ?: SimDecision.SubmitBlockers
    }
}
