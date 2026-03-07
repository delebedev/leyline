# Sealed Wire Conformance Report

**Recording:** `recordings/2026-03-07_11-49-05`
**Event:** Sealed_TMT_20260303 (TMT Sealed session, proxy capture)
**Analyst:** conformance agent
**Date:** 2026-03-07

---

## Summary

Six FD endpoints analyzed. Findings range from cosmetic gaps (InventoryInfo zeros) to structural omissions that affect client behavior (missing `EventState`, `CourseDeck` absence on join, `ByCourseObjectiveTrack` in event def).

| Endpoint | Cmd | Status | Severity |
|---|---|---|---|
| Event_Join (600) | rsp=227 | Gaps in InventoryInfo; CourseDeckSummary missing DeckId/Name on join — correct | Low |
| Event_SetDeckV2 (622) | rsp=244 | Shape matches; CourseDeckSummary.Attributes population needed | Low |
| Event_GetCoursesV2 (623) | rsp=246 | Missing `CurrentWins`/`CurrentLosses` conditional emission | Medium |
| Event_EnterPairing (603) | rsp=428 | Shape matches exactly | None |
| Event_GetMatchResultReport (608) | rsp=635 | Missing `Cosmetics` in InventoryInfo; `PrizeWallsUnlocked` missing | Medium |
| ActiveEventsV2 (624) | rsp=80 | Missing `EventState`, `DisplayPriorityMilestoneChanges`, `ByCourseObjectiveTrack`, `SelectedDeckWidget`, `StickerDisplay`, `PrizeWallData` | High |

---

## 1. Event_Join (CmdType 600) — seq 225→227

### Real server top-level shape

```
{
  "Course": { ... },       // Course object — see below
  "InventoryInfo": { ... } // Full inventory snapshot
}
```

### Course object (on join, before deck selection)

Real server sends Course **without** `CourseDeck` and **without** `CourseDeckSummary.DeckId/Name` when the player has not yet submitted a deck (module = `DeckSelect`):

```
CourseDeckSummary keys present: Attributes, FormatLegalities, PreferredCosmetics,
                                 DeckValidationSummaries, UnownedCards
CourseDeckSummary keys ABSENT: DeckId, Name, DeckTileId, DeckArtId
CourseDeck: ABSENT (top-level key missing entirely)
CardPool: list[84]               -- full card pool present
CardPoolByCollation: list[6]     -- 6 packs, each with CollationId + CardPool
CardStyles: list[0]
```

Our `buildJoinResponse()` calls `buildCourseJson(course, includeDeck = false, includeWins = false)` which always emits `CourseDeckSummary` with `DeckId = "00000000-..."` and `Name = ""`. **This is a structural mismatch** — the real server omits `DeckId`/`Name`/`DeckTileId`/`DeckArtId` when no deck has been submitted yet.

**Impact:** client likely tolerates zeroed DeckId. Low risk unless it affects deck-select UI state.

### InventoryInfo — gaps

Real server sends a full inventory snapshot:

```
SeqId: 8              (we send 1 — hardcoded)
Changes: list[2]      (we send [])
  [0] Source=EventPayEntry, SourceId=<courseId>, InventoryGems=-2000
  [1] Source=EventGrantCardPool, SourceId=Sealed_TMT_20260303, GrantedCards=[{GrpId,CardAdded,SetCode}x84]
Gems: 1570            (we send 0)
Gold: 825             (we send 0)
TotalVaultProgress: 695  (we send 0)
WildCardCommons: 167
WildCardUnCommons: 170
WildCardRares: 3
WildCardMythics: 42
CustomTokens: { BonusPackProgress: 1, Login_TDM: 1, BattlePass_ECL_Orb: 1 }
Boosters: list[10]    (we send [])
Vouchers: {}
PrizeWallsUnlocked: list[0]
Cosmetics: { ArtStyles: list[296], Avatars: list[27], Pets: list[16], Sleeves: list[23],
             Emotes: list[15], Titles: list[0] }
```

**Missing from our output:**
- `CustomTokens` — we send `{}`, real server has named tokens (cosmetic, low risk)
- `Boosters` — we send `[]`, real server reflects actual booster count (cosmetic)
- `Cosmetics` — we send `{}` (empty object), real server has full cosmetics inventory
- `PrizeWallsUnlocked` — we have the key; real server also has it as `[]` (match)
- `SeqId` — we hardcode 1, real server has 8 (monotonic, functional)
- `Changes` — we omit the EventPayEntry and EventGrantCardPool change records

