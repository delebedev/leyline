package leyline.frontdoor.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import leyline.frontdoor.service.EventDef
import leyline.frontdoor.service.QueueEntry

/**
 * Translates [EventDef] / [QueueEntry] config to Arena wire JSON shapes.
 *
 * Three endpoints: queue config (CmdType 1910), active events (624), courses (623).
 * Parallel to [DeckWireBuilder] / [PlayerWireBuilder].
 */
object EventWireBuilder {

    fun toQueueConfigJson(queues: List<QueueEntry>): String = buildJsonArray {
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

    fun toActiveEventsJson(events: List<EventDef>): String = buildJsonObject {
        putJsonArray("DynamicFilterTags") {}
        put("CacheVersion", 2)
        putJsonArray("Events") {
            for (e in events) {
                add(buildEventJson(e))
            }
        }
        putJsonArray("Challenges") {}
        putJsonArray("AiBotMatches") {}
    }.toString()

    fun toCoursesJson(courses: List<Pair<String, String>>): String = buildJsonObject {
        putJsonArray("Courses") {
            for ((eventName, module) in courses) {
                add(buildCourseJson(eventName, module))
            }
        }
    }.toString()

    private fun buildEventJson(e: EventDef) = buildJsonObject {
        put("InternalEventName", e.internalName)
        put("EventState", "Active")
        put("FormatType", e.formatType)
        put("StartTime", "2025-01-01T00:00:00Z")
        put("LockedTime", "2099-01-01T00:00:00Z")
        put("ClosedTime", "2099-01-01T00:00:00Z")
        putJsonArray("Flags") { e.flags.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("EventTags") { e.eventTags.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("PastEntries") {}
        putJsonArray("EntryFees") {}
        putJsonObject("EventUXInfo") {
            put("PublicEventName", e.publicName)
            put("DisplayPriority", e.displayPriority)
            if (e.bladeBehavior != null) put("EventBladeBehavior", e.bladeBehavior)
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
                    put("LossDetailsType", "PlayUntilEventEnds")
                }
            }
        }
        put("WinCondition", e.winCondition)
        putJsonArray("AllowedCountryCodes") {}
        putJsonArray("ExcludedCountryCodes") {}
    }

    private fun buildCourseJson(eventName: String, module: String) = buildJsonObject {
        put("CourseId", "00000000-0000-0000-0000-000000000000")
        put("InternalEventName", eventName)
        put("CurrentModule", module)
        put("ModulePayload", "")
        putJsonObject("CourseDeckSummary") {
            put("DeckId", "00000000-0000-0000-0000-000000000000")
            put("Name", "")
            putJsonArray("Attributes") {}
            put("DeckTileId", 0)
            put("DeckArtId", 0)
            putJsonObject("FormatLegalities") {}
            putJsonObject("PreferredCosmetics") {
                put("Avatar", "")
                put("Sleeve", "")
                put("Pet", "")
                put("Title", "")
                putJsonArray("Emotes") {}
            }
            putJsonArray("DeckValidationSummaries") {}
            putJsonObject("UnownedCards") {}
        }
        putJsonObject("CourseDeck") {
            putJsonArray("MainDeck") {}
            putJsonArray("ReducedSideboard") {}
            putJsonArray("Sideboard") {}
            putJsonArray("CommandZone") {}
            putJsonArray("Companions") {}
            putJsonArray("CardSkins") {}
        }
        putJsonArray("CardPool") {}
        putJsonArray("CardPoolByCollation") {}
        putJsonArray("CardStyles") {}
    }
}
