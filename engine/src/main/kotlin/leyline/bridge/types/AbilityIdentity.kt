package leyline.bridge.types

sealed interface AbilityDefinitionRef {
    val definitionId: Int

    data class SpellAbility(
        override val definitionId: Int,
    ) : AbilityDefinitionRef

    data class Trigger(
        override val definitionId: Int,
    ) : AbilityDefinitionRef

    data class StaticAbility(
        override val definitionId: Int,
    ) : AbilityDefinitionRef
}

enum class AbilityKeywordFamily {
    Backup,
    Mentor,
}

data class ResolvedAbilityIdentity(
    val definition: AbilityDefinitionRef,
    val abilityGrpId: Int,
    val keywordFamily: AbilityKeywordFamily? = null,
)
