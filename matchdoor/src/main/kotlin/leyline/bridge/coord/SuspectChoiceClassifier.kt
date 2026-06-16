package leyline.bridge.coord

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility

/** Classifies Forge choose-card scripts that resolve by suspecting the chosen card. */
internal object SuspectChoiceClassifier {
    fun isSuspectChoice(sa: SpellAbility?): Boolean =
        sa?.api == ApiType.ChooseCard &&
            sa.usesTriggeredCards() &&
            sa.subAbilityChain().any { sub -> sub.suspectsChosenCard() }

    private fun SpellAbility.usesTriggeredCards(): Boolean =
        hasParam("DefinedCards") &&
            paramTokens("DefinedCards").any { token ->
                token.equals("TriggeredCards", ignoreCase = true) ||
                    token.startsWith("TriggeredCards.", ignoreCase = true)
            }

    private fun SpellAbility.suspectsChosenCard(): Boolean =
        api == ApiType.AlterAttribute &&
            paramTokens("Defined").any { it.equals("ChosenCard", ignoreCase = true) } &&
            paramTokens("Attributes").any { attr ->
                attr.equals("Suspect", ignoreCase = true) ||
                    attr.equals("Suspected", ignoreCase = true)
            } &&
            !hasParamValue("Activate", "False")

    private fun SpellAbility.subAbilityChain(): Sequence<SpellAbility> =
        generateSequence(getSubAbility() as? SpellAbility) { sub -> sub.getSubAbility() as? SpellAbility }
            .take(16)

    private fun SpellAbility.hasParamValue(
        name: String,
        value: String,
    ): Boolean = hasParam(name) && getParam(name).equals(value, ignoreCase = true)

    private fun SpellAbility.paramTokens(name: String): List<String> =
        if (!hasParam(name)) {
            emptyList()
        } else {
            getParam(name)
                .split(',', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
}
