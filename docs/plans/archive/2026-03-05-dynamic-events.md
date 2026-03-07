# Dynamic Event Serving + Format Plumbing — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace golden queue/event JSON with server-owned EventRegistry. Wire eventName from client through match creation. All constructed formats visible in Play blade.

**Architecture:** `EventRegistry` object defines queue entries + event definitions as Kotlin data classes, builds JSON responses. `FrontDoorHandler` uses EventRegistry for CmdType 1910/624 instead of golden files. CmdType 612 extracts `eventName`, threads it through `MatchInfo` → `MatchCreated` → `MatchHandler`.

**Tech Stack:** Kotlin, kotlinx.serialization (buildJsonObject/buildJsonArray), Kotest FunSpec, Netty EmbeddedChannel.

**Safety:** Existing `FrontDoorHandlerTest` wire tests are the behavioral contract. Run `just test-fd` between tasks, `just test-gate` before commits.

---

### Task 1: EventRegistry with queue config JSON builder

**Files:**
- Create: `src/main/kotlin/leyline/frontdoor/service/EventRegistry.kt`
- Create: `src/test/kotlin/leyline/frontdoor/service/EventRegistryTest.kt`

**Step 1: Write EventRegistryTest**

```kotlin
package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag

class EventRegistryTest : FunSpec({
    tags(FdTag)

    val json = Json { ignoreUnknownKeys = true }

    test("queue config JSON is a valid array with all queue entries") {
        val result = EventRegistry.toQueueConfigJson()
        val arr = json.parseToJsonElement(result).jsonArray
        arr shouldHaveAtLeastSize 9 // 8 format queues + AIBotMatch

        // Check StandardRanked entry shape
        val std = arr.first { it.jsonObject["Id"]?.jsonPrimitive?.content == "StandardRanked" }.jsonObject
        std["EventNameBO1"]?.jsonPrimitive?.content shouldBe "Ladder"
        std["EventNameBO3"]?.jsonPrimitive?.content shouldBe "Traditional_Ladder"
        std["DeckSizeBO1"]?.jsonPrimitive?.content shouldBe "Events/Deck_60plus"
    }

    test("queue config includes AIBotMatch") {
        val result = EventRegistry.toQueueConfigJson()
        result shouldContain "AIBotMatch"
    }

    test("active events JSON has Events array with all events") {
        val result = EventRegistry.toActiveEventsJson()
        val obj = json.parseToJsonElement(result).jsonObject
        val events = obj["Events"]?.jsonArray ?: error("no Events")
        events shouldHaveAtLeastSize 13 // BO1 + BO3 for each format + AIBotMatch

        // Check Ladder event shape
        val ladder = events.first {
            it.jsonObject["InternalEventName"]?.jsonPrimitive?.content == "Ladder"
        }.jsonObject
        ladder["FormatType"]?.jsonPrimitive?.content shouldBe "Constructed"
        ladder["EventState"]?.jsonPrimitive?.content shouldBe "Active"
        val ux = ladder["EventUXInfo"]?.jsonObject ?: error("no EventUXInfo")
        ux["DeckSelectFormat"]?.jsonPrimitive?.content shouldBe "Standard"
        ux["Group"]?.jsonPrimitive?.content shouldBe "" // must not be null
    }

    test("every event has non-null Group in EventUXInfo") {
        val result = EventRegistry.toActiveEventsJson()
        val events = json.parseToJsonElement(result).jsonObject["Events"]!!.jsonArray
        for (event in events) {
            val name = event.jsonObject["InternalEventName"]?.jsonPrimitive?.content
            val group = event.jsonObject["EventUXInfo"]?.jsonObject?.get("Group")
            check(group != null) { "Event $name has null Group — client will NRE" }
        }
    }

    test("findEvent returns known event") {
        val event = EventRegistry.findEvent("Ladder")
        event shouldBe EventRegistry.events.first { it.internalName == "Ladder" }
    }

    test("findEvent returns null for unknown") {
        EventRegistry.findEvent("NonExistent") shouldBe null
    }
})
```