**Impact of Changes omission:** the EventGrantCardPool change record is how the client learns which specific cards were granted to the sealed pool. If the client relies on this to populate its local collection cache for deck building, omitting it could cause deck editor issues (cards show as unowned). Worth flagging for engine-bridge.

---

## 2. Event_SetDeckV2 (CmdType 622) — seq 243→244

### Real server shape

```
{
  "CourseId": "<uuid>",
  "InternalEventName": "Sealed_TMT_20260303",
  "CurrentModule": "CreateMatch",
  "ModulePayload": "",
  "CourseDeckSummary": {
    "DeckId": "<uuid>",
    "Name": "Sealed Deck",
    "Attributes": [                         // 6 attributes populated
      { "name": "Version",      "value": "11" },
      { "name": "TileID",       "value": "100538" },
      { "name": "LastPlayed",   "value": "\"0001-01-01T00:00:00\"" },
      { "name": "LastUpdated",  "value": "\"2026-03-07T11:54:33.160439+00:00\"" },
      { "name": "IsFavorite",   "value": "false" },
      { "name": "Format",       "value": "Sealed" }
    ],
    "DeckTileId": 100538,
    "DeckArtId": 0,
    "FormatLegalities": { "ArtisanHistoric_Achievement": false },
    "PreferredCosmetics": { "Avatar": "", "Sleeve": "CardBack_FIN_448363", ... },
    "DeckValidationSummaries": [],
    "UnownedCards": {}
  },
  "CourseDeck": {
    "MainDeck": [ {cardId, quantity}, ... ],
    "ReducedSideboard": [],
    "Sideboard": [ ... ],   // unselected cards go here
    ...
  },
  "CardPool": list[84],
  "CardPoolByCollation": list[6],
  "CardStyles": []
}
```

**Note:** SetDeckV2 response is the course object directly (not wrapped in `"Course"` key), and includes `CourseDeck`. This shape matches what `buildCourseJson(course, includeDeck=true)` would emit.

**Gaps in our output:**
- `CourseDeckSummary.Attributes` — we emit `[]` always. Real server populates Version, TileID, LastPlayed, LastUpdated, IsFavorite, Format. Missing these means deck editor metadata won't be persisted correctly, but unlikely to break gameplay.
- `CourseDeckSummary.FormatLegalities` — we emit `{}`. Real server has `{"ArtisanHistoric_Achievement": false}` for sealed. Cosmetic — client ignores for non-constructed formats.
- `CourseDeckSummary.PreferredCosmetics.Sleeve` — we emit `""`. Real server reflects the player's equipped sleeve. Cosmetic only.

**Shape is otherwise correct.** `buildCourseJson` with `includeDeck=true` matches the structural expectation.

---

## 3. Event_GetCoursesV2 (CmdType 623) — seq 245→246

### Real server shape for the Sealed course (after deck submission)

```
{
  "Courses": [
    {
      "CourseId": "<uuid>",
      "InternalEventName": "Sealed_TMT_20260303",
      "CurrentModule": "CreateMatch",
      "ModulePayload": "",
      "CourseDeckSummary": { ... full with DeckId, Attributes, etc. },
      "CourseDeck": { MainDeck, ReducedSideboard, Sideboard, CommandZone, Companions, CardSkins },
      // NOTE: no CurrentWins key here since wins=0
      "CardPool": list[84],
      "CardPoolByCollation": list[6],
      "CardStyles": []
    },
    ...other courses...
  ]
}
```

### Our `toCoursesJson()` behavior

Calls `buildCourseJson(course, includeLosses = true)`. The `includeWins` param defaults to `true`, meaning we emit `CurrentWins` when `course.wins > 0`. Real server does the same (0-wins courses omit the key). **This is correct.**

**Gaps:**
- `CourseDeckSummary.Attributes` — same as SetDeckV2: we emit `[]`, real server populates 6 attributes. Medium priority — deck editor attribute display.
- Sealed course present in all responses from seq 246 onward (after join). Before join (seq 83/106/177/221), sealed course does NOT appear in GetCoursesV2. This means leyline must only add the sealed course to the courses list after a successful Event_Join. Verify this is implemented — if GetCoursesV2 is served from a static list, sealed courses may appear before they should.

---

## 4. Event_EnterPairing (CmdType 603) — seq 427→428

### Real server response

```json
{
  "CurrentModule": "CreateMatch",
  "Payload": "Success"
}
```

**Our `buildMatchResultReport` is not used here** — EnterPairing has its own handler. No builder function shown for this endpoint in EventWireBuilder.kt.

**Status: shape exact match.** No issues to flag.

---

## 5. Event_GetMatchResultReport (CmdType 608) — seq 634→635

### Real server top-level shape

