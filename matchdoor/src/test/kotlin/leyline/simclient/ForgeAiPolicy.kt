package leyline.simclient

import forge.ai.PlayerControllerAi
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.combat.Combat
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.testkit.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

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
 * Scope: `chooseAarAction` (ActionsAvailableReq), `chooseAttackers`
 * (DeclareAttackersReq), and `chooseBlockers` (DeclareBlockersReq). Other
 * prompt types (SelectTargets, OptionalAction, CTO, NumericInput, Group) fall
 * through to the greedy responder in [SimClientDriver].
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
        val action: Action,
        val instanceId: Int,
        val grpId: Int,
        val actionType: ActionType,
        val abilityGrpId: Int = 0,
    )

    /**
     * Ask the AI what to play at the current priority window. Returns a
     * [Choice] when the AI picks an ability that matches an available AAR
     * action, or null when the AI passes / no actionable match.
     *
     * Match rules (iteration 1):
     *   - LandAbility → first AAR action with `actionType=Play_add3` and same grpId
     *   - isSpell()    → first AAR action with `actionType=Cast`         and same grpId
     *   - non-spell activated ability → exact `Activate_add3` host iid + abilityGrpId match
     *   - everything else → null (driver falls back to greedy / pass)
     */
    fun chooseAarAction(
        promptActions: List<Action>,
        skipFingerprints: Set<String> = emptySet(),
    ): Choice? {
        val abilities = askAi("chooseSpellAbilityToPlay") { aiController.chooseSpellAbilityToPlay() }
        if (abilities.isNullOrEmpty()) return null

        for (sa in abilities) {
            val hostCard = sa.hostCard ?: continue
            val grpId = harness.bridge.cardRepository.findGrpIdByName(hostCard.name) ?: continue
            val actionType =
                when {
                    sa is LandAbility -> ActionType.Play_add3
                    sa.isSpell -> ActionType.Cast
                    else -> ActionType.Activate_add3
                }
            // Prefer the action whose instanceId matches Forge's card id so we cast
            // THIS specific copy. getOrAlloc is idempotent (returns the existing
            // mapping if one exists) — no harmful side effect.
            val mappedInstanceId =
                harness.bridge.getOrAllocInstanceId(ForgeCardId(hostCard.id)).value
            val match = chooseMatchingAction(sa, actionType, grpId, mappedInstanceId, promptActions, skipFingerprints) ?: continue
            return Choice(match, match.instanceId, match.grpId, actionType, match.abilityGrpId)
        }
        return null
    }

    private fun chooseMatchingAction(
        sa: SpellAbility,
        actionType: ActionType,
        grpId: Int,
        mappedInstanceId: Int,
        promptActions: List<Action>,
        skipFingerprints: Set<String>,
    ): Action? {
        if (actionType != ActionType.Activate_add3) {
            return promptActions.firstOrNull {
                it.actionType == actionType &&
                    it.grpId == grpId &&
                    it.instanceId == mappedInstanceId &&
                    it.actionFingerprint() !in skipFingerprints
            } ?: promptActions.firstOrNull {
                it.actionType == actionType && it.grpId == grpId && it.actionFingerprint() !in skipFingerprints
            }
        }

        val hostCard = sa.hostCard ?: return null
        val cardData = harness.bridge.cardRepository.findByGrpId(grpId) ?: return null
        val abilityGrpId = harness.bridge.abilityRegistryFor(hostCard, cardData)?.forSpellAbility(sa.id) ?: return null
        return promptActions.firstOrNull {
            it.actionType == ActionType.Activate_add3 &&
                it.instanceId == mappedInstanceId &&
                it.abilityGrpId == abilityGrpId &&
                it.actionFingerprint() !in skipFingerprints
        }
    }

    /**
     * Ask the AI which creatures should attack. Uses a throwaway [Combat]
     * object so the consult does not mutate Forge's live combat before the
     * bridge receives a DeclareAttackers response.
     */
    fun chooseAttackers(): List<Int>? {
        val probeCombat = Combat(seatPlayer)
        askAi("declareAttackers") { aiController.declareAttackers(seatPlayer, probeCombat) } ?: return null
        return probeCombat.getAttackers().map { attacker ->
            harness.bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
        }
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
        askAi("declareBlockers") { aiController.declareBlockers(seatPlayer, combat) } ?: return null
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

    fun canChooseSelectN(req: SelectNReq): Boolean {
        val count = selectNCount(req)
        if (count <= 0) return false
        val cards = req.idsList.mapNotNull { cardForInstance(it) }
        return cards.size == req.idsList.size && cards.all { it.zone?.zoneType == ZoneType.Hand }
    }

    fun chooseSelectN(req: SelectNReq): List<Int>? {
        val count = selectNCount(req)
        if (count <= 0) return emptyList()
        val candidates = req.idsList.mapNotNull { cardForInstance(it) }
        if (candidates.size != req.idsList.size || candidates.any { it.zone?.zoneType != ZoneType.Hand }) return null
        val targetPlayer = candidates.firstOrNull()?.owner ?: return null
        if (candidates.any { it.owner != targetPlayer }) return null
        val sourceSa = cardForInstance(req.sourceId)?.spellAbilities?.firstOrNull()
        val validCards = CardCollection(candidates)
        val chosen =
            askAi("chooseCardsToDiscardFrom") {
                aiController.chooseCardsToDiscardFrom(targetPlayer, sourceSa, validCards, count, count, validCards)
            } ?: return null
        val chosenIds = chosen.map { instanceIdForCard(it) }.filter { it != 0 }
        return chosenIds.takeIf { it.size >= count }?.take(count)
    }

    private fun selectNCount(req: SelectNReq): Int {
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        return min.coerceAtMost(max)
    }

    private fun cardForInstance(instanceId: Int): Card? {
        if (instanceId == 0) return null
        val forgeId = harness.bridge.getForgeCardId(InstanceId(instanceId)) ?: return null
        return harness.bridge.findCard(forgeId)
    }

    private fun instanceIdForCard(card: Card): Int = harness.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value

    private fun <T> askAi(
        label: String,
        block: () -> T,
    ): T? {
        var result: T? = null
        var threw: Throwable? = null
        seatPlayer.runWithController({
            try {
                result = block()
            } catch (t: Throwable) {
                threw = t
            }
        }, aiController)
        if (threw != null) {
            log.warn("Forge AI {} threw: {}: {}", label, threw!!::class.simpleName, threw!!.message)
        }
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(ForgeAiPolicy::class.java)
    }
}
