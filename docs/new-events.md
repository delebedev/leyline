# New FD Events — Proxy Session 2026-03-06

Source: `recordings/2026-03-06_22-00-53/capture/fd-frames.jsonl` (107 frames, real WotC account via proxy)

## Session Summary

| seq | dir | cmd | size | notes |
|-----|-----|-----|------|-------|
| 2→6 | C2S→S2C | Authenticate | 68B | |
| 7→8 | C2S→S2C | Graph_GetGraphDefinitions | 262KB | |
| 9→10 | C2S→S2C | Graph_GetGraphState | 356B | |
| 12→13 | C2S→S2C | GetDesignerMetadata | 1.2KB | already golden |
| 15→16 | C2S→S2C | GetFormats | bin | already golden |
| 19→20 | C2S→S2C | GetSets | bin | already golden |
| 21→22 | C2S→S2C | Event_GetActiveMatches | 16B | |
| 27→32 | C2S→S2C | StartHook | 1.7MB | already golden |
| 28→33 | C2S→S2C | StaticContent | 1.1MB | |
| 36→52 | C2S→S2C | Card_GetAllCards | 63KB | |
| 37→53 | C2S→S2C | Rank_GetCombinedRankInfo | 95B | |
| 41→60 | C2S→S2C | Deck_GetAllPreconDecksV3 | 1MB | already golden |
| 42→56 | C2S→S2C | GetPlayerPreferences | 1.5KB | already golden |
| 43→58 | C2S→S2C | GetPlayBladeQueueConfig | 4.3KB | |
| 44→57 | C2S→S2C | GetNetDeckFolders | 357B | |
| 46→61 | C2S→S2C | GetPlayerInbox | 1.8KB | |
| 47→64 | C2S→S2C | GetAllPrizeWalls | 1KB | |
| 49→62 | C2S→S2C | ChallengeReconnectAll | 0B | |
| 70→71 | C2S→S2C | Merc_GetStoreStatusV2 | 438B | |
| 72→73 | C2S→S2C | Merc_GetSkusAndListings | 7.5MB | |
| 81→83 | C2S→S2C | Event_GetActiveEventsV2 | 116KB | |
| 86→87 | C2S→S2C | Event_GetCoursesV2 | 21KB | |
| 91→95 | C2S→S2C | Quest_GetQuests | 1.5KB | |
| 96→99 | C2S→S2C | Carousel_GetCarouselItems | 1.3KB | already golden |
| 97→98 | C2S→S2C | PeriodicRewards_GetStatus | 5.8KB | |
| 202→203 | C2S→S2C | Event_Resign | 2.3KB | **new** |
| 204→205 | C2S→S2C | Event_Drop | 2B | already golden |

## New Event Commands

### Event_Resign (CmdType 606)

**Request:** `{"EventName":"Jump_In_2024"}`

**Response:** Full course object with `CurrentModule: "Complete"`:

```json
{
  "CourseId": "e5ff47e2-92c5-4795-b070-34ee522d0426",
  "InternalEventName": "Jump_In_2024",
  "CurrentModule": "Complete",
  "ModulePayload": "",
  "CourseDeckSummary": {
    "DeckId": "01043cdf-c559-455a-b688-eef9cfe33e9e",
    "Attributes": [],
    "DeckTileId": 86795,
    "DeckArtId": 0,
    "FormatLegalities": {},
    "PreferredCosmetics": { "Avatar": "", "Sleeve": "", "Pet": "", "Title": "", "Emotes": [] },
    "DeckValidationSummaries": [],
    "UnownedCards": {}
  },
  "CourseDeck": {
    "MainDeck": [
      {"cardId": 86834, "quantity": 1}, {"cardId": 86845, "quantity": 1},
      {"cardId": 86932, "quantity": 1}, {"cardId": 86926, "quantity": 1},
      {"cardId": 86795, "quantity": 1}, {"cardId": 86793, "quantity": 1},
      {"cardId": 86824, "quantity": 1}, {"cardId": 86810, "quantity": 1},
      {"cardId": 86799, "quantity": 1}, {"cardId": 86855, "quantity": 1},
      {"cardId": 86829, "quantity": 1}, {"cardId": 83951, "quantity": 1},
      {"cardId": 90479, "quantity": 1}, {"cardId": 90590, "quantity": 1},
      {"cardId": 90588, "quantity": 1}, {"cardId": 90470, "quantity": 1},
      {"cardId": 90488, "quantity": 1}, {"cardId": 90487, "quantity": 1},
      {"cardId": 90491, "quantity": 1}, {"cardId": 90486, "quantity": 1},
      {"cardId": 90489, "quantity": 1}, {"cardId": 90496, "quantity": 1},
      {"cardId": 90481, "quantity": 1}, {"cardId": 90477, "quantity": 1},
      {"cardId": 84574, "quantity": 1}, {"cardId": 84574, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91838, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1},
      {"cardId": 91838, "quantity": 1}, {"cardId": 91836, "quantity": 1}
    ],
    "ReducedSideboard": [],
    "Sideboard": [],
    "CommandZone": [],
    "Companions": [],
    "CardSkins": []
  },
  "CurrentWins": 2,
  "CurrentLosses": 4,
  "CardPool": [86834, 86845, 86932, 86926, 86795, 86793, 86824, 86810, 86799, 86855, 86829, 83951, 90479, 90590, 90588, 90470, 90488, 90487, 90491, 90486, 90489, 90496, 90481, 90477, 84574, 84574, 91838, 91838, 91838, 91836, 91838, 91836, 91838, 91836, 91838, 91836, 91838, 91836, 91838, 91836],
  "CardPoolByCollation": [],
  "CardStyles": [],
  "JumpStart": {
    "CurrentChoices": [],
    "PacketsChosen": [
      { "packetName": "SJMP_PacketName_WOE_Rats", "displayArtId": 434489, "displayGrpId": 86795, "colors": ["R", "B"] },
      { "packetName": "SJMP_PacketName_OTJ_Treasured", "displayArtId": 444223, "displayGrpId": 90590, "colors": ["R"] }
    ]
  }
}
```

### Event_GetActiveMatches (CmdType 403)

**Request:** `"{}"`  (note: string-wrapped empty JSON)

**Response:**
```json
{"MatchesV3": []}
```

### Event_GetCoursesV2 (CmdType 623)

**Request:** `{}`

**Response:** Array of active event courses. Each course has deck, record, card pool. 21KB for 7 courses.