```
{
  "CurrentModule": "CreateMatch",
  "FoundMatch": true,
  "InventoryInfo": { ... },    // full inventory snapshot (same structure as Event_Join)
  "questUpdates": list[3],     // array of quest progress objects
  "periodicRewardsProgress": {}
}
```

### questUpdates item shape

```json
{
  "questId": "<uuid>",
  "goal": 30,
  "locKey": "Quests/Quest_Golgari_Necromancy",
  "tileResourceId": "<uuid>",
  "treasureResourceId": "<uuid>",
  "questTrack": "Default",
  "endingProgress": 1,
  "startingProgress": 1,
  "canSwap": true,
  "chestDescription": { "image1": "...", "prefab": "...", ... }
}
```

### InventoryInfo gaps (same as Event_Join)

Real server `InventoryInfo` contains:
- `Cosmetics` with `ArtStyles[296]`, `Avatars[27]`, `Pets[16]`, `Sleeves[23]`, `Emotes[15]`, `Titles[0]`

Our `buildMatchResultReport` omits `Cosmetics` entirely (not just `{}`). The real server always includes it. This is a **structural omission** — the client may need this to update its local cosmetics state after a match.

Also omitted from our builder:
- `PrizeWallsUnlocked` — present as `[]` in real server, absent from our output
- `CustomTokens` in InventoryInfo — `{}` vs populated object
- `Boosters` — `[]` vs real booster list

**Shape mismatches vs our `buildMatchResultReport`:**

| Field | Real server | Ours |
|---|---|---|
| `InventoryInfo.Cosmetics` | `{ArtStyles, Avatars, Pets, Sleeves, Emotes, Titles}` | absent (not emitted) |
| `InventoryInfo.PrizeWallsUnlocked` | `[]` | absent |
| `InventoryInfo.Boosters` | list[10] | `[]` ✓ |
| `InventoryInfo.CustomTokens` | `{...tokens}` | `{}` (close enough) |
| `questUpdates` | list[3] | `[]` |
| `periodicRewardsProgress` | `{}` | `{}` ✓ |

**Impact:** Missing `Cosmetics` key is medium — if client tries to iterate it to refresh cosmetic inventory after match, a missing key causes a null ref. Missing `questUpdates` means quest progress bar won't update after a match.

---

## 6. ActiveEventsV2 (CmdType 624) — seq 77→80

### Real server Sealed_TMT event definition

Top-level fields on the event object:

| Field | Real server | Ours |
|---|---|---|
| `InternalEventName` | `"Sealed_TMT_20260303"` | ✓ |
| `EventState` | absent (key not present) | `"Active"` — **WRONG** |
| `FormatType` | `"Sealed"` | ✓ (from EventDef.formatType) |
| `StartTime` | `"2026-03-03T..."` | hardcoded `"2025-01-01T00:00:00Z"` |
| `LockedTime` | `"2026-03-10T..."` | hardcoded `"2099-01-01T00:00:00Z"` |
| `ClosedTime` | `"2026-03-10T..."` | hardcoded `"2099-01-01T00:00:00Z"` |
| `Flags` | `["IsArenaPlayModeEvent","UpdateQuests","UpdateDailyWeeklyRewards"]` | from EventDef.flags |
| `EventTags` | `["Sealed","Limited"]` | from EventDef.eventTags |
| `PastEntries` | `{}` | `{}` ✓ |
| `EntryFees` | `[{Gem,2000},{SealedToken,1}]` | `[]` — **MISSING** |
| `WinCondition` | `"SingleElimination"` | from EventDef.winCondition |
| `AllowedCountryCodes` | `[]` | `[]` ✓ |
| `ExcludedCountryCodes` | `[]` | `[]` ✓ |

### EventUXInfo gaps

| Field | Real server | Ours |
|---|---|---|
| `PublicEventName` | `"Sealed_TMT"` | ✓ |
| `DisplayPriority` | 73 | ✓ |
| `DisplayPriorityMilestoneChanges` | `[]` | **absent** |
| `Parameters` | `{}` | `{}` ✓ |
| `Group` | `""` | `""` ✓ |
| `DynamicFilterTagIds` | `["TMT Limited"]` | `[]` — **MISSING** |
| `FactionSealedUXInfo` | `[]` | `[]` ✓ |
| `DeckSelectFormat` | `"Sealed"` | ✓ |
| `Prizes` | `{"0": "00000000-...", ..., "7": "..."}` | `{}` — **MISSING** |
| `EventComponentData` | complex object (see below) | partial |

### EventComponentData gaps

Real server `EventComponentData` for Sealed_TMT contains:

