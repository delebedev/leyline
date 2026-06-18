package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility

object SpellAbilityShapes {
    fun isSuspectChoice(sa: SpellAbility?): Boolean =
        sa?.api == ApiType.ChooseCard &&
            sa.paramTokens("DefinedCards").any(::isTriggeredCardsToken) &&
            sa.subAbilityChain().any { subAbility -> subAbility.suspectsChosenCard() }

    private fun SpellAbility.suspectsChosenCard(): Boolean =
        api == ApiType.AlterAttribute &&
            !hasParamValue("Activate", "False") &&
            paramTokens("Defined").any { it.equals("ChosenCard", ignoreCase = true) } &&
            paramTokens("Attributes").any { it.equals("Suspect", ignoreCase = true) || it.equals("Suspected", ignoreCase = true) }

    private fun SpellAbility.subAbilityChain(): Sequence<SpellAbility> = generateSequence(subAbility) { it.subAbility }

    private fun isTriggeredCardsToken(token: String): Boolean =
        token.equals("TriggeredCards", ignoreCase = true) || token.startsWith("TriggeredCards.", ignoreCase = true)

    private fun SpellAbility.paramTokens(name: String): Set<String> =
        if (!hasParam(name)) {
            emptySet()
        } else {
            getParam(name)
                .split(',', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

    private fun SpellAbility.hasParamValue(
        name: String,
        value: String,
    ): Boolean = hasParam(name) && getParam(name).equals(value, ignoreCase = true)
}
