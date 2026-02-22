package forge.nexus.conformance

import forge.ai.PlayerControllerAi
import forge.game.Game
import forge.game.combat.Combat
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import org.slf4j.LoggerFactory

/**
 * Scripted actions for deterministic AI turns in tests.
 *
 * Each action maps to a top-level decision the engine asks the AI.
 * Sub-decisions (mana payment, targeting) delegate to [PlayerControllerAi].
 */
sealed interface ScriptedAction {
    /** Pass priority — do nothing this priority window. */
    data object PassPriority : ScriptedAction

    /** Play a land by name from hand. */
    data class PlayLand(val cardName: String) : ScriptedAction

    /** Cast a spell by name from hand. */
    data class CastSpell(val cardName: String) : ScriptedAction

    /** Declare specific creatures as attackers. */
    data class Attack(val cardNames: List<String>) : ScriptedAction

    /** Declare no attackers (skip combat). */
    data object DeclareNoAttackers : ScriptedAction

    /** Declare no blockers. */
    data object DeclareNoBlockers : ScriptedAction
}

/**
 * AI [PlayerControllerAi] that follows a predetermined script.
 *
 * Override only top-level decision methods (chooseSpellAbilityToPlay,
 * declareAttackers, declareBlockers). Everything else (mana payment,
 * targeting, mulligan) delegates to the parent AI controller.
 *
 * Script exhaustion falls back to PassPriority. Illegal actions (card not
 * in hand, can't cast) log a warning and pass — never hang, never throw.
 */
class ScriptedPlayerController(
    game: Game,
    player: Player,
    private val script: List<ScriptedAction>,
) : PlayerControllerAi(game, player, player.lobbyPlayer) {

    private val log = LoggerFactory.getLogger(ScriptedPlayerController::class.java)
    private var scriptIndex = 0

    /** Peek at the next action without consuming. */
    private fun peekAction(): ScriptedAction? =
        if (scriptIndex < script.size) script[scriptIndex] else null

    /** Consume and return the next scripted action, or null if exhausted. */
    private fun nextAction(): ScriptedAction? {
        if (scriptIndex >= script.size) return null
        return script[scriptIndex++]
    }

    /**
     * Choose spell ability to play. Returns:
     * - non-empty list → play those abilities
     * - **null** → pass priority (Forge convention: null = "I pass", emptyList = "tried but
     *   failed" which causes PhaseHandler to retry up to 999 times)
     *
     * PlayLand/CastSpell actions are only consumed when the ability is actually
     * found and playable. If not playable yet (wrong turn, tapped out, etc.),
     * the action stays at the top of the script queue and we pass — the engine
     * advances to the next priority window where the action may become valid.
     */
    override fun chooseSpellAbilityToPlay(): List<SpellAbility>? {
        val action = peekAction()
        return when (action) {
            is ScriptedAction.PlayLand -> {
                val sa = findLandAbility(action.cardName)
                if (sa != null) {
                    nextAction() // consume
                    listOf(sa)
                } else {
                    // Don't consume — card may become playable on a later turn.
                    // Returning null passes priority; engine advances.
                    null
                }
            }
            is ScriptedAction.CastSpell -> {
                val sa = findCastAbility(action.cardName)
                if (sa != null) {
                    nextAction() // consume
                    listOf(sa)
                } else {
                    null // don't consume — try again when castable
                }
            }
            is ScriptedAction.PassPriority -> {
                nextAction()
                null
            }
            null -> null // script exhausted → pass
            else -> null // combat actions handled in declare* methods
        }
    }

    override fun declareAttackers(attacker: Player, combat: Combat) {
        val action = peekAction()
        when (action) {
            is ScriptedAction.Attack -> {
                nextAction()
                val bf = attacker.getZone(ZoneType.Battlefield)
                val defender = attacker.opponents.firstOrNull() ?: return
                for (name in action.cardNames) {
                    val card = bf.cards.firstOrNull {
                        it.name.equals(name, ignoreCase = true) && it.isCreature
                    }
                    if (card != null) {
                        combat.addAttacker(card, defender)
                    } else {
                        log.warn("Script: Attack({}) — creature not found on battlefield", name)
                    }
                }
            }
            is ScriptedAction.DeclareNoAttackers -> {
                nextAction()
                // Do nothing — no attackers declared
            }
            else -> {
                // No script instruction for this combat → delegate to AI
                super.declareAttackers(attacker, combat)
            }
        }
    }

    override fun declareBlockers(defender: Player, combat: Combat) {
        val action = peekAction()
        when (action) {
            is ScriptedAction.DeclareNoBlockers -> {
                nextAction()
                // Do nothing — no blockers declared
            }
            else -> {
                super.declareBlockers(defender, combat)
            }
        }
    }

    // -- Helper: find spell abilities by card name --

    private fun findLandAbility(cardName: String): SpellAbility? {
        val hand = player.getZone(ZoneType.Hand)
        val card = hand.cards.firstOrNull { it.name.equals(cardName, ignoreCase = true) && it.isLand }
            ?: return null
        return card.spellAbilities.firstOrNull { it is LandAbility && it.canPlay() }
    }

    private fun findCastAbility(cardName: String): SpellAbility? {
        val hand = player.getZone(ZoneType.Hand)
        val card = hand.cards.firstOrNull { it.name.equals(cardName, ignoreCase = true) }
            ?: return null
        return card.spellAbilities.firstOrNull { !it.isLandAbility && it.canPlay() }
    }
}
