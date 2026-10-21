package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.player.PlayerActionConfirmMode
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.StaticChoiceIds
import leyline.game.mapping.PromptIds

/** Plans are consumed synchronously on the callback thread, not retained by the planner. */
sealed interface ConfirmActionPlan {
    data object Accept : ConfirmActionPlan

    data class CommanderReturn(
        val hostCard: Card,
    ) : ConfirmActionPlan

    data class OptionalAction(
        val hostCard: Card?,
        val defaultOnTimeout: Boolean,
        val forceSnapshotBeforePrompt: Boolean,
        val logContext: String,
        val customPromptId: Int? = null,
    ) : ConfirmActionPlan

    sealed interface Choice : ConfirmActionPlan {
        val options: List<String>
        val sourceEntityId: Int?
        val route: ResolvedPromptRoute

        /** Candidate-free default preserves the bridge's call history and priority policy. */
        data class AffirmativeDefault(
            override val options: List<String>,
            override val sourceEntityId: Int?,
        ) : Choice {
            override val route = PromptRouteResolver.resolve(PromptSemantic.Generic)
        }

        data class Parity(
            override val options: List<String>,
            override val sourceEntityId: Int?,
            val optionIds: List<Int>,
        ) : Choice {
            override val route = PromptRouteResolver.resolve(PromptSemantic.StaticParityChoice)
        }
    }
}

/** Owns confirmAction precedence while the resolving callback and shown card are available. */
object ConfirmActionPlanner {
    fun plan(
        sa: SpellAbility?,
        mode: PlayerActionConfirmMode?,
        options: List<String>?,
        cardToShow: Card?,
    ): ConfirmActionPlan {
        if (SpellAbilityShapes.isParadigmCopyCast(sa) || SpellAbilityShapes.isParadigmCopyCard(cardToShow)) {
            return ConfirmActionPlan.Accept
        }
        val hostCard = cardToShow ?: sa?.hostCard
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination && hostCard?.isRealCommander == true) {
            return ConfirmActionPlan.CommanderReturn(hostCard)
        }
        when {
            sa?.api == ApiType.Endure ->
                return ConfirmActionPlan.OptionalAction(hostCard, true, false, "confirmAction:Endure", PromptIds.ENDURE_PUT_COUNTERS)
            sa?.api == ApiType.CopySpellAbility && sa.hasParam("Optional") ->
                return ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:Optional")
            sa?.api == ApiType.RearrangeTopOfLibrary && sa.hasParam("MayShuffle") ->
                return ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:MayShuffle", PromptIds.OPTIONAL_SHUFFLE)
        }
        val labels = options?.takeIf { it.isNotEmpty() }?.toList() ?: listOf("Yes", "No")
        val sourceEntityId = hostCard?.id?.takeIf { it > 0 }
        val parityIds = StaticChoiceIds.parityIdsForNames(labels)
        return if (parityIds != null) {
            ConfirmActionPlan.Choice.Parity(labels, sourceEntityId, parityIds)
        } else {
            ConfirmActionPlan.Choice.AffirmativeDefault(labels, sourceEntityId)
        }
    }
}
