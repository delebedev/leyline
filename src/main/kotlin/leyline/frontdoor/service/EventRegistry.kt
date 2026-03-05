package leyline.frontdoor.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
)

/**
 * Server-owned queue + event definitions for the Play blade.
 *
 * Replaces golden `play-blade-queue-config.json` (CmdType 1910) and
 * `active-events.json` (CmdType 624). Matches prod Arena server shape
 * captured 2026-03-03.
 */
object EventRegistry {

    /** Matches real server queue config (proxy capture 2026-03-03, 14 queues). */
    val queues: List<QueueEntry> = listOf(
        // Ranked (no QueueType field emitted — client default)
        QueueEntry("StandardRanked", "Ranked", "PlayBlade/FindMatch/Blade_Standard_Ladder", "Ladder", "Traditional_Ladder"),
        QueueEntry("AlchemyRanked", "Ranked", "PlayBlade/FindMatch/Blade_Alchemy_Ladder", "Alchemy_Ladder", "Traditional_Alchemy_Ladder"),
        QueueEntry(
            "SparkAlchemyRanked", "Ranked", "Events/Event_Title_Spark_Ladder", "Spark_Alchemy_Ladder",
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
            "TimelessUnranked", "Unranked", "Events/Event_Title_Play_Timeless", "Timeless_Play",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        QueueEntry(
            "AIBotMatch", "Unranked", "Events/Event_Title_AIBotMatch", "AIBotMatch",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        // Brawl
        QueueEntry(
            "HistoricBrawl", "Brawl", "Events/Event_Title_Play_Brawl_Historic", "Play_Brawl_Historic",
            deckSizeBO1 = "Events/Deck_100commander",
            deckSizeBO3 = "MainNav/General/Empty_String",
            sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String",
        ),
        QueueEntry(
            "StandardBrawl", "Brawl", "Events/Event_Title_Play_Brawl_Bo1", "Play_Brawl",
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
    )

    fun findEvent(internalName: String): EventDef? =
        events.firstOrNull { it.internalName == internalName }

    fun toQueueConfigJson(): String = buildJsonArray {
        for (q in queues) {
            add(
                buildJsonObject {
                    put("Id", q.id)
                    if (q.queueType != "Ranked") put("QueueType", q.queueType)
                    put("LocTitle", q.locTitle)
                    put("EventNameBO1", q.eventNameBO1)
                    if (q.eventNameBO3 != null) put("EventNameBO3", q.eventNameBO3)
                    put("DeckSizeBO1", q.deckSizeBO1)
                    put("DeckSizeBO3", q.deckSizeBO3)
                    put("SideBoardBO1", q.sideboardBO1)
                    put("SideBoardBO3", q.sideboardBO3)
                },
            )
        }
    }.toString()

    fun toActiveEventsJson(): String = buildJsonObject {
        putJsonArray("DynamicFilterTags") {}
        put("CacheVersion", 2)
        putJsonArray("Events") {
            for (e in events) {
                add(
                    buildJsonObject {
                        put("InternalEventName", e.internalName)
                        put("EventState", "Active")
                        put("FormatType", e.formatType)
                        put("StartTime", "2025-01-01T00:00:00Z")
                        put("LockedTime", "2099-01-01T00:00:00Z")
                        put("ClosedTime", "2099-01-01T00:00:00Z")
                        putJsonArray("Flags") { e.flags.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("EventTags") {}
                        putJsonObject("PastEntries") {}
                        putJsonArray("EntryFees") {}
                        putJsonObject("EventUXInfo") {
                            put("PublicEventName", e.publicName)
                            put("DisplayPriority", e.displayPriority)
                            put("EventBladeBehavior", "Queue")
                            put("DeckSelectFormat", e.deckSelectFormat)
                            putJsonObject("Parameters") {}
                            putJsonArray("DynamicFilterTagIds") {}
                            put("Group", "")
                            putJsonArray("FactionSealedUXInfo") {}
                            putJsonObject("Prizes") {}
                            putJsonObject("EventComponentData") {
                                putJsonObject("DescriptionText") {
                                    put("LocKey", e.descLocKey)
                                }
                                putJsonObject("TitleRankText") {
                                    put("LocKey", e.titleLocKey)
                                }
                                putJsonObject("TimerDisplay") {}
                                putJsonObject("ResignWidget") {}
                                putJsonObject("MainButtonWidget") {}
                                putJsonObject("LossDetailsDisplay") {
                                    if (e.winCondition == "BestOf3") {
                                        put("LossDetailsType", "PlayUntilEventEnds")
                                    } else {
                                        put("LossDetailsType", "PlayUntilEventEnds")
                                    }
                                }
                            }
                        }
                        put("WinCondition", e.winCondition)
                        putJsonArray("AllowedCountryCodes") {}
                        putJsonArray("ExcludedCountryCodes") {}
                    },
                )
            }
        }
        putJsonArray("Challenges") {}
        putJsonArray("AiBotMatches") {}
    }.toString()
}
