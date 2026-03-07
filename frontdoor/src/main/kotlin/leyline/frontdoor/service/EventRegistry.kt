package leyline.frontdoor.service

data class QueueEntry(
    val id: String,
    val queueType: String = "Ranked",
    val locTitle: String,
    val eventNameBO1: String,
    val eventNameBO3: String? = null,
    val deckSizeBO1: String = "Events/Deck_60plus",
    val deckSizeBO3: String = "Events/Deck_60plus",
    val sideboardBO1: String = "Events/Sideboard_7minus",
    val sideboardBO3: String = "Events/Sideboard_15minus",
)

data class EventDef(
    val internalName: String,
    val publicName: String,
    val deckSelectFormat: String,
    val formatType: String = "Constructed",
    val flags: List<String> = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
    val winCondition: String = "SingleElimination",
    val displayPriority: Int = 50,
    val titleLocKey: String = "Events/Event_Title_$internalName",
    val descLocKey: String = "Events/Event_Desc_$internalName",
    /** "Queue" for Find Match events, null for Events-tab events (drafts, sealed, etc.) */
    val bladeBehavior: String? = "Queue",
    val eventTags: List<String> = emptyList(),
    val maxWins: Int? = null,
    val maxLosses: Int? = null,
)

/**
 * Server-owned queue + event definitions for the Play blade.
 *
 * Data and lookups only — wire serialization lives in [leyline.frontdoor.wire.EventWireBuilder].
 * Matches prod Arena server shape captured 2026-03-03.
 */
object EventRegistry {

    /** Arena format names that have no Forge equivalent — skip validation. */
    private val UNMAPPED_FORMATS = setOf("Timeless", "Alchemy")

    /** Arena → Forge renames (after stripping "Traditional" prefix). */
    private val ARENA_TO_FORGE = mapOf(
        "Explorer" to "Pioneer",
    )

    /**
     * Map Arena deckSelectFormat string to Forge format name, or null if unmapped.
     * "TraditionalStandard" → "Standard", "Explorer" → "Pioneer".
     */
    fun mapArenaFormat(arenaFormat: String): String? {
        val base = arenaFormat.removePrefix("Traditional")
        if (base in UNMAPPED_FORMATS) return null
        return ARENA_TO_FORGE[base] ?: base
    }

