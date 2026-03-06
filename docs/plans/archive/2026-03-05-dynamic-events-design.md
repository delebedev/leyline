# Dynamic Event Serving + Format Plumbing — Design

> **Status:** Approved (2026-03-05)
> **Scope:** Replace golden queue/event JSON with server-owned EventRegistry. Wire eventName from client through match creation pipeline.

## Current state

CmdType 1910 (PlayBladeQueueConfig) and 624 (ActiveEventsV2) serve static golden
JSON files. Only 5 queue entries vs prod's 14. CmdType 612 extracts `deckId` but
ignores `eventName`. MatchCreated hardcodes `"EventId":"AIBotMatch"`. MatchHandler
has no format awareness.

## Target state

Server defines queue entries + event definitions in Kotlin. Builds JSON responses
at request time. Extracts `eventName` from 612 and threads it through
MatchCreated → MatchHandler. All constructed formats available in Play blade.

## EventRegistry

New `frontdoor/service/EventRegistry.kt`. Data classes + hardcoded list:

```kotlin
data class QueueEntry(
    val id: String,
    val queueType: String = "Ranked",  // Ranked, Unranked, Brawl
    val eventNameBO1: String,
    val eventNameBO3: String? = null,
    val deckSize: String = "Events/Deck_60plus",
)

data class EventDef(
    val internalName: String,
    val publicName: String,
    val deckSelectFormat: String,
    val formatType: String = "Constructed",
    val flags: List<String> = listOf("IsArenaPlayModeEvent", "UpdateQuests"),
    val winCondition: String = "SingleElimination",
    val displayPriority: Int = 50,
)
```

Queue entries (matching prod capture from 2026-03-03):

| Id | QueueType | EventNameBO1 | EventNameBO3 |
|----|-----------|-------------|-------------|
| StandardRanked | Ranked | Ladder | Traditional_Ladder |
| StandardUnranked | Unranked | Play | Constructed_BestOf3 |
| HistoricRanked | Ranked | Historic_Ladder | Traditional_Historic_Ladder |
| HistoricUnranked | Unranked | Historic_Play | Traditional_Historic_Play |
| ExplorerRanked | Ranked | Explorer_Ladder | Traditional_Explorer_Ladder |
| ExplorerUnranked | Unranked | Explorer_Play | Traditional_Explorer_Play |
| TimelessRanked | Ranked | Timeless_Ladder | Traditional_Timeless_Ladder |
| TimelessUnranked | Unranked | Timeless_Play | — |
| AIBotMatch | Unranked | AIBotMatch | — |

Each event has matching `DeckSelectFormat` from prod: Standard, Historic, Explorer,
Timeless, TraditionalStandard, TraditionalHistoric, TraditionalExplorer,
TraditionalTimeless.

`toQueueConfigJson()` and `toActiveEventsJson()` build complete response JSON.

## Event name plumbing

612 payload: `{"deckId":"...","eventName":"AIBotMatch"}`.

Flow:
1. `FrontDoorHandler.612` → extract `eventName`
2. `MatchmakingService.startAiMatch(pid, deckId, eventName)` → `MatchInfo` gains `eventName`
3. `FdEnvelope.buildMatchCreatedJson(matchId, host, port, eventId)` — parameterized
4. `LeylineServer` volatile `selectedEventName` alongside `selectedDeckId`
5. `MatchHandler` receives + logs eventName (no rules enforcement yet)

## What stays golden

`get-formats-response.bin` and `get-sets-response.bin` — complex protobuf with set
legality. Not worth generating dynamically for this scope.

## Files

| File | Change |
|------|--------|
| New: `frontdoor/service/EventRegistry.kt` | Queue + event definitions, JSON builders |
| Mod: `FrontDoorHandler.kt` | Use EventRegistry for 1910/624, extract eventName on 612 |
| Mod: `frontdoor/service/MatchmakingService.kt` | Accept eventName |
| Mod: `protocol/FdEnvelope.kt` | Parameterize eventId |
| Mod: `infra/LeylineServer.kt` | Wire selectedEventName |
| Mod: `match/MatchHandler.kt` | Receive + log eventName |
| Mod: `frontdoor/GoldenData.kt` | Remove queue/events loading |
| Del: `fd-golden/play-blade-queue-config.json` | Replaced |
| Del: `fd-golden/active-events.json` | Replaced |
| Tests: new `EventRegistryTest`, update `FrontDoorHandlerTest` |

## Not in scope

- Ban lists / set legality enforcement
- Brawl / command zone
- Draft / Sealed events
- Format-specific Forge rules (all matches run default Constructed)
- Alchemy (digital-only cards need Forge support)
