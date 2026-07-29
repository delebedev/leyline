package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import java.util.UUID

@JvmInline
value class ActionToken(
    val value: String,
)

/**
 * Owns exact executable commands for one or more short-lived priority windows.
 *
 * Callers expose only [ActionToken] and immutable command facts outside the
 * bridge. [GameActionBridge] owns synchronization for the table and its
 * enclosing priority-window lifecycle.
 */
internal class PriorityActionTokenTable(
    private val tokenFactory: () -> ActionToken = { ActionToken(UUID.randomUUID().toString()) },
) {
    private val commands = mutableMapOf<ActionToken, BoundCommand>()

    private data class BoundCommand(
        val actionId: String,
        val command: PlayerAction,
    )

    fun register(
        actionId: String,
        command: PlayerAction,
    ): ActionToken =
        commands.entries
            .firstOrNull { (_, registered) -> registered.actionId == actionId && registered.command == command }
            ?.key
            ?: tokenFactory().also {
                commands[it] = BoundCommand(actionId, command)
            }

    /**
     * Atomically replace one window's commands and return one token per input.
     *
     * Existing tokens are reused for equal commands. Token generation completes
     * against a staged table so a factory failure leaves the live table intact.
     */
    fun replaceBatch(
        actionId: String,
        replacements: List<PlayerAction>,
    ): List<ActionToken> {
        val existingForWindow = commands.filterValues { it.actionId == actionId }
        val staged = commands.filterValues { it.actionId != actionId }.toMutableMap()
        val assigned = mutableMapOf<PlayerAction, ActionToken>()
        val tokens =
            replacements.map { command ->
                val token =
                    assigned[command]
                        ?: existingForWindow.entries
                            .firstOrNull { (_, registered) -> registered.command == command }
                            ?.key
                        ?: tokenFactory().also { generated ->
                            check(generated !in commands && generated !in staged) {
                                "Priority action token factory returned a duplicate token"
                            }
                        }
                assigned[command] = token
                staged[token] = BoundCommand(actionId, command)
                token
            }
        commands.clear()
        commands.putAll(staged)
        return tokens
    }

    fun contains(
        actionId: String,
        token: ActionToken,
    ): Boolean = commands[token]?.actionId == actionId

    fun take(
        actionId: String,
        token: ActionToken,
    ): PlayerAction? {
        val bound = commands[token]?.takeIf { it.actionId == actionId } ?: return null
        commands.remove(token)
        return bound.command
    }

    fun retain(
        actionId: String,
        retainedTokens: Set<ActionToken>,
    ) {
        val removed =
            commands
                .filter { (token, bound) -> bound.actionId == actionId && token !in retainedTokens }
                .keys
        removed.forEach(commands::remove)
    }

    fun clear(actionId: String) {
        retain(actionId, emptySet())
    }
}

internal fun PlayerAction.cardIdOrNull(): ForgeCardId? =
    when (this) {
        is PlayerAction.ActivateAbility -> cardId
        is PlayerAction.ActivateMana -> cardId
        is PlayerAction.CastSpell -> cardId
        is PlayerAction.PlayLand -> cardId
        is PlayerAction.DeclareAttackers,
        is PlayerAction.DeclareBlockers,
        PlayerAction.EndTurn,
        PlayerAction.PassPriority,
        -> null
    }

internal fun PlayerAction.retainsLiveAbility(): Boolean =
    when (this) {
        is PlayerAction.ActivateAbility -> ability != null
        is PlayerAction.ActivateMana -> ability != null
        is PlayerAction.CastSpell -> ability != null
        is PlayerAction.DeclareAttackers,
        is PlayerAction.DeclareBlockers,
        is PlayerAction.PlayLand,
        PlayerAction.EndTurn,
        PlayerAction.PassPriority,
        -> false
    }
