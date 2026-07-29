package leyline.game.mapping

import leyline.bridge.handoff.ActionToken
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.PreparedPriorityOffer
import leyline.game.PreparedPriorityWindow
import leyline.game.PriorityActionSet
import leyline.game.PriorityActionValue

/**
 * Owner-neutral action preparation. Protocol ids and executable tokens are
 * assigned only when the owning traversal commits the complete window.
 */
internal data class PriorityActionPreparation(
    val actions: PriorityActionSet,
    val offers: List<PreparedPriorityAction>,
) {
    init {
        require(actions.actions.size == offers.size) {
            "Every active priority action must have an executable offer"
        }
    }

    val commands: List<PlayerAction>
        get() = offers.map(PreparedPriorityAction::command)

    fun bindTokens(
        actionId: String,
        tokens: List<ActionToken>,
    ): PreparedPriorityWindow {
        require(tokens.size == offers.size) {
            "Prepared token count must match active priority actions"
        }
        return PreparedPriorityWindow(
            actionId = actionId,
            actions = actions,
            offers =
                offers.zip(tokens) { offer, token ->
                    PreparedPriorityOffer(
                        value = offer.value,
                        token = token,
                        cardId = offer.cardId,
                        abilityId = offer.abilityId,
                        stackAbilityGrpId = offer.stackAbilityGrpId,
                        forgeAbilityId = offer.forgeAbilityId,
                        spellGrpId = offer.spellGrpId,
                    )
                },
        )
    }
}

internal data class PreparedPriorityAction(
    val value: PriorityActionValue,
    val command: PlayerAction,
    val stackAbilityGrpId: Int? = null,
    val forgeAbilityId: Int? = null,
    val spellGrpId: Int? = null,
) {
    val cardId: ForgeCardId?
        get() =
            when (command) {
                is PlayerAction.CastSpell -> command.cardId
                is PlayerAction.ActivateAbility -> command.cardId
                is PlayerAction.ActivateMana -> command.cardId
                is PlayerAction.PlayLand -> command.cardId
                is PlayerAction.DeclareAttackers,
                is PlayerAction.DeclareBlockers,
                PlayerAction.EndTurn,
                PlayerAction.PassPriority,
                -> null
            }

    val abilityId: Int?
        get() =
            when (command) {
                is PlayerAction.CastSpell -> command.abilityId
                is PlayerAction.ActivateAbility -> command.abilityId
                is PlayerAction.ActivateMana -> command.abilityId
                is PlayerAction.DeclareAttackers,
                is PlayerAction.DeclareBlockers,
                is PlayerAction.PlayLand,
                PlayerAction.EndTurn,
                PlayerAction.PassPriority,
                -> null
            }
}

internal class PriorityActionPreparationBuilder {
    private val values = PriorityActionSetBuilder()
    private val offers = mutableListOf<PreparedPriorityAction>()

    fun addAction(
        value: PriorityActionValue,
        command: PlayerAction,
        stackAbilityGrpId: Int? = null,
        forgeAbilityId: Int? = null,
        spellGrpId: Int? = null,
    ) {
        values.addAction(value)
        offers +=
            PreparedPriorityAction(
                value = value,
                command = command,
                stackAbilityGrpId = stackAbilityGrpId,
                forgeAbilityId = forgeAbilityId,
                spellGrpId = spellGrpId,
            )
    }

    fun addInactiveAction(value: PriorityActionValue) {
        values.addInactiveAction(value)
    }

    fun addAllInactiveActions(actions: Iterable<PriorityActionValue>) {
        values.addAllInactiveActions(actions)
    }

    fun build(): PriorityActionPreparation = PriorityActionPreparation(values.build(), offers.toList())
}
