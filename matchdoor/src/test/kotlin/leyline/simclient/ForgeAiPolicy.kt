package leyline.simclient

import forge.ai.PlayerControllerAi
import forge.game.combat.Combat
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Forge-AI advisor that picks an AAR action to submit on behalf of the
 * simclient seat.
 *
 * Builds a parallel [PlayerControllerAi] for the seat (NOT registered as the
 * Forge `Player`'s actual controller — leyline's bridged controller stays in
 * place and continues to emit GRE prompts). The advisor is consulted as a
 * read-only decision source: at a priority window we ask the AI what it would
 * play, then translate the chosen [forge.game.spellability.SpellAbility] into
 * a matching `ActionsAvailableReq` action and submit via the existing
 * `session.onPerformAction` path.
 *
 * ## Load-bearing rules — DO NOT BREAK when adding a new translator method
 *
 * 1. **Don't register [PlayerControllerAi] as the player's controller.**
 *    Leyline's bridged `PlayerController` (extends `PlayerControllerHuman`)
 *    MUST stay registered — it's what emits GRE prompts and blocks on
 *    response futures. Swap it out and the simclient stops producing prompt
 *    traces.
 *
 * 2. **Wrap every Forge-AI call in
 *    `seatPlayer.runWithController(proc, aiController)`.** Forge AI internals
 *    (e.g. `forge.ai.ability.AttachAi.attachToCardAIPreferences`) cast
 *    `player.getController()` to `PlayerControllerAi`. Outside that scope
 *    you'll get `ClassCastException` because the registered controller is
 *    leyline's bridge. The `runWithController` helper layers a
 *    timestamp-MAX controller and removes it in `finally`.
 *
 * 3. **Skip AI consult on Pass-only AARs** (caller side — see
 *    `SimClientDriver.hasCastableActionsInAar`). Forge AI's search costs
 *    50-200ms; during that window leyline's auto-pass loop consumes the
 *    priority window, and our subsequent submit lands "no pending action",
 *    causing a state resync that pollutes the trace with a spurious GSM.
 *    The driver's race guard handles this; translators here don't need to.
 *
 * Iteration 1 scope: only `chooseAarAction` (ActionsAvailableReq) and
 * `chooseBlockers` (DeclareBlockersReq). Other prompt types
 * (SelectTargets, OptionalAction, CTO, NumericInput, Group) fall through
 * to the greedy responder in [SimClientDriver].
 */
class ForgeAiPolicy(
    private val harness: MatchFlowHarness,
    private val seatId: SeatId,
) {
    /** Resolved on first use — bridge is `lateinit` and not ready at construction. */
    private val seatPlayer: Player by lazy {
        harness.bridge.getPlayer(seatId)
            ?: error("ForgeAiPolicy: seat $seatId has no Forge player")
    }

    private val aiController: PlayerControllerAi by lazy {
        PlayerControllerAi(harness.game(), seatPlayer, seatPlayer.lobbyPlayer)
    }

    /** Resolved action ready to submit as a `PerformAction` GRE message. */
    data class Choice(
        val instanceId: Int,
        val grpId: Int,
        val actionType: ActionType,
    )

    /**
     * Ask the AI what to play at the current priority window. Returns a
     * [Choice] when the AI picks an ability that matches an available AAR
     * action, or null when the AI passes / no actionable match.
     *
     * Match rules (iteration 1):
     *   - LandAbility → first AAR action with `actionType=Play_add3` and same grpId
     *   - isSpell()    → first AAR action with `actionType=Cast`         and same grpId
     *   - everything else → null (driver falls back to greedy / pass)
     */
    fun chooseAarAction(): Choice? {
        // Forge AI internals (AttachAi etc.) cast `player.getController()` to
        // PlayerControllerAi during decision-making. Use Forge's built-in
        // runWithController to temporarily install our AI controller for the
        // duration of the decision, then restore the bridged controller.
        var abilities: List<SpellAbility>? = null
        var threw: Throwable? = null
        seatPlayer.runWithController({
            try {
                abilities = aiController.chooseSpellAbilityToPlay()
            } catch (t: Throwable) {
                threw = t
            }
        }, aiController)
        if (threw != null) {
            log.warn(
                "Forge AI chooseSpellAbilityToPlay threw: {}: {}",
                threw!!::class.simpleName,
                threw!!.message,
            )
            return null
        }
        if (abilities.isNullOrEmpty()) return null

        val actions = harness.accumulator.actions ?: return null

        for (sa in abilities) {
            val hostCard = sa.hostCard ?: continue
            val grpId = harness.bridge.cardRepository.findGrpIdByName(hostCard.name) ?: continue
            val actionType =
                when {
                    sa is LandAbility -> ActionType.Play_add3
                    sa.isSpell -> ActionType.Cast
                    else -> continue
                }
            // Prefer the action whose instanceId matches Forge's card id so we cast
            // THIS specific copy. getOrAlloc is idempotent (returns the existing
            // mapping if one exists) — no harmful side effect.
            val mappedInstanceId =
                harness.bridge.getOrAllocInstanceId(ForgeCardId(hostCard.id)).value
            val match =
                actions.actionsList.firstOrNull {
                    it.actionType == actionType &&
                        it.grpId == grpId &&
                        it.instanceId == mappedInstanceId
                }
                    ?: actions.actionsList.firstOrNull {
                        it.actionType == actionType && it.grpId == grpId
                    }
                    ?: continue
            return Choice(match.instanceId, match.grpId, actionType)
        }
        return null
    }

    /**
     * Ask the AI which of the seat's creatures should block the current
     * attackers. Mutates the engine's live [Combat] under runWithController so
     * Forge AI's internals see the expected controller type. Returns a
     * `blockerInstanceId → attackerInstanceId` map ready for
     * [MatchFlowHarness.declareBlockers], or null when there are no blocks.
     *
     * For multi-block (one blocker assigned to several attackers) we emit one
     * entry per (blocker, attacker) pair.
     */
    fun chooseBlockers(): Map<Int, Int>? {
        val combat: Combat = harness.game().combat ?: return null
        if (combat.getAttackers().isEmpty()) return null
        var threw: Throwable? = null
        seatPlayer.runWithController({
            try {
                aiController.declareBlockers(seatPlayer, combat)
            } catch (t: Throwable) {
                threw = t
            }
        }, aiController)
        if (threw != null) {
            log.warn(
                "Forge AI declareBlockers threw: {}: {}",
                threw!!::class.simpleName,
                threw!!.message,
            )
            return null
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (attacker in combat.getAttackers()) {
            val blockers = combat.getBlockers(attacker)
            if (blockers.isNullOrEmpty()) continue
            for (blocker in blockers) {
                val blockerId = harness.bridge.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
                val attackerId = harness.bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
                pairs += blockerId to attackerId
            }
        }
        if (pairs.isEmpty()) return null
        // declareBlockers takes Map<blockerInstanceId, attackerInstanceId>; the
        // multi-block case (same blocker → multiple attackers) collapses to the
        // last assignment per blocker. Acceptable for iteration 2; refine if AI
        // ever banding-blocks.
        return pairs.toMap()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ForgeAiPolicy::class.java)
    }
}