**Step 2: Run test — expect FAIL (EventRegistry doesn't exist)**

Run: `just test-fd`

**Step 3: Write EventRegistry.kt**

```kotlin
package leyline.frontdoor.service

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
        QueueEntry("TimelessUnranked", "Unranked", "PlayBlade/FindMatch/Blade_Timeless_Play", "Timeless_Play",
            deckSizeBO1 = "Events/Deck_60plus", sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String"),
        QueueEntry("AIBotMatch", "Unranked", "Events/Event_Title_AIBotMatch", "AIBotMatch",
            deckSizeBO3 = "MainNav/General/Empty_String", sideboardBO1 = "MainNav/General/Empty_String",
            sideboardBO3 = "MainNav/General/Empty_String"),
    )

    val events: List<EventDef> = listOf(
        // Standard
        EventDef("Ladder", "Standard Ranked", "Standard", displayPriority = 100,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked")),
        EventDef("Traditional_Ladder", "Traditional Standard Ranked", "TraditionalStandard", displayPriority = 99,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"), winCondition = "BestOf3"),
        EventDef("Play", "Standard Play", "Standard", displayPriority = 90),
        EventDef("Constructed_BestOf3", "Traditional Standard Play", "TraditionalStandard", displayPriority = 89,
            winCondition = "BestOf3"),
        // Historic
        EventDef("Historic_Ladder", "Historic Ranked", "Historic", displayPriority = 80,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked")),
        EventDef("Traditional_Historic_Ladder", "Traditional Historic Ranked", "TraditionalHistoric", displayPriority = 79,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"), winCondition = "BestOf3"),
        EventDef("Historic_Play", "Historic Play", "Historic", displayPriority = 78),
        EventDef("Traditional_Historic_Play", "Traditional Historic Play", "TraditionalHistoric", displayPriority = 77,
            winCondition = "BestOf3"),
        // Explorer
        EventDef("Explorer_Ladder", "Explorer Ranked", "Explorer", displayPriority = 70,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked")),
        EventDef("Traditional_Explorer_Ladder", "Traditional Explorer Ranked", "TraditionalExplorer", displayPriority = 69,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"), winCondition = "BestOf3"),
        EventDef("Explorer_Play", "Explorer Play", "Explorer", displayPriority = 68),
        EventDef("Traditional_Explorer_Play", "Traditional Explorer Play", "TraditionalExplorer", displayPriority = 67,
            winCondition = "BestOf3"),
        // Timeless
        EventDef("Timeless_Ladder", "Timeless Ranked", "Timeless", displayPriority = 60,
            flags = listOf("IsArenaPlayModeEvent", "UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked")),
        EventDef("Traditional_Timeless_Ladder", "Traditional Timeless Ranked", "TraditionalTimeless", displayPriority = 59,
            flags = listOf("UpdateQuests", "UpdateDailyWeeklyRewards", "Ranked"), winCondition = "BestOf3"),
        EventDef("Timeless_Play", "Timeless Play", "Timeless", displayPriority = 58),
        // AIBotMatch
        EventDef("AIBotMatch", "Bot Match", "Standard", displayPriority = 40,
            flags = listOf("IsArenaPlayModeEvent", "IsAiBotMatch", "SkipDeckValidation")),
    )

    fun findEvent(internalName: String): EventDef? =
        events.firstOrNull { it.internalName == internalName }

    fun toQueueConfigJson(): String = buildJsonArray {
        for (q in queues) {
            add(buildJsonObject {
                put("Id", q.id)
                if (q.queueType != "Ranked") put("QueueType", q.queueType)
                put("LocTitle", q.locTitle)
                put("EventNameBO1", q.eventNameBO1)
                if (q.eventNameBO3 != null) put("EventNameBO3", q.eventNameBO3)
                put("DeckSizeBO1", q.deckSizeBO1)
                put("DeckSizeBO3", q.deckSizeBO3)
                put("SideBoardBO1", q.sideboardBO1)
                put("SideBoardBO3", q.sideboardBO3)
            })
        }
    }.toString()

    fun toActiveEventsJson(): String = buildJsonObject {
        putJsonArray("DynamicFilterTags") {}
        put("CacheVersion", 1)
        putJsonArray("Events") {
            for (e in events) {
                add(buildJsonObject {
                    put("InternalEventName", e.internalName)
                    put("EventState", "Active")
                    put("FormatType", e.formatType)
                    put("StartTime", "2025-01-01T00:00:00Z")
                    put("LockedTime", "2099-01-01T00:00:00Z")
                    put("ClosedTime", "2099-01-01T00:00:00Z")
                    putJsonArray("Flags") { e.flags.forEach { add(it) } }
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
                })
            }
        }
        putJsonArray("Challenges") {}
        putJsonArray("AiBotMatches") {}
    }.toString()
}
```

**Step 4: Run `just test-fd`**

Expected: EventRegistryTest passes, existing FD tests still pass.

**Step 5: Commit**

```
feat(fd): add EventRegistry — server-owned queue + event definitions
```

---

### Task 2: Wire EventRegistry into FrontDoorHandler

**Files:**
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` (lines 188-197)
- Modify: `src/main/kotlin/leyline/frontdoor/GoldenData.kt` (remove 2 fields)
- Modify: `src/test/kotlin/leyline/frontdoor/FrontDoorHandlerTest.kt` (strengthen 1910/624 tests)

**Step 1: Update FrontDoorHandlerTest for richer 1910/624 assertions**

In `FrontDoorHandlerTest.kt`, replace the existing tests at lines 225-237:

```kotlin
test("CmdType 1910 - PlayBladeQueueConfig has all format queues") {
    val ch = fdChannel()
    val msg = ch.sendCmd(1910)
    val arr = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonArray
    arr.shouldNotBeEmpty()
    // Must contain at least Standard + Historic + Explorer + Timeless queues + AIBotMatch
    val ids = arr.map { it.jsonObject["Id"]?.jsonPrimitive?.content }
    ids shouldContain "StandardRanked"
    ids shouldContain "HistoricRanked"
    ids shouldContain "ExplorerRanked"
    ids shouldContain "TimelessRanked"
    ids shouldContain "AIBotMatch"
}

test("CmdType 624 - ActiveEventsV2 has events for all formats") {
    val ch = fdChannel()
    val msg = ch.sendCmd(624)
    val obj = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
    val events = obj["Events"]?.jsonArray
    events.shouldNotBeNull()
    events.shouldNotBeEmpty()
    val names = events.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
    names shouldContain "Ladder"
    names shouldContain "Historic_Ladder"
    names shouldContain "Explorer_Ladder"
    names shouldContain "Timeless_Ladder"
    names shouldContain "AIBotMatch"
}
```

Add `import io.kotest.matchers.collections.shouldContain` at top if not present.

**Step 2: Run test — expect FAIL (old golden data doesn't have all queues)**

Run: `just test-fd`

**Step 3: Update FrontDoorHandler CmdType 1910 and 624 handlers**

In `FrontDoorHandler.kt`, replace lines 188-197:

```kotlin
// --- Play blade data ---
1910 -> { // GetPlayBladeQueueConfig
    val configJson = EventRegistry.toQueueConfigJson()
    log.info("Front Door: PlayBladeQueueConfig ({} queues)", EventRegistry.queues.size)
    writer.sendJson(ctx, txId, configJson)
}

624 -> { // Event_GetActiveEventsV2
    val eventsJson = EventRegistry.toActiveEventsJson()
    log.info("Front Door: ActiveEventsV2 ({} events)", EventRegistry.events.size)
    writer.sendJson(ctx, txId, eventsJson)
}
```

Add import: `import leyline.frontdoor.service.EventRegistry`

**Step 4: Remove golden fields from GoldenData**

In `GoldenData.kt`, remove the `playBladeQueueConfigJson` and `activeEventsJson` fields from the data class and the `loadFromClasspath()` factory. Also remove the `golden.playBladeQueueConfigJson.length` from the `init` log in `FrontDoorHandler`.

**Step 5: Run `just test-fd`**

Expected: all tests pass including the strengthened 1910/624 tests.

**Step 6: Commit**

```
feat(fd): wire EventRegistry for CmdType 1910/624, remove golden queue/events
```

---

### Task 3: Thread eventName through match creation

**Files:**
- Modify: `src/main/kotlin/leyline/frontdoor/domain/Player.kt` (add eventName to MatchInfo)
- Modify: `src/main/kotlin/leyline/frontdoor/service/MatchmakingService.kt` (accept eventName)
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` (extract + pass eventName on 612)
- Modify: `src/main/kotlin/leyline/protocol/FdEnvelope.kt` (parameterize EventId)
- Modify: `src/test/kotlin/leyline/frontdoor/FrontDoorHandlerTest.kt` (verify eventName in MatchCreated)

**Step 1: Update FrontDoorHandlerTest — verify EventId in MatchCreated**

Replace the existing 612 test (lines 196-215) with:

```kotlin
test("CmdType 612 - AiBotMatch returns ack then MatchCreated with correct EventId") {
    val ch = fdChannel()
    val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","eventName":"AIBotMatch"}""")
    responses.size shouldBe 2

    responses[0].transactionId.shouldNotBeNull()

    val push = responses[1]
    val pushJson = push.jsonPayload.shouldNotBeNull()
    pushJson shouldContain "MatchCreated"
    val pushObj = json.parseToJsonElement(pushJson).jsonObject
    pushObj["Type"]?.jsonPrimitive?.content shouldBe "MatchCreated"
    val matchInfo = pushObj["MatchInfoV3"]?.jsonObject
    matchInfo.shouldNotBeNull()
    matchInfo["MatchEndpointHost"].shouldNotBeNull()
    matchInfo["MatchEndpointPort"].shouldNotBeNull()
    matchInfo["MatchId"].shouldNotBeNull()
    matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
}

test("CmdType 612 - eventName propagates to MatchCreated EventId") {
    val ch = fdChannel()
    val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","eventName":"Historic_Ladder"}""")
    responses.size shouldBe 2
    val matchInfo = json.parseToJsonElement(responses[1].jsonPayload!!)
        .jsonObject["MatchInfoV3"]?.jsonObject
    matchInfo.shouldNotBeNull()
    matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "Historic_Ladder"
}
```

**Step 2: Run test — expect FAIL (EventId always "AIBotMatch")**

Run: `just test-fd`

**Step 3: Add eventName to MatchInfo**

In `src/main/kotlin/leyline/frontdoor/domain/Player.kt`:

```kotlin
data class MatchInfo(val matchId: String, val host: String, val port: Int, val eventName: String = "AIBotMatch")
```

**Step 4: Update MatchmakingService to accept eventName**

In `MatchmakingService.kt`:

```kotlin
fun startAiMatch(playerId: PlayerId, deckId: DeckId, eventName: String = "AIBotMatch"): MatchInfo {
    decks.findById(deckId) // validate exists (future: validate legality)
    return MatchInfo(
        matchId = UUID.randomUUID().toString(),
        host = matchDoorHost,
        port = matchDoorPort,
        eventName = eventName,
    )
}
```

**Step 5: Extract eventName in FrontDoorHandler and pass through**

In `FrontDoorHandler.kt`, add pattern to companion object (line 370):

```kotlin
private val EVENT_NAME_PATTERN = Regex(""""eventName"\s*:\s*"([^"]+)"""")
```

Update the 612 handler (lines 178-186):

```kotlin
612 -> { // Event_AiBotMatch
    val deckId = json?.let { DECK_ID_PATTERN.find(it)?.groupValues?.get(1) }
    val eventName = json?.let { EVENT_NAME_PATTERN.find(it)?.groupValues?.get(1) } ?: "AIBotMatch"
    if (deckId != null) onDeckSelected?.invoke(deckId)
    val pid = playerId ?: PlayerId("anonymous")
    val match = matchmaking.startAiMatch(pid, DeckId(deckId ?: ""), eventName)
    log.info("Front Door: Event_AiBotMatch deckId={} event={} → ack + pushing MatchCreated", deckId, eventName)
    writer.sendEmpty(ctx, txId)
    sendMatchCreated(ctx, match)
}
```

Update `sendMatchCreated` (line 349-354):

```kotlin
private fun sendMatchCreated(ctx: ChannelHandlerContext, match: MatchInfo) {
    val json = FdEnvelope.buildMatchCreatedJson(match.matchId, match.host, match.port, match.eventName)
    log.info("Front Door: pushing MatchCreated matchId={} event={}", match.matchId, match.eventName)
    val pushTxId = UUID.randomUUID().toString()
    writer.sendJson(ctx, pushTxId, json)
}
```

**Step 6: Parameterize FdEnvelope.buildMatchCreatedJson**

In `FdEnvelope.kt` (line 405):

```kotlin
fun buildMatchCreatedJson(matchId: String, matchDoorHost: String, matchDoorPort: Int, eventId: String = "AIBotMatch"): String =
    buildJsonObject {
        put("Type", "MatchCreated")
        putJsonObject("MatchInfoV3") {
            put("ControllerFabricUri", "wzmc://forge/$matchId")
            put("MatchEndpointHost", matchDoorHost)
            put("MatchEndpointPort", matchDoorPort)
            put("MatchId", matchId)
            put("McFabricId", "wzmc://forge/$matchId")
            put("EventId", eventId)
            put("MatchType", "Familiar")
            put("MatchTypeInternal", 1)
            put("Battlefield", "FDN")
            put("YourSeat", 1)
            putJsonArray("PlayerInfos") {
                add(playerInfoJson(1, 1, "ForgePlayer", "Avatar_Basic_Adventurer"))
                add(playerInfoJson(2, 2, "Sparky", "Avatar_Basic_Sparky"))
            }
        }
    }.toString()
```

Also update `FrontDoorReplayStub.kt` call site (line 190) — add the `eventId` parameter or use the default.

**Step 7: Run `just test-fd`**

Expected: all tests pass including new eventName propagation test.

**Step 8: Commit**

```
feat(fd): thread eventName from client 612 through MatchCreated push
```

---

### Task 4: Wire eventName to MatchHandler

**Files:**
- Modify: `src/main/kotlin/leyline/infra/LeylineServer.kt` (add selectedEventName volatile)
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` (add onEventSelected callback)
- Modify: `src/main/kotlin/leyline/match/MatchHandler.kt` (receive + log eventName)

**Step 1: Add selectedEventName to LeylineServer**

In `LeylineServer.kt`, next to `selectedDeckId` (line 82):

```kotlin
@Volatile
var selectedEventName: String? = null
```

**Step 2: Add onEventSelected callback to FrontDoorHandler constructor**

In `FrontDoorHandler.kt` constructor, add after `onDeckSelected`:

```kotlin
private val onEventSelected: ((String) -> Unit)? = null,
```

Update 612 handler to invoke it:

```kotlin
if (eventName != "AIBotMatch") onEventSelected?.invoke(eventName)
```

Actually, always invoke:

```kotlin
onEventSelected?.invoke(eventName)
```

**Step 3: Wire in LeylineServer.startStub()**

In `LeylineServer.kt` where FrontDoorHandler is constructed (line 171-181), add:

```kotlin
onEventSelected = { selectedEventName = it },
```

**Step 4: Add selectedEventOverride to MatchHandler**

In `MatchHandler.kt` constructor, add after `selectedDeckOverride`:

```kotlin
/** Returns the eventName selected in FD's 612 handler, if any. */
private val selectedEventOverride: (() -> String?)? = null,
```

In `processGREMessage` ConnectReq handler (around line 179), log it:

```kotlin
ClientMessageType.ConnectReq_097b -> {
    val eventName = selectedEventOverride?.invoke()
    if (eventName != null) {
        log.info("Match Door: event={}", eventName)
    }
    // ... existing eviction + bridge creation code
```

**Step 5: Wire in LeylineServer matchDoorChannel setup (line 207)**

Add to MatchHandler constructor call:

```kotlin
selectedEventOverride = { selectedEventName },
```

**Step 6: Run `just test-fd` then `just test-gate`**

Expected: all pass. No behavioral change in tests — just plumbing + logging.

**Step 7: Commit**

```
feat: wire eventName from FD through LeylineServer to MatchHandler
```

---

### Task 5: Delete golden files + clean up GoldenData

**Files:**
- Delete: `src/main/resources/fd-golden/play-blade-queue-config.json`
- Delete: `src/main/resources/fd-golden/active-events.json`
- Modify: `src/main/kotlin/leyline/frontdoor/FrontDoorHandler.kt` (remove init log referencing queueConfig)

**Step 1: Delete the golden files**

```bash
trash src/main/resources/fd-golden/play-blade-queue-config.json
trash src/main/resources/fd-golden/active-events.json
```

**Step 2: Verify GoldenData no longer references them**

Should already be done in Task 2. If not, remove any remaining references.

**Step 3: Update FrontDoorHandler init log**

Remove `golden.playBladeQueueConfigJson.length` from the init log message (line 61-66). Replace with:

```kotlin
init {
    log.info(
        "Front Door stub: loaded golden data — formats={}B sets={}B startHook={}B",
        golden.getFormatsProto.size,
        golden.getSetsProto.size,
        golden.startHookJson.length,
    )
}
```

**Step 4: Run `just test-fd` then `just test-gate`**

Expected: all pass — nothing references these files anymore.

**Step 5: Run `just fmt`**

**Step 6: Commit**

```
refactor(fd): delete golden play-blade-queue-config.json and active-events.json
```

---

### Task 6: Final verification

**Step 1: Run `just test-gate`**

All unit + conformance + FD tests must pass.

**Step 2: Run `just fmt`**

**Step 3: Verify no golden references remain for deleted files**

```bash
grep -r "play-blade-queue-config\|active-events.json" src/ --include="*.kt"
```

Expected: zero results.

**Step 4: Verify EventRegistry matches prod capture shape**

Spot-check: `EventRegistry.toQueueConfigJson()` should produce JSON with same field names and structure as the prod capture from `recordings/2026-03-03_22-09-17/`.

**Step 5: Commit if any cleanup needed**

```
chore: final cleanup after dynamic events wiring
```
