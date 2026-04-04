---
summary: "How the client Play blade is populated: EventDef, PlayerCourse, QueueConfig entities, hydration flow, and required fields for Find Match."
read_when:
  - "implementing or debugging Play blade / Find Match UI population"
  - "adding a new event type to the FD stub"
  - "understanding EventDef, PlayerCourse, or QueueConfig relationships"
---
# Play Blade Data Model

How the client's Play blade (Find Match / Events / Last Played tabs) is populated.

## Entities

### EventDef (server-owned, static)
Source: CmdType 624 (`Event_GetActiveEventsV2`)

Global event catalog. Defines what events exist and their UX properties.

```
EventDef
├── InternalEventName    (PK, e.g. "Ladder", "Play_Brawl_Historic")
├── EventState           ("Active")
├── FormatType           ("Constructed")
├── Flags[]              ("Ranked", "IsArenaPlayModeEvent", ...)
├── WinCondition         ("SingleElimination", "BestOf3")
├── EventUXInfo
│   ├── PublicEventName
│   ├── DisplayPriority
│   ├── EventBladeBehavior   ("Queue" — required for Find Match hydration)
│   ├── DeckSelectFormat     ("Standard", "Historic", ...)
│   ├── Group                ("" — must be non-null)
│   └── EventComponentData
│       ├── DescriptionText.LocKey
│       └── TitleRankText.LocKey
└── StartTime/LockedTime/ClosedTime
```

### QueueEntry (server-owned, static)
Source: CmdType 1910 (`GetPlayBladeQueueConfig`)

Defines Find Match tabs. Each queue maps to one or two events (BO1/BO3).

```
QueueEntry
├── Id               (PK, e.g. "StandardRanked", "HistoricBrawl")
├── QueueType        ("Ranked" [omitted], "Unranked", "Brawl")
├── LocTitle
├── EventNameBO1 ──→ EventDef.InternalEventName  (FK, required)
├── EventNameBO3 ──→ EventDef.InternalEventName  (FK, optional)
├── DeckSizeBO1/BO3
└── SideBoardBO1/BO3
```

### Course (per-player, mutable)
Source: CmdType 623 (`EventGetCoursesV2`)

Player's event participation state. Tracks which event they entered, with what deck, and progress.

```
Course
├── CourseId          (UUID)
├── InternalEventName ──→ EventDef.InternalEventName  (FK)
├── CurrentModule     ("Complete", "CreateMatch")
├── ModulePayload
├── CourseDeckSummary (V2 deck summary shape)
├── CourseDeck        (full card list)
├── CurrentWins       (optional)
├── CurrentLosses     (optional)
├── CardPool[]
├── CardPoolByCollation[]
└── CardStyles[]
```

### PlayerPreferences (per-player, mutable)
Source: CmdType 1911/1912

Saved UI state including last selected queue.

```
Preferences.PlayBladeSelectionData (JSON string)
├── findMatch
│   ├── QueueType             ("Ranked", "Unranked")
│   ├── QueueIdForQueueType   {QueueType → QueueEntry.Id}
│   ├── QueueId               (last selected QueueEntry.Id)
│   ├── UseBO3                (bool)
│   └── DeckId
└── bladeType                 ("FindMatch", "LastPlayed")
```

## Relationships

```
QueueEntry.EventNameBO1  ──must match──→  EventDef.InternalEventName
QueueEntry.EventNameBO3  ──must match──→  EventDef.InternalEventName

Course.InternalEventName ──references──→  EventDef.InternalEventName
```

## Client Hydration Flow

1. **1910** → client builds `Dictionary<PlayBladeQueueType, List<BladeQueueInfo>>` from queue entries, grouped by QueueType
2. **624** → client stores active events by InternalEventName
3. **`HydrateMockWithRealEvents`** matches each queue's `EventNameBO1` against active events by exact string match on `InternalEventName`
4. **`LockEmptyTabs()`** locks any QueueType with zero hydrated entries
5. **1911** → client restores saved tab selection from preferences, does `dictionary[QueueType]` lookup

## Invariants (enforced by tests)

| Rule | What breaks | Test |
|------|------------|------|
| Every `EventNameBO1`/`BO3` must have matching `InternalEventName` in events | Tabs locked, `LockEmptyTabs()` | `EventRegistryTest: every queue EventNameBO1/BO3 has a matching active event` |
| All 3 QueueTypes (Ranked/Unranked/Brawl) must have entries | `KeyNotFoundException` on tab switch | `FrontDoorHandlerTest: 14 queues` |
| `EventUXInfo.Group` must be non-null | Client NRE | `EventRegistryTest: every event has non-null Group` |
| `EventBladeBehavior: "Queue"` in EventUXInfo | Find Match tabs empty/locked | `FrontDoorHandlerTest: reference shape` |
| Event shape must match reference golden | Missing fields → silent failures | `FrontDoorHandlerTest: every event matches reference shape` |

## Events Tab vs Find Match Tab

- **Find Match**: populated by QueueEntry + matching EventDef (via `EventNameBO1`)
- **Events tab**: populated by Courses (CmdType 623) — player's active event participations
- **Last Played**: populated from `PlayBladeSelectionData.RecentGamesData` in preferences