```
BoosterPacksDisplay: { CollationIds: [200059, 200059, 200059, 200059, 200059, 200059] }
LossDetailsDisplay: { Games: 3 }         -- we emit { LossDetailsType: "PlayUntilEventEnds" } — WRONG
TimerDisplay: {}                          -- ✓
DescriptionText: { LocKey: "..." }        -- ✓
TitleRankText: { LocKey: "..." }          -- ✓
ResignWidget: {}                          -- ✓
SelectedDeckWidget: { DeckButtonBehavior: "Editable", ShowCopyDeckButton: true }  -- MISSING
MainButtonWidget: {}                      -- ✓
ByCourseObjectiveTrack: { ChestDescriptions: [ ... 8 reward tier entries ... ] }  -- MISSING entirely
StickerDisplay: { Stickers: [] }          -- MISSING
PrizeWallData: {}                         -- MISSING
```

**`LossDetailsDisplay` is wrong:** we emit `{ LossDetailsType: "PlayUntilEventEnds" }` but real server sends `{ Games: 3 }` for Sealed_TMT. This controls the loss display widget in the UI — `Games: 3` means "best-of-3" loss limit display. `PlayUntilEventEnds` is for unlimited-loss events (like ladder). **This is a visible bug** — the event UI will show wrong loss behavior text.

**`ByCourseObjectiveTrack`** is entirely absent from our output. This drives the reward track display (the 8 gem+pack tier chest icons shown during the event). Without it the reward track won't render.

**`SelectedDeckWidget`** with `DeckButtonBehavior: "Editable"` controls whether the deck button in the event UI lets the player edit their sealed deck. Without it, the deck edit button behavior is unspecified.

**`EventState` should be absent** — the real server does not emit this key for active events in this recording. We always emit `"Active"`. This may cause a parsing issue if the client treats an explicit EventState as different from the implicit default.

**`EntryFees` is `[]`** in our output. Real server sends two fee options: `{Gem: 2000}` and `{SealedToken: 1}`. While this doesn't affect existing-event flow (player already paid), it affects the event browser "Join" button display for new players.

---

## Prioritized Action List

### High (likely causes visible client bugs)

1. **`LossDetailsDisplay` wrong value** — `{ Games: 3 }` not `{ LossDetailsType: "PlayUntilEventEnds" }` for sealed events. Fix in `buildEventJson`. Flag to engine-bridge.

2. **`ByCourseObjectiveTrack` missing** — reward track UI will not render for sealed events. Needs `ChestDescriptions` array with 8 win-tier entries. This data is event-config-specific (different per event). Flag to engine-bridge for config model.

3. **`DynamicFilterTagIds` empty** — affects event filter sidebar. Sealed events should appear under "TMT Limited" filter. Currently filtered out.

### Medium (missing data, may cause functional issues)

4. **`InventoryInfo.Cosmetics` absent** in `buildMatchResultReport` — real server always includes `{ArtStyles, Avatars, Pets, Sleeves, Emotes, Titles}`. Add empty sub-arrays at minimum.

5. **`InventoryInfo.Changes` missing** in `buildJoinResponse` — EventGrantCardPool change record informs client which cards are in the sealed pool. Client may rely on this for deck editor "owned" state.

6. **`SelectedDeckWidget` missing** — deck edit button behavior undefined in sealed event UI.

7. **`EntryFees` empty** — event browser "Join" button won't show correct cost for sealed events.

### Low (cosmetic / value gaps, unlikely to break flow)

8. **`CourseDeckSummary.Attributes` always `[]`** — deck metadata (Version, TileID, LastPlayed etc) not populated.

9. **`EventState` emitted as `"Active"`** — real server omits this key entirely. Remove the hardcoded emission.

10. **`DisplayPriorityMilestoneChanges` missing** — real server sends `[]`; we omit.

11. **`Prizes` empty `{}`** — real server sends `{"0": null-uuid, ..., "7": null-uuid}` for sealed.

12. **`StickerDisplay` and `PrizeWallData` missing** — both empty objects/arrays in real server. Add stubs.

13. **`InventoryInfo.SeqId` hardcoded 1** — should be monotonically increasing; use real value from player inventory.

14. **`questUpdates` always `[]`** — quest progress bar won't update after matches, but doesn't block gameplay.

---

## Source File

`frontdoor/src/main/kotlin/leyline/frontdoor/wire/EventWireBuilder.kt`

Relevant functions:
- `buildJoinResponse()` — lines 131–149
- `buildMatchResultReport()` — lines 151–171
- `buildEventJson()` — lines 173–212
- `buildCourseJson(Course)` — lines 68–129
- `toCoursesJson()` — lines 60–66
- `toActiveEventsJson()` — lines 40–50
