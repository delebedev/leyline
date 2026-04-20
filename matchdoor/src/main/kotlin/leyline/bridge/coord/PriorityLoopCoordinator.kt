package leyline.bridge.coord

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.DevCheck
import leyline.bridge.PlayableActionQuery
import leyline.bridge.findCard
import leyline.bridge.forge.PlayerController
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.OwnerContext
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.resolveAttackDefender
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.PhaseStopProfile
import leyline.bridge.types.PriorityDecision
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.collections.iterator

/**
 * Owns the engine-thread priority loop and the combat callbacks.
 *
 * The four overrides routed here (`chooseSpellAbilityToPlay`,
 * `declareAttackers`, `declareBlockers`, `assignCombatDamage`) share a
 * distinctive timing model: they block the engine thread on [GameActionBridge],
 * not [leyline.bridge.handoff.InteractivePromptBridge], and they drive the
 * main game-loop decision points rather than ad-hoc choices.
 *
 * See [PlayerController]'s KDoc for the coordinator pattern.
 */
class PriorityLoopCoordinator(
    private val owner: OwnerContext,
    private val game: Game,
    private val player: Player,
    private val actionBridge: GameActionBridge,
    private val phaseStopProfile: PhaseStopProfile?,
    private val smartPhaseSkip: Boolean,
    private val spellExecutor: SpellExecutor,
) {
    private val log = LoggerFactory.getLogger(PriorityLoopCoordinator::class.java)

    private var lastSeenTurn: Int = -1

    /**
     * Main priority window entry point. Notify state, block until the client
     * responds, translate the response into a Forge [SpellAbility] (or null
     * for pass). Mana abilities loop without re-passing priority because they
     * do not use the stack.
     */
    fun chooseSpellAbility(): List<SpellAbility>? {
        val handler = game.phaseHandler

        val currentTurn = handler.turn
        if (currentTurn != lastSeenTurn) {
            lastSeenTurn = currentTurn
            actionBridge.setAutoPassUntilEndOfTurn(false)
        }

        if (actionBridge.autoPassUntilEndOfTurn) {
            owner.recordDecision(PriorityDecision.Skip(AutoPassReason.EndTurnFlag))
            return null
        }

        // Full control: skip all engine-side auto-pass, always return priority to session layer
        val fullControl = owner.autoPassState?.isFullControl ?: false

        // Smart phase skip (ADR-008): auto-pass when player has no meaningful actions.
        // Only on own turn — on opponent's turn the player needs priority at their
        // phase stops to cast instants (e.g. Kill Shot during combat).
        // Never skip when stack has items — player should see stack state.
        // Never skip right after a prompt resolved — player needs to see the result.
        // Never skip when full control is on.
        val promptJustResolved = actionBridge.prioritySignal?.consumePromptResolved() == true
        val smartSkipAllowed = !fullControl && smartPhaseSkip && !promptJustResolved
        if (smartSkipAllowed &&
            handler.playerTurn?.id == player.id &&
            game.stack.isEmpty &&
            !PlayableActionQuery.hasPlayableNonManaAction(game, player)
        ) {
            owner.recordDecision(PriorityDecision.Skip(AutoPassReason.SmartPhaseSkip))
            return null
        }

        val isOwnTurn = handler.playerTurn?.id == player.id
        // Phase stop check only applies on human's own turn. During opponent's turn
        // the session layer (advanceOrWait) handles opponent-turn stops separately.
        if (!fullControl &&
            isOwnTurn &&
            phaseStopProfile != null &&
            !phaseStopProfile.isEnabled(player.id, handler.phase)
        ) {
            owner.recordDecision(PriorityDecision.Skip(AutoPassReason.PhaseNotStopped(handler.phase?.name ?: "UNKNOWN")))
            return null
        }

        // Loop so that mana abilities (which don't use the stack) keep priority with the player.
        while (true) {
            owner.notifyStateChanged()

            val state = PendingActionState(
                phase = handler.phase?.name ?: "UNKNOWN",
                turn = handler.turn,
                activePlayerId = handler.playerTurn?.id ?: -1,
                priorityPlayerId = player.id,
            )
            when (val action = actionBridge.awaitAction(state)) {
                is PlayerAction.PassPriority -> return null
                is PlayerAction.EndTurn -> {
                    actionBridge.setAutoPassUntilEndOfTurn(true)
                    return null
                }
                is PlayerAction.CastSpell -> return spellExecutor.castSpell(action.cardId, action.abilityId, action.targets)
                is PlayerAction.ActivateAbility -> return spellExecutor.activateAbility(action.cardId, action.abilityId, action.targets)
                is PlayerAction.ActivateMana -> {
                    if (!spellExecutor.activateMana(action.cardId)) {
                        log.debug("Mana activation failed for card {}", action.cardId.value)
                    }
                    continue
                }
                is PlayerAction.PlayLand -> return spellExecutor.playLand(action.cardId)
                else -> return null
            }
        }
    }

    fun declareAttackers(attacker: Player, combat: Combat) {
        log.info("declareAttackers: waiting for {}", attacker.name)
        owner.notifyStateChanged()

        val state = PendingActionState(
            phase = "COMBAT_DECLARE_ATTACKERS",
            turn = game.phaseHandler.turn,
            activePlayerId = attacker.id,
            priorityPlayerId = attacker.id,
        )
        when (val action = actionBridge.awaitAction(state)) {
            is PlayerAction.DeclareAttackers -> {
                val resolvedDefender = resolveAttackDefender(game, attacker, action.defender)
                for (cardId in action.attackerIds) {
                    val card = findCard(game, cardId) ?: continue
                    if (!CombatUtil.canAttack(card)) continue
                    val defender = resolvedDefender ?: combat.defenders.firstOrNull() ?: continue
                    combat.addAttacker(card, defender)
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    fun declareBlockers(defender: Player, combat: Combat) {
        log.info("declareBlockers: waiting for {}", defender.name)
        owner.notifyStateChanged()

        val state = PendingActionState(
            phase = "COMBAT_DECLARE_BLOCKERS",
            turn = game.phaseHandler.turn,
            activePlayerId = defender.id,
            priorityPlayerId = defender.id,
        )
        when (val action = actionBridge.awaitAction(state)) {
            is PlayerAction.DeclareBlockers -> {
                for ((blockerCardId, attackerCardId) in action.blockAssignments) {
                    val blocker = findCard(game, blockerCardId) ?: continue
                    val attackerCard = findCard(game, attackerCardId) ?: continue
                    if (combat.isAttacking(attackerCard)) {
                        combat.addBlocker(attackerCard, blocker)
                    }
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    /**
     * Prompt for manual combat damage distribution and block the engine thread
     * on a dedicated future. [fallback] is invoked when the future times out or
     * errors — the caller is expected to pass the matching `super` call so the
     * engine continues with the default assignment.
     */
    fun promptForCombatDamage(
        attacker: Card,
        blockers: CardCollectionView,
        damageDealt: Int,
        defender: GameEntity?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? {
        // Clear stale cache entries from a previous damage step.
        owner.damageAssignCache.clear()

        log.info(
            "assignCombatDamage: prompting for {} (id={}, damage={}, blockers={})",
            attacker.name,
            attacker.id,
            damageDealt,
            blockers.size,
        )

        // Block the engine thread on a dedicated future. The auto-pass loop detects
        // this via CombatHandler.checkPendingDamageAssignment and sends AssignDamageReq.
        // CombatHandler.onAssignDamage completes the future.
        val future = CompletableFuture<MutableMap<Card?, Int>>()
        owner.pendingDamageAssignment = PlayerController.DamageAssignmentPrompt(
            attacker = attacker,
            blockers = blockers,
            damageDealt = damageDealt,
            defender = defender,
            hasDeathtouch = attacker.hasKeyword(Keyword.DEATHTOUCH),
            hasTrample = attacker.hasKeyword(Keyword.TRAMPLE),
            future = future,
        )
        actionBridge.prioritySignal?.signal()

        return try {
            future.get(actionBridge.getTimeoutMs(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.warn("assignCombatDamage: timed out, auto-assigning for {}", attacker.name)
            DevCheck.failOnAutoPass { "assignCombatDamage timed out for ${attacker.name}" }
            fallback()
        } catch (ex: Exception) {
            log.warn("assignCombatDamage: error {}, auto-assigning", ex.message)
            DevCheck.failOnAutoPass { "assignCombatDamage error: ${ex.message}" }
            fallback()
        } finally {
            owner.pendingDamageAssignment = null
            owner.damageAssignCache.clear()
        }
    }
}
