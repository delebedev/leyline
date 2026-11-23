package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.player.PlayerActionConfirmMode
import forge.game.spellability.SpellAbility
import forge.util.Localizer
import leyline.bridge.handoff.AutomaticAnswerRule
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.StaticChoiceIds
import leyline.game.mapping.PromptIds

/** Plans are consumed synchronously on the callback thread, not retained by the planner. */
sealed interface ConfirmActionPlan {
    /** Answer Yes without asking, under a named [rule]. */
    data class Automatic(
        val rule: AutomaticAnswerRule,
    ) : ConfirmActionPlan

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

        /** Forge's own option labels, asked as a named choice; option 0 means Yes. */
        data class Labels(
            override val options: List<String>,
            override val sourceEntityId: Int?,
        ) : Choice {
            override val route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice)
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

/**
 * Owns confirmAction precedence while the resolving callback and shown card are available.
 *
 * Every confirmation is asked unless it matches an [AutomaticAnswerRule]. Yes/No
 * confirmations ask through the optional-action gate; Forge's own labels ask as a
 * named choice.
 */
object ConfirmActionPlanner {
    @Suppress("CyclomaticComplexMethod")
    fun plan(
        sa: SpellAbility?,
        mode: PlayerActionConfirmMode?,
        message: String?,
        options: List<String>?,
        cardToShow: Card?,
    ): ConfirmActionPlan {
        val hostCard = cardToShow ?: sa?.hostCard
        return when {
            SpellAbilityShapes.isParadigmCopyCast(sa) || SpellAbilityShapes.isParadigmCopyCard(cardToShow) ->
                ConfirmActionPlan.Automatic(AutomaticAnswerRule.ParadigmCopyCast)
            mode == PlayerActionConfirmMode.ChangeZoneToAltDestination && hostCard?.isRealCommander == true ->
                ConfirmActionPlan.CommanderReturn(hostCard)
            mode == PlayerActionConfirmMode.ChangeZoneGeneral && ForgeMessages.isCancelSearch(message) ->
                ConfirmActionPlan.Automatic(AutomaticAnswerRule.SearchSelectionSubmitted)
            cardToShow != null && mode == null && ForgeMessages.precedesOptionalCast(message) ->
                ConfirmActionPlan.Automatic(AutomaticAnswerRule.PlayEffectPrecedesOptionalCast)
            sa?.api == ApiType.Endure ->
                ConfirmActionPlan.OptionalAction(hostCard, true, false, "confirmAction:Endure", PromptIds.ENDURE_PUT_COUNTERS)
            sa?.api == ApiType.CopySpellAbility && sa.hasParam("Optional") ->
                ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:Optional")
            sa?.api == ApiType.RearrangeTopOfLibrary && sa.hasParam("MayShuffle") ->
                ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:MayShuffle", PromptIds.OPTIONAL_SHUFFLE)
            sa?.api == ApiType.Repeat && sa.hasParam("RepeatOptional") ->
                ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:RepeatOptional")
            // A resolving optional transfer must expose its current zones before consent.
            sa?.api == ApiType.ChangeZone &&
                sa.hasParam("Optional") &&
                (mode == null || mode == PlayerActionConfirmMode.ChangeZoneGeneral) ->
                ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction:OptionalZoneChange")
            else -> askPlan(options, hostCard)
        }
    }

    private fun askPlan(
        options: List<String>?,
        hostCard: Card?,
    ): ConfirmActionPlan {
        val labels = options?.takeIf { it.isNotEmpty() }?.toList() ?: YES_NO
        val sourceEntityId = hostCard?.id?.takeIf { it > 0 }
        val parityIds = StaticChoiceIds.parityIdsForNames(labels)
        return when {
            parityIds != null -> ConfirmActionPlan.Choice.Parity(labels, sourceEntityId, parityIds)
            labels != YES_NO || hostCard == null -> ConfirmActionPlan.Choice.Labels(labels, sourceEntityId)
            // The gate publishes the pre-decision state and declines on timeout.
            else -> ConfirmActionPlan.OptionalAction(hostCard, false, true, "confirmAction")
        }
    }

    private val YES_NO = listOf("Yes", "No")
}

/** Recognizes Forge confirmation messages by their localized templates. */
internal object ForgeMessages {
    fun isCancelSearch(message: String?): Boolean = matches(message, "lblCancelSearchUpToSelectNumCards")

    /** Play-effect and Discover confirmations that Forge follows with `playSaFromPlayEffect`. */
    fun precedesOptionalCast(message: String?): Boolean =
        matches(message, "lblDoYouWantPlayCard") ||
            matches(message, "lblDoYouWantPlayCardTransformed") ||
            matches(message, "lblDiscoverChoice")

    /** True when [message] is the template [key] with its one argument filled in. */
    private fun matches(
        message: String?,
        key: String,
    ): Boolean {
        if (message == null) return false
        val template = Localizer.getInstance().getMessage(key, PLACEHOLDER)
        val prefix = template.substringBefore(PLACEHOLDER)
        val suffix = template.substringAfter(PLACEHOLDER, "")
        if (prefix == template) return false
        return message.length > prefix.length + suffix.length && message.startsWith(prefix) && message.endsWith(suffix)
    }

    private const val PLACEHOLDER = "\u0000"
}