    /** Matches real server queue config (proxy capture 2026-03-03, 14 queues). */
    val queues: List<QueueEntry> = listOf(
        // Ranked (no QueueType field emitted — client default)
        QueueEntry("StandardRanked", "Ranked", "PlayBlade/FindMatch/Blade_Standard_Ladder", "Ladder", "Traditional_Ladder"),
        QueueEntry("AlchemyRanked", "Ranked", "PlayBlade/FindMatch/Blade_Alchemy_Ladder", "Alchemy_Ladder", "Traditional_Alchemy_Ladder"),
        QueueEntry(
            "SparkAlchemyRanked",
            "Ranked",
            "Events/Event_Title_Spark_Ladder",
            "Spark_Alchemy_Ladder",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        QueueEntry("HistoricRanked", "Ranked", "PlayBlade/FindMatch/Blade_Traditional_Historic_Ladder", "Historic_Ladder", "Traditional_Historic_Ladder"),
        QueueEntry("ExplorerRanked", "Ranked", "Events/Event_Title_Explorer_Ladder", "Explorer_Ladder", "Traditional_Explorer_Ladder"),
        QueueEntry("TimelessRanked", "Ranked", "Events/Event_Title_Timeless_Ladder", "Timeless_Ladder", "Traditional_Timeless_Ladder"),
        // Unranked
        QueueEntry("StandardUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Traditional_Standard_Play", "Play", "Constructed_BestOf3"),
        QueueEntry("AlchemyUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Alchemy_Play", "Alchemy_Play", "Traditional_Alchemy_Play"),
        QueueEntry("HistoricUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Traditional_Historic_Play", "Historic_Play", "Traditional_Historic_Play"),
        QueueEntry("ExplorerUnranked", "Unranked", "Events/Event_Title_Explorer_Play", "Explorer_Play", "Traditional_Explorer_Play"),
        QueueEntry(
            "TimelessUnranked",
            "Unranked",
            "Events/Event_Title_Play_Timeless",
            "Timeless_Play",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        QueueEntry(
            "AIBotMatch",
            "Unranked",
            "Events/Event_Title_AIBotMatch",
            "AIBotMatch",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        // Brawl
        QueueEntry(
            "HistoricBrawl",
            "Brawl",
            "Events/Event_Title_Play_Brawl_Historic",
            "Play_Brawl_Historic",
            deckSizeBO1 = "Events/Deck_100commander",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        QueueEntry(
            "StandardBrawl",
            "Brawl",
            "Events/Event_Title_Play_Brawl_Bo1",
            "Play_Brawl",
            deckSizeBO1 = "Events/Deck_60commander",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
    )

    val events: List<EventDef> = listOf(
        // Standard
        EventDef(
            "Ladder",
            "Standard Ranked",
            "Standard",
            displayPriority = 100,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Ladder",
            "Traditional Standard Ranked",
            "TraditionalStandard",
            displayPriority = 99,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Play", "Standard Play", "Standard", displayPriority = 90),
        EventDef(
            "Constructed_BestOf3",
            "Traditional Standard Play",
            "TraditionalStandard",
            displayPriority = 89,
            winCondition = "BestOf3",
            titleLocKey = "Events/Event_Title_Play_Standard_Bo3",
            descLocKey = "Events/Event_Desc_Traditional_Play",
        ),
        // Alchemy
        EventDef(
            "Alchemy_Ladder",
            "Alchemy Ranked",
            "Alchemy",
            displayPriority = 85,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Alchemy_Ladder",
            "Traditional Alchemy Ranked",
            "TraditionalAlchemy",
            displayPriority = 84,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Alchemy_Play", "Alchemy Play", "Alchemy", displayPriority = 83),
        EventDef(
            "Traditional_Alchemy_Play",
            "Traditional Alchemy Play",
            "TraditionalAlchemy",
            displayPriority = 82,
            winCondition = "BestOf3",
        ),
        EventDef(
            "Spark_Alchemy_Ladder",
            "Spark Alchemy Ranked",
            "SparkAlchemy",
            displayPriority = 81,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            titleLocKey = "Events/Event_Title_Spark_Ladder",
            descLocKey = "Events/Event_Desc_Spark_Ladder",
        ),
        // Historic
        EventDef(
            "Historic_Ladder",
            "Historic Ranked",
            "Historic",
            displayPriority = 80,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Historic_Ladder",
            "Traditional Historic Ranked",
            "TraditionalHistoric",
            displayPriority = 79,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Historic_Play", "Historic Play", "Historic", displayPriority = 78),
        EventDef(
            "Traditional_Historic_Play",
            "Traditional Historic Play",
            "TraditionalHistoric",
            displayPriority = 77,
            winCondition = "BestOf3",
        ),
        // Explorer
        EventDef(
            "Explorer_Ladder",
            "Explorer Ranked",
            "Explorer",
            displayPriority = 70,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Explorer_Ladder",
            "Traditional Explorer Ranked",
            "TraditionalExplorer",
            displayPriority = 69,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Explorer_Play", "Explorer Play", "Explorer", displayPriority = 68),
        EventDef(
            "Traditional_Explorer_Play",
            "Traditional Explorer Play",
            "TraditionalExplorer",
            displayPriority = 67,
            winCondition = "BestOf3",
        ),
        // Timeless
        EventDef(
            "Timeless_Ladder",
            "Timeless Ranked",
            "Timeless",
            displayPriority = 60,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Timeless_Ladder",
            "Traditional Timeless Ranked",
            "TraditionalTimeless",
            displayPriority = 59,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
            titleLocKey = "Events/Event_Title_Timeless_Traditional_Ladder",
            descLocKey = "Events/Event_Desc_Timeless_Traditional_Ladder",
        ),
        EventDef(
            "Timeless_Play",
            "Timeless Play",
            "Timeless",
            displayPriority = 58,
            titleLocKey = "Events/Event_Title_Play_Timeless",
            descLocKey = "Events/Event_Desc_Play_Timeless",
        ),
        // Brawl
        EventDef(
            "Play_Brawl_Historic",
            "Historic Brawl",
            "HistoricBrawl",
            displayPriority = 50,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            titleLocKey = "Events/Event_Title_Play_Brawl_Historic",
            descLocKey = "Events/Event_Desc_Play_Brawl_Historic",
        ),
        EventDef(
            "Play_Brawl",
            "Standard Brawl",
            "StandardBrawl",
            displayPriority = 49,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            titleLocKey = "Events/Event_Title_Play_Brawl_Bo1",
            descLocKey = "Events/Event_Desc_Play_Brawl_Bo1",
        ),
        // AIBotMatch
        EventDef(
            "AIBotMatch",
            "Bot Match",
            "Standard",
            displayPriority = 40,
            flags = listOf("IsArenaPlayModeEvent", "IsAiBotMatch", "SkipDeckValidation"),
        ),
        // Non-queue events (Events tab)
        EventDef(
            "Jump_In_2024",
            "Jump_In",
            "Draft_Rebalanced",
            formatType = "Draft",
            displayPriority = 80,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "IsPreconEvent"),
            bladeBehavior = null,
            eventTags = listOf("JumpIn", "Limited"),
            titleLocKey = "Events/Event_Title_Jump_In",
            descLocKey = "Events/Event_Desc_Jump_In",
        ),
        // Sealed
        EventDef(
            "Sealed_FDN_20260307",
            "Sealed FDN",
            "Sealed",
            formatType = "Sealed",
            displayPriority = 75,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            bladeBehavior = null,
            eventTags = listOf("Limited"),
            maxWins = 7,
            maxLosses = 3,
        ),
    )

    fun findEvent(internalName: String): EventDef? =
        events.firstOrNull { it.internalName == internalName }

    /** Look up Forge format name for an Arena event. Null = no restriction (e.g. AIBotMatch with SkipDeckValidation). */
    fun forgeFormatFor(eventName: String): String? {
        val event = findEvent(eventName) ?: return null
        if (event.flags.contains("SkipDeckValidation")) return null
        return mapArenaFormat(event.deckSelectFormat)
    }

    /**
     * Default courses — events the player has "participated in".
     * Real server returns only events the player entered; we seed a few common ones.
     * module=CreateMatch means active (shows "Resume"), module=Complete means finished.
     */
    val defaultCourses = listOf(
        "Ladder" to "Complete",
        "Play" to "CreateMatch",
        "Jump_In_2024" to "CreateMatch",
    )
}
