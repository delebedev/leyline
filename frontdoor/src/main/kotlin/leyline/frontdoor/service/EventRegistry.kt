package leyline.frontdoor.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

@Serializable
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

@Serializable
data class EntryFee(val currencyType: String, val quantity: Int, val referenceId: String? = null)

@Serializable
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
    /** True for Quick Draft (BotDraft) events — internal flag, not sent on wire. */
    val isBotDraft: Boolean = false,
    val entryFees: List<EntryFee> = emptyList(),
    val dynamicFilterTagIds: List<String> = emptyList(),
    /** Shows editable deck button on event blade (sealed/draft). */
    val editableDeck: Boolean = false,
    /** Arena collation ID for limited events (sealed/draft). 0 = unknown. */
    val collationId: Int = 0,
    /** Wire EventState — null omits the field (default), "ForceActive" for always-visible events. */
    val eventState: String? = null,
    /** Precon deck IDs for InspectPreconDecksWidget (Color Challenge nodes). */
    val preconDeckIds: List<String> = emptyList(),
    /** Fixed deck selection (Color Challenge nodes use "Fixed"). */
    val deckButtonBehavior: String? = null,
) {
    val isSealed: Boolean get() = formatType == "Sealed"
}

/** Color Challenge node config — precon deck + opponent avatar (from graph definitions). */
@Serializable
data class ColorChallengeNode(val preconDeckId: String, val opponentAvatar: String)

/** Bot Match entry in the AiBotMatches array (separate from Events). */
@Serializable
data class AiBotMatchDef(
    val publicEventName: String = "AIBotMatch",
    val internalEventName: String,
    val format: String,
    val winCondition: String = "SingleElimination",
    val deckIds: List<String> = emptyList(),
    val displayPriority: Int = 99,
)

/**
 * Server-owned queue + event definitions for the Play blade.
 *
 * Data and lookups only — wire serialization lives in [leyline.frontdoor.wire.EventWireBuilder].
 * Matches prod Arena server shape captured 2026-03-03, updated 2026-03-10 with mature account data.
 *
 * ## How the client decides what to display
 *
 * Three CmdType responses feed the home/play UI:
 * - **1910 (PlayBladeQueueConfig)** — queue list for Find Match tab. Independent of events.
 * - **624 (ActiveEventsV2)** — three sub-arrays: `Events[]`, `AiBotMatches[]`, `DynamicFilterTags[]`.
 * - **623 (CoursesV2)** — player's joined/completed events (resume state).
 *
 * ### DisplayPriority controls home lobby tiles
 * Events with `bladeBehavior="Queue"` appear in Find Match only (never home tiles).
 * Non-queue events (`bladeBehavior=null`) with **positive** displayPriority render as
 * home lobby tiles, sorted by priority. Priority **-1** hides from home but keeps the
 * event in the client's registry (accessible via Events tab or programmatically).
 *
 * ### Key fields
 * - `EventBladeBehavior: "Queue"` → Find Match only. Real server uses prio -1 for all queue events.
 * - `EventBladeBehavior: null` (omitted) → home tile candidate, ordered by displayPriority.
 * - `EventState: "ForceActive"` → always visible regardless of start/lock times (ColorChallenge).
 * - `EventState: "NotActive"` → exists in registry but greyed out (test/seasonal events).
 * - `AiBotMatches[]` is a **separate array** from `Events[]` — putting AIBotMatch in both
 *   causes a client crash ("duplicate key"). Bot Match home tile on mature accounts comes from
 *   `SparkyStarterDeckDuel` in Events (prio 92), not from the AiBotMatches array (prio -1).
 *
 * ### Carousel (1600) is cosmetic — store/mastery promos, not gameplay events.
 *
 * All data loaded from fd-golden JSON resource files (proxy captures).
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
    val queues: List<QueueEntry> = loadResource("/fd-golden/queues.json")

    val events: List<EventDef> = loadResource("/fd-golden/events.json")

    /** Events for 624 response — all events except AIBotMatch (which lives in AiBotMatches array). */
    val activeEvents: List<EventDef>
        get() = events.filter { it.internalName != "AIBotMatch" }

    /** AiBotMatches array — separate from Events, rendered as "Bot Match" tile. */
    val aiBotMatches: List<AiBotMatchDef> = loadResource("/fd-golden/ai-bot-matches.json")

    /**
     * Color Challenge node → precon deck ID + opponent avatar.
     * Loaded from `fd-golden/color-challenge-nodes.json` (proxy capture 2026-03-10).
     * Used by CmdType 1703 (Graph_AdvanceNode) to start a Familiar bot match.
     */
    val colorChallengeNodes: Map<String, ColorChallengeNode> = loadResource("/fd-golden/color-challenge-nodes.json")

    fun findEvent(internalName: String): EventDef? =
        events.firstOrNull { it.internalName == internalName }

    fun isSealed(eventName: String): Boolean = findEvent(eventName)?.isSealed == true

    fun isDraft(eventName: String): Boolean = findEvent(eventName)?.isBotDraft == true

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
    )

    private inline fun <reified T> loadResource(path: String): T {
        val text = EventRegistry::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Missing resource: $path")
        return lenientJson.decodeFromString(text)
    }
}
