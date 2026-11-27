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

/**
 * Outcome of one ability identity lookup: the value and the source that answered it, or the reason none did.
 *
 * [T] is what the lookup site produces: a [ResolvedAbilityIdentity] for definition lookups, a catalog row
 * for stack entries and cast offers, or a row with its occurrence for activated offers.
 */
sealed interface AbilityLookup<out T> {
    data class Resolved<out T>(
        val value: T,
        val via: AbilityIdentitySource,
    ) : AbilityLookup<T>

    data class Missing(
        val reason: AbilityLookupMiss,
    ) : AbilityLookup<Nothing>

    fun valueOrNull(): T? = (this as? Resolved<T>)?.value
}

/** The source that answered an identity lookup. */
enum class AbilityIdentitySource(
    val label: String,
) {
    /** The definition's row in the source card's registry. */
    Registry("registry"),

    /** The definition's row after the registry was rebuilt from the live card. */
    RebuiltRegistry("rebuilt-registry"),

    /** A continuous `AddAbility` grant matched to its granting static's hidden row. */
    ContinuousGrant("continuous-grant"),

    /** An ability a resolved effect added, matched to the donor's hidden row. */
    ResolutionGrant("resolution-grant"),

    /** A keyword granted by a static, matched to the granting card's catalog row. */
    GrantedKeyword("granted-keyword"),

    /** The only intrinsic trigger row on the source, for a regenerated trigger definition. */
    SoleIntrinsicTrigger("sole-intrinsic-trigger"),

    /** The cleanup row recorded for a pending trigger. */
    PendingTriggerCleanup("pending-trigger-cleanup"),

    /** The row recorded when the trigger was first seen pending. */
    PendingTrigger("pending-trigger"),

    /** The row of the ability that created a delayed trigger. */
    SpawningAbility("spawning-ability"),

    /** The only triggered row on the card that created a delayed trigger. */
    SpawningSoleTrigger("spawning-sole-trigger"),

    /** The modal choice recorded for the source card. */
    ModalChoice("modal-choice"),

    /** The ability's own identity lookup, counted under its own site as well. */
    AbilityIdentity("ability-identity"),

    /** The identity recorded for a stack entry when it was put on the stack. */
    StackRecorded("stack-recorded"),

    /** A Saga chapter row. */
    SagaChapter("saga-chapter"),

    /** The fixed Paradigm delayed-trigger row. */
    ParadigmDelayed("paradigm-delayed"),

    /** A fixed keyword row for a stack entry (Decayed, Cascade, Training). */
    KeywordRow("keyword-row"),

    /** The source card's first triggered row for Discover. */
    DiscoverTrigger("discover-trigger"),

    /** A cast rail's fixed or keyword-base row. */
    RailFixed("rail-fixed"),

    /** A cast rail's per-card alternative cost row. */
    RailCatalog("rail-catalog"),

    /** The recipient's ordinal for a granted activated ability. */
    GrantOrdinal("grant-ordinal"),

    /** The printed keyword slot of the ability. */
    KeywordSlot("keyword-slot"),

    /** The first printed slot carrying the resolved row. */
    PrintedSlot("printed-slot"),

    /** The caller's fallback row, such as a basic land's mana row. */
    CallerFallback("caller-fallback"),
}

/** Why an identity lookup found no row. */
enum class AbilityLookupMiss(
    val label: String,
) {
    /** The source card has no catalog data. */
    NoCardData("no-card-data"),

    /** The definition has no row in the registry. */
    NoRow("no-row"),

    /** A stack entry fell back to its source card's row. */
    SourceCardRow("source-card-row"),

    /** The resolved row has no printed slot or grant ordinal. */
    NoOccurrence("no-occurrence"),
}
