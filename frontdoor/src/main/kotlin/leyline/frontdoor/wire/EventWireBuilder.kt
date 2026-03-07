package leyline.frontdoor.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.DeckCard
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

    fun toDefaultCoursesJson(courses: List<Pair<String, String>>): String = buildJsonObject {
        putJsonArray("Courses") {
            for ((eventName, module) in courses) {
                add(buildCourseJson(eventName, module))
            }
        }
    }.toString()

    fun toCoursesJson(courses: List<Course>): String = buildJsonObject {
        putJsonArray("Courses") {
            for (course in courses) {
                add(buildCourseJson(course, includeLosses = true))
            }
        }
    }.toString()

    fun buildCourseJson(
        course: Course,
        includeDeck: Boolean = true,
        includeWins: Boolean = true,
        includeLosses: Boolean = false,
    ) = buildJsonObject {
        put("CourseId", course.id.value)
        put("InternalEventName", course.eventName)
        put("CurrentModule", course.module.wireName())
        put("ModulePayload", "")
        putJsonObject("CourseDeckSummary") {
            val s = course.deckSummary
            put("DeckId", s?.deckId?.value ?: "00000000-0000-0000-0000-000000000000")
            put("Name", s?.name ?: "")
            putJsonArray("Attributes") {}
            put("DeckTileId", s?.tileId ?: 0)
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
        if (includeDeck) {
            putJsonObject("CourseDeck") {
                val d = course.deck
                putJsonArray("MainDeck") {
                    d?.mainDeck?.forEach { add(buildDeckCardJson(it)) }
                }
                putJsonArray("ReducedSideboard") {}
                putJsonArray("Sideboard") {
                    d?.sideboard?.forEach { add(buildDeckCardJson(it)) }
                }
                putJsonArray("CommandZone") {}
                putJsonArray("Companions") {}
                putJsonArray("CardSkins") {}
            }
        }
        if (includeWins && course.wins > 0) put("CurrentWins", course.wins)
        if (includeLosses && course.losses > 0) put("CurrentLosses", course.losses)
        putJsonArray("CardPool") {
            course.cardPool.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("CardPoolByCollation") {
            for (cp in course.cardPoolByCollation) {
                add(
                    buildJsonObject {
                        put("CollationId", cp.collationId)
                        putJsonArray("CardPool") {
                            cp.cardPool.forEach { add(JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        putJsonArray("CardStyles") {}
    }

    fun buildJoinResponse(course: Course): String = buildJsonObject {
        put("Course", buildCourseJson(course, includeDeck = false, includeWins = false))
        putJsonObject("InventoryInfo") {
            put("SeqId", 1)
            putJsonArray("Changes") {}
            put("Gems", 0)
            put("Gold", 0)
            put("TotalVaultProgress", 0)
            put("WildCardCommons", 0)
            put("WildCardUnCommons", 0)
            put("WildCardRares", 0)
            put("WildCardMythics", 0)
            putJsonArray("Boosters") {}
            putJsonObject("Vouchers") {}
            putJsonObject("Cosmetics") {}
            putJsonObject("CustomTokens") {}
            putJsonArray("PrizeWallsUnlocked") {}
        }
    }.toString()

    fun buildMatchResultReport(course: Course): String = buildJsonObject {
        put("CurrentModule", course.module.wireName())
        put("FoundMatch", true)
        putJsonObject("InventoryInfo") {
            put("SeqId", 1)
            putJsonArray("Changes") {}
            put("Gems", 0)
            put("Gold", 0)
            put("TotalVaultProgress", 0)
            put("WildCardCommons", 0)
            put("WildCardUnCommons", 0)
            put("WildCardRares", 0)
            put("WildCardMythics", 0)
            putJsonArray("Boosters") {}
            putJsonObject("Vouchers") {}
            putJsonObject("Cosmetics") {}
            putJsonObject("CustomTokens") {}
        }
        putJsonArray("questUpdates") {}
        putJsonObject("periodicRewardsProgress") {}
    }.toString()

    private fun buildEventJson(e: EventDef) = buildJsonObject {
        put("InternalEventName", e.internalName)
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
                    if (e.maxLosses != null) {
                        put("Games", e.maxLosses)
                    } else {
                        put("LossDetailsType", "PlayUntilEventEnds")
                    }
                }
                if (e.formatType == "Sealed") {
                    putJsonObject("SelectedDeckWidget") {
                        put("DeckButtonBehavior", "Editable")
                        put("ShowCopyDeckButton", true)
                    }
                }
            }
        }
        put("WinCondition", e.winCondition)
        putJsonArray("AllowedCountryCodes") {}
        putJsonArray("ExcludedCountryCodes") {}
    }

    private fun buildDeckCardJson(card: DeckCard) = buildJsonObject {
        put("cardId", card.grpId)
        put("quantity", card.quantity)
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