```json
{
  "Courses": [
    {
      "CourseId": "11427d95-ef23-4010-b960-d0ea003b1e6d",
      "InternalEventName": "Alchemy_Ladder",
      "CurrentModule": "Complete",
      "ModulePayload": "",
      "CourseDeckSummary": {
        "DeckId": "b19c8b9c-18e1-43ab-b668-b67061be6614",
        "Name": "Exploit zombies Alchemy",
        "Attributes": [
          {"name": "Version", "value": "11"},
          {"name": "TileID", "value": "75330"},
          {"name": "LastPlayed", "value": "\"2022-02-28T20:20:12.775156+00:00\""},
          {"name": "LastUpdated", "value": "\"2022-02-25T21:22:49.106775\""},
          {"name": "IsFavorite", "value": "false"},
          {"name": "Format", "value": "Alchemy"}
        ],
        "Description": "",
        "DeckTileId": 75330,
        "DeckArtId": 0,
        "FormatLegalities": {},
        "PreferredCosmetics": {"Avatar": "", "Sleeve": "", "Pet": "", "Title": "", "Emotes": []},
        "DeckValidationSummaries": [],
        "UnownedCards": {}
      },
      "CourseDeck": {
        "MainDeck": [{"cardId": 75330, "quantity": 7}, {"cardId": 78914, "quantity": 3}, "..."],
        "ReducedSideboard": [], "Sideboard": [], "CommandZone": [], "Companions": [], "CardSkins": [{"GrpId": 78575, "CCV": "DA"}, "..."]
      },
      "CurrentWins": 1,
      "CardPool": [],
      "CardPoolByCollation": [],
      "CardStyles": []
    },
    {
      "CourseId": "ed9dd8f9-3c0b-44ea-a971-9f25703d0f25",
      "InternalEventName": "DualColorPrecons",
      "CurrentModule": "CreateMatch",
      "CourseDeckSummary": {"Name": "?=?Loc/Decks/Precon/Precon_EPPFDN_WG", "...": "..."},
      "CurrentWins": 6, "CurrentLosses": 2,
      "MadeChoice": "ded6cdc1-b6f0-4f78-a7dc-228a15d632ea"
    },
    {
      "CourseId": "e5ff47e2-92c5-4795-b070-34ee522d0426",
      "InternalEventName": "Jump_In_2024",
      "CurrentModule": "CreateMatch",
      "CurrentWins": 2, "CurrentLosses": 4,
      "JumpStart": {
        "CurrentChoices": [],
        "PacketsChosen": [
          {"packetName": "SJMP_PacketName_WOE_Rats", "displayArtId": 434489, "displayGrpId": 86795, "colors": ["R","B"]},
          {"packetName": "SJMP_PacketName_OTJ_Treasured", "displayArtId": 444223, "displayGrpId": 90590, "colors": ["R"]}
        ]
      }
    },
    {
      "CourseId": "a74ffd30-f591-43b1-816a-e079dd45a0d1",
      "InternalEventName": "Explorer_Ladder",
      "CurrentModule": "CreateMatch",
      "CourseDeckSummary": {"Name": "Azorius Enchantments (2)", "...": "..."}
    },
    {
      "CourseId": "dd746cf4-d52b-4f2f-ab81-4828df4ba6b6",
      "InternalEventName": "Ladder",
      "CurrentModule": "Complete",
      "CurrentLosses": 1
    },
    {
      "CourseId": "326cc45c-e8a6-4505-9a04-7722a8815283",
      "InternalEventName": "AIBotMatch",
      "CourseDeckSummary": {"Name": "Might of the Legion", "...": "..."}
    },
    {
      "CourseId": "eb6b8327-4440-42fe-9d3d-0e3b756270d4",
      "InternalEventName": "Play",
      "CurrentModule": "MatchResults",
      "CourseDeckSummary": {"Name": "Might of the Legion", "...": "..."}
    }
  ]
}
```

Modules observed: `Complete`, `CreateMatch`, `MatchResults`, `Joined` (from other sessions).

### Event_GetActiveEventsV2 (CmdType 624)

**Request:** `{"CacheVersion":0,"ShowAllEvents":false}` (first call) or `{"CacheVersion":55211318,"ShowAllEvents":false}` (subsequent)

**Response:** 116KB — too large to inline. Contains `DynamicFilterTags[]` and `Events[]`.

Extract: `LEYLINE_FD_SESSION=recordings/2026-03-06_22-00-53/capture just fd-response Event_GetActiveEventsV2 | jq .`

Key fields per event:
```json
{
  "InternalEventName": "PremierDraft_TMT_20260303",
  "EventType": "sealed",
  "FormatType": "Draft",
  "EventUXInfo": {
    "EventBladeBehavior": "Default",
    "DisplayPriority": 100,
    "EventLandingPageImage": "...",
    "FilterTags": ["SIR Limited"]
  },
  "Parameters": {
    "maxWins": 7, "maxLosses": 3,
    "entryCurrencyType": "Gem", "entryCurrencyAmount": 1500
  }
}
```

### Event_GetMatchResultReport (CmdType 611)

