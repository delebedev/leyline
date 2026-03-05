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
)

/**
 * Server-owned queue + event definitions for the Play blade.
 *
 * Replaces golden `play-blade-queue-config.json` (CmdType 1910) and
 * `active-events.json` (CmdType 624). Matches prod Arena server shape
 * captured 2026-03-03.
 */
object EventRegistry {

    val queues: List<QueueEntry> = listOf(
        QueueEntry("StandardRanked", "Ranked", "PlayBlade/FindMatch/Blade_Standard_Ladder", "Ladder", "Traditional_Ladder"),
        QueueEntry("StandardUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Traditional_Standard_Play", "Play", "Constructed_BestOf3"),
        QueueEntry("HistoricRanked", "Ranked", "PlayBlade/FindMatch/Blade_Historic_Ladder", "Historic_Ladder", "Traditional_Historic_Ladder"),
        QueueEntry("HistoricUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Historic_Play", "Historic_Play", "Traditional_Historic_Play"),
        QueueEntry("ExplorerRanked", "Ranked", "PlayBlade/FindMatch/Blade_Explorer_Ladder", "Explorer_Ladder", "Traditional_Explorer_Ladder"),
        QueueEntry("ExplorerUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Explorer_Play", "Explorer_Play", "Traditional_Explorer_Play"),
        QueueEntry("TimelessRanked", "Ranked", "PlayBlade/FindMatch/Blade_Timeless_Ladder", "Timeless_Ladder", "Traditional_Timeless_Ladder"),
        QueueEntry(
            "TimelessUnranked",
            "Unranked",
            "PlayBlade/FindMatch/Blade_Timeless_Play",
            "Timeless_Play",
            sideboardBO1 = "MainNav/General/Empty_String",
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
        ),
        EventDef("Timeless_Play", "Timeless Play", "Timeless", displayPriority = 58),
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
        put("CacheVersion", 1)
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
                            put("PrioritizeBannerIfPlayerHasToken", false)
                            putJsonArray("FactionSealedUXInfo") {}
                            putJsonObject("Prizes") {}
                            putJsonObject("EventComponentData") {}
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
