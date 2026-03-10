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

data class EntryFee(val currencyType: String, val quantity: Int, val referenceId: String? = null)

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
data class ColorChallengeNode(val preconDeckId: String, val opponentAvatar: String)

/** Bot Match entry in the AiBotMatches array (separate from Events). */
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
        // Queue-backing events — all displayPriority=-1 per real server (mature account capture 2026-03-10)
        // Standard
        EventDef(
            "Ladder",
            "Standard Ranked",
            "Standard",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Ladder",
            "Traditional Standard Ranked",
            "TraditionalStandard",
            displayPriority = -1,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Play", "Standard Play", "Standard", displayPriority = -1),
        EventDef(
            "Constructed_BestOf3",
            "Traditional Standard Play",
            "TraditionalStandard",
            displayPriority = -1,
            winCondition = "BestOf3",
            titleLocKey = "Events/Event_Title_Play_Standard_Bo3",
            descLocKey = "Events/Event_Desc_Traditional_Play",
        ),
        // Alchemy
        EventDef(
            "Alchemy_Ladder",
            "Alchemy Ranked",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Alchemy_Ladder",
            "Traditional Alchemy Ranked",
            "TraditionalAlchemy",
            displayPriority = -1,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Alchemy_Play", "Alchemy Play", "Alchemy", displayPriority = -1),
        EventDef(
            "Traditional_Alchemy_Play",
            "Traditional Alchemy Play",
            "TraditionalAlchemy",
            displayPriority = -1,
            winCondition = "BestOf3",
        ),
        EventDef(
            "Spark_Alchemy_Ladder",
            "Spark Alchemy Ranked",
            "SparkAlchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            titleLocKey = "Events/Event_Title_Spark_Ladder",
            descLocKey = "Events/Event_Desc_Spark_Ladder",
        ),
        // Historic
        EventDef(
            "Historic_Ladder",
            "Historic Ranked",
            "Historic",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Historic_Ladder",
            "Traditional Historic Ranked",
            "TraditionalHistoric",
            displayPriority = -1,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Historic_Play", "Historic Play", "Historic", displayPriority = -1),
        EventDef(
            "Traditional_Historic_Play",
            "Traditional Historic Play",
            "TraditionalHistoric",
            displayPriority = -1,
            winCondition = "BestOf3",
        ),
        // Explorer
        EventDef(
            "Explorer_Ladder",
            "Explorer Ranked",
            "Explorer",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Explorer_Ladder",
            "Traditional Explorer Ranked",
            "TraditionalExplorer",
            displayPriority = -1,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
        ),
        EventDef("Explorer_Play", "Explorer Play", "Explorer", displayPriority = -1),
        EventDef(
            "Traditional_Explorer_Play",
            "Traditional Explorer Play",
            "TraditionalExplorer",
            displayPriority = -1,
            winCondition = "BestOf3",
        ),
        // Timeless
        EventDef(
            "Timeless_Ladder",
            "Timeless Ranked",
            "Timeless",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
        ),
        EventDef(
            "Traditional_Timeless_Ladder",
            "Traditional Timeless Ranked",
            "TraditionalTimeless",
            displayPriority = -1,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            winCondition = "BestOf3",
            titleLocKey = "Events/Event_Title_Timeless_Traditional_Ladder",
            descLocKey = "Events/Event_Desc_Timeless_Traditional_Ladder",
        ),
        EventDef(
            "Timeless_Play",
            "Timeless Play",
            "Timeless",
            displayPriority = -1,
            titleLocKey = "Events/Event_Title_Play_Timeless",
            descLocKey = "Events/Event_Desc_Play_Timeless",
        ),
        // Brawl
        EventDef(
            "Play_Brawl_Historic",
            "Historic Brawl",
            "HistoricBrawl",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            titleLocKey = "Events/Event_Title_Play_Brawl_Historic",
            descLocKey = "Events/Event_Desc_Play_Brawl_Historic",
        ),
        EventDef(
            "Play_Brawl",
            "Standard Brawl",
            "StandardBrawl",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            titleLocKey = "Events/Event_Title_Play_Brawl_Bo1",
            descLocKey = "Events/Event_Desc_Play_Brawl_Bo1",
        ),
        // AIBotMatch — kept for findEvent() lookups by gameplay code
        EventDef(
            "AIBotMatch",
            "Bot Match",
            "Standard",
            displayPriority = 40,
            flags = listOf("IsArenaPlayModeEvent", "IsAiBotMatch", "SkipDeckValidation"),
        ),
        // SparkyStarterDeckDuel — Bot Match home tile (mature account shape, prio 92)
        EventDef(
            "SparkyStarterDeckDuel",
            "SparkyStarterDeckDuel",
            "AllZeroes",
            displayPriority = 92,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "AllowUncollectedCards", "IsPreconEvent", "IsAiBotMatch"),
            bladeBehavior = null,
            eventTags = listOf("AiBotMatch"),
            titleLocKey = "Events/Event_Title_SparkyChallenge",
            descLocKey = "Events/Event_Desc_SparkyChallenge",
        ),
        // Color Challenge — main tile (ForceActive, no blade behavior)
        EventDef(
            "ColorChallenge",
            "ColorChallenge",
            "Alchemy",
            displayPriority = 93,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "IsPreconEvent"),
            bladeBehavior = null,
            eventState = "ForceActive",
            titleLocKey = "Events/Event_Title_ColorChallenge",
            descLocKey = "Events/Event_Desc_ColorChallenge",
        ),
        // Color Challenge per-color nodes (hidden, priority -1)
        EventDef(
            "ColorChallenge_Node5_W",
            "ColorChallenge_Node5_W",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "IsPreconEvent"),
            preconDeckIds = listOf("af38619e-72fa-4e54-9b92-11551eed8c28"),
            deckButtonBehavior = "Fixed",
            titleLocKey = "Events/Event_Title_Alchemy_Play",
            descLocKey = "Events/Event_Desc_Alchemy_Play",
        ),
        EventDef(
            "ColorChallenge_Node5_U",
            "ColorChallenge_Node5_U",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "IsPreconEvent"),
            preconDeckIds = listOf("d073372e-ba43-440a-a98e-9201803a5e15"),
            deckButtonBehavior = "Fixed",
            titleLocKey = "Events/Event_Title_Alchemy_Play",
            descLocKey = "Events/Event_Desc_Alchemy_Play",
        ),
        EventDef(
            "ColorChallenge_Node5_B",
            "ColorChallenge_Node5_B",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "IsPreconEvent"),
            preconDeckIds = listOf("6c40709b-6e87-4973-a645-bfada529f992"),
            deckButtonBehavior = "Fixed",
            titleLocKey = "Events/Event_Title_Alchemy_Play",
            descLocKey = "Events/Event_Desc_Alchemy_Play",
        ),
        EventDef(
            "ColorChallenge_Node5_R",
            "ColorChallenge_Node5_R",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "IsPreconEvent"),
            preconDeckIds = listOf("4792e924-c693-4b06-b4e7-31bfeedd610d"),
            deckButtonBehavior = "Fixed",
            titleLocKey = "Events/Event_Title_Alchemy_Play",
            descLocKey = "Events/Event_Desc_Alchemy_Play",
        ),
        EventDef(
            "ColorChallenge_Node5_G",
            "ColorChallenge_Node5_G",
            "Alchemy",
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "SkipDeckValidation", "IsPreconEvent"),
            preconDeckIds = listOf("c7df76d9-1c2a-4b25-b41a-85ed0424c86e"),
            deckButtonBehavior = "Fixed",
            titleLocKey = "Events/Event_Title_Alchemy_Play",
            descLocKey = "Events/Event_Desc_Alchemy_Play",
        ),
        // Non-queue events (Events tab) — disabled for new-account mode
        EventDef(
            "Jump_In_2024",
            "Jump_In",
            "Draft_Rebalanced",
            formatType = "Draft",
            displayPriority = -1,
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
            displayPriority = -1,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards"),
            bladeBehavior = null,
            eventTags = listOf("Sealed", "Limited"),
            titleLocKey = "Events/Event_Title_Sealed_FDN",
            descLocKey = "Events/Event_Desc_Sealed_FDN",
            maxWins = 7,
            maxLosses = 3,
            editableDeck = true,
        ),
        // Quick Draft
        EventDef(
            "QuickDraft_ECL_20260223",
            "ECL_Quick_Draft",
            "Draft",
            formatType = "Draft",
            displayPriority = 61,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"),
            bladeBehavior = null,
            eventTags = listOf("QuickDraft", "Limited"),
            titleLocKey = "Events/Event_Title_ECL_Quick_Draft",
            descLocKey = "Events/Event_Desc_ECL_Quick_Draft",
            maxWins = 7,
            maxLosses = 3,
            isBotDraft = true,
            entryFees = listOf(
                EntryFee("Gold", 5000),
                EntryFee("Gem", 750),
            ),
            dynamicFilterTagIds = listOf("ECL Limited"),
            editableDeck = true,
            collationId = 100058, // TODO(#62): look up from client card DB
        ),
    )

    /** Events for 624 response — all events except AIBotMatch (which lives in AiBotMatches array). */
    val activeEvents: List<EventDef>
        get() = events.filter { it.internalName != "AIBotMatch" }

    /** AiBotMatches array — separate from Events, rendered as "Bot Match" tile. */
    val aiBotMatches = listOf(
        AiBotMatchDef(
            internalEventName = "AIBotMatch_Rebalanced",
            format = "DirectGameAlchemy",
            deckIds = listOf(
                "e2aaafa8-a633-4eb4-bb94-4309dd915a6a",
                "92f8315c-4f38-4c6c-b233-f0aa809d33b4",
                "557335a3-4cbd-4c9f-8e31-0939f18b449b",
                "437eda14-8f28-4667-95b8-fd5ac97c583d",
                "ee38d813-aba6-4831-8c5e-1c54f3ef84a9",
                "9ac025df-75ec-4e10-9fb0-3b6c796a19c9",
                "8ef7d808-fec0-42b4-bfc1-323f56633375",
            ),
        ),
        AiBotMatchDef(
            internalEventName = "AIBotMatch",
            format = "DirectGame",
            deckIds = listOf(
                "e2aaafa8-a633-4eb4-bb94-4309dd915a6a",
                "92f8315c-4f38-4c6c-b233-f0aa809d33b4",
                "557335a3-4cbd-4c9f-8e31-0939f18b449b",
                "437eda14-8f28-4667-95b8-fd5ac97c583d",
                "ee38d813-aba6-4831-8c5e-1c54f3ef84a9",
                "9ac025df-75ec-4e10-9fb0-3b6c796a19c9",
                "8ef7d808-fec0-42b4-bfc1-323f56633375",
            ),
        ),
    )

    /**
     * Color Challenge node → precon deck ID (from graph definitions, proxy capture 2026-03-10).
     * Used by CmdType 1703 (Graph_AdvanceNode) to start a Familiar bot match.
     */
    val colorChallengeNodes: Map<String, ColorChallengeNode> = mapOf(
        "white01" to ColorChallengeNode("2b165bff-4ea9-4e90-b5a5-3e067beb6584", "Avatar_Basic_ChandraNalaar"),
        "white02" to ColorChallengeNode("9eca8462-f83a-4fb2-b22e-697344d62934", "Avatar_Basic_LilianaVess"),
        "white03" to ColorChallengeNode("2c583e9b-69c2-4528-aac5-cf42dbaaf647", "Avatar_Basic_JaceBeleren"),
        "white04" to ColorChallengeNode("bbb9cb46-0f82-4b86-8ccd-ba442a9f7a59", "Avatar_Basic_VivienReid"),
        "blue01" to ColorChallengeNode("8460b1c2-0522-422a-866e-ece751377e22", "Avatar_Basic_LilianaVess"),
        "blue02" to ColorChallengeNode("a6d166b0-81d7-46ee-a54b-8dd7f4c484e1", "Avatar_Basic_VivienReid"),
        "blue03" to ColorChallengeNode("d695efa7-dcaa-4b1c-b264-3f45edc07dbc", "Avatar_Basic_AjaniGoldmane"),
        "blue04" to ColorChallengeNode("24b08b3c-2cb8-46d8-a2e9-4705fdc445b2", "Avatar_Basic_ChandraNalaar"),
        "black01" to ColorChallengeNode("112c3de6-1049-4f1a-9c84-0cf2c298c67c", "Avatar_Basic_VivienReid"),
        "black02" to ColorChallengeNode("c2f8212d-b7d5-4da0-9fd4-37fb2d24ed2f", "Avatar_Basic_AjaniGoldmane"),
        "black03" to ColorChallengeNode("0f1710d9-3ae7-45d9-ae18-dd96efc26eab", "Avatar_Basic_ChandraNalaar"),
        "black04" to ColorChallengeNode("7a85a9b9-8cc6-4625-bc92-f1f64609fa59", "Avatar_Basic_JaceBeleren"),
        "red01" to ColorChallengeNode("8b095570-a8e6-49d5-a564-f30b46698c0b", "Avatar_Basic_JaceBeleren"),
        "red02" to ColorChallengeNode("63a1acaf-36e0-4c6b-8854-cf2ca8c5709e", "Avatar_Basic_LilianaVess"),
        "red03" to ColorChallengeNode("a42c9d36-05b7-40a6-afc2-f662c236f53a", "Avatar_Basic_VivienReid"),
        "red04" to ColorChallengeNode("05b73a9f-cecb-4cbd-a370-b20ba6a07d14", "Avatar_Basic_AjaniGoldmane"),
        "green01" to ColorChallengeNode("12eb4102-f51f-4853-b9f9-5dca962352f2", "Avatar_Basic_AjaniGoldmane"),
        "green02" to ColorChallengeNode("a646ac00-fbe5-4dbd-85f5-1b4d1a838f5a", "Avatar_Basic_ChandraNalaar"),
        "green03" to ColorChallengeNode("40616755-71ba-4d3a-bb4b-10bd67114616", "Avatar_Basic_JaceBeleren"),
        "green04" to ColorChallengeNode("ba302e86-efaf-4881-aec3-d22e90274363", "Avatar_Basic_LilianaVess"),
    )

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
}