Source: `recordings/2026-03-06_22-37-41` (session 2 — full game through proxy)

**Request:** `{"MatchId":"5ae653de-f10a-4b66-beb7-ed1c5fc1f714","EventName":"Constructed_Event_2026"}`

**Response:** 90KB — post-game summary with full inventory snapshot.

```json
{
  "CurrentModule": "CreateMatch",
  "FoundMatch": true,
  "InventoryInfo": {
    "SeqId": 6,
    "Changes": [],
    "Gems": 145,
    "Gold": 825,
    "TotalVaultProgress": 695,
    "WildCardCommons": 167,
    "WildCardUnCommons": 170,
    "WildCardRares": 3,
    "WildCardMythics": 42,
    "CustomTokens": {
      "BonusPackProgress": 1,
      "Login_TDM": 1,
      "BattlePass_ECL_Orb": 1
    },
    "Boosters": [
      {"CollationId": 100052, "SetCode": "DFT", "Count": 5},
      {"CollationId": 100054, "SetCode": "FIN", "Count": 3},
      {"CollationId": 100058, "SetCode": "ECL", "Count": 17}
    ],
    "Vouchers": {},
    "PrizeWallsUnlocked": [],
    "Cosmetics": {"ArtStyles": ["..."]}
  },
  "questUpdates": [],
  "periodicRewardsProgress": []
}
```

Key: returns `CurrentModule` (next event state) + `FoundMatch` + full `InventoryInfo` snapshot (gems, gold, wildcards, boosters, cosmetics, vault progress). `questUpdates` and `periodicRewardsProgress` carry post-game reward deltas.

## Non-Event Commands (Not Yet Golden)

| cmd | request | response size | notes |
|-----|---------|---------------|-------|
| Authenticate | `{code, codeVerifier, ...}` | 68B | JWT exchange |
| StaticContent | `{}` | 1.1MB | localization / static assets |
| Card_GetAllCards | `{}` | 63KB | card database delta |
| Rank_GetCombinedRankInfo | `{}` | 95B | `{"constructedClass":"Gold", "limitedClass":"Silver", ...}` |
| Rank_GetSeasonAndRankDetails | `{}` | 6.5KB | season config, step thresholds |
| GetPlayBladeQueueConfig | `{}` | 4.3KB | queue definitions for play blade |
| GetNetDeckFolders | `{}` | 357B | deck folder metadata |
| GetPlayerInbox | `{}` | 1.8KB | inbox messages |
| GetAllPrizeWalls | `{}` | 1KB | mastery/prize wall state |
| ChallengeReconnectAll | `{}` | 0B | reconnect to pending challenges |
| Merc_GetStoreStatusV2 | `{}` | 438B | store availability |
| Merc_GetSkusAndListings | `{}` | 7.5MB | full store catalog |
| Quest_GetQuests | `{}` | 1.5KB | daily/weekly quests |
| PeriodicRewards_GetStatus | `{}` | 5.8KB | daily wins, weekly wins, reset timers |
| Rank_EvaluatePayoutsV2 | `{}` | 4B (`null`) | |
| Renewal_GetCurrentRenewal | `{}` | 2B (`{}`) | no active renewal |
| GetAllPreferredPrintings | `{}` | 2B (`{}`) | |
| GetVoucherDefinitions | `{}` | 0B | |
| Store_GetEntitlementsV2 | `{}` | 36KB | owned products/entitlements |

## Extraction Commands

```bash
# Set session for all commands
export LEYLINE_FD_SESSION=recordings/2026-03-06_22-00-53/capture

# Extract any response as golden
just fd-response Event_Resign | jq . > frontdoor/src/main/resources/fd-golden/event-resign.json
just fd-response Event_GetCoursesV2 | jq . > frontdoor/src/main/resources/fd-golden/event-courses.json
just fd-response Quest_GetQuests | jq . > frontdoor/src/main/resources/fd-golden/quests.json
# etc.
```
