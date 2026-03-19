# Game Types & Event System — Research

> **Status:** Research complete (2026-03-04)
> **Scope:** What game types can leyline serve? How does the Arena client discover formats? Can we add custom events?

---

## 1. Arena Client Menu System

The Play blade is **entirely server-driven**. Three CmdType responses control what the player sees:

### Layer 1: PlayBladeQueueConfig (CmdType 1910)

Defines the tabs in the Play blade. Each entry = one tab.

```
PlayBladeQueueEntry {
    Id:            string       // unique queue ID (e.g. "StandardRanked")
    QueueType:     enum         // Ranked=0, Unranked=1, Brawl=2
    LocTitle:      string       // localization key for tab label
    EventNameBO1:  string       // internal event name for Best-of-1
    EventNameBO3:  string       // internal event name for Best-of-3
    DeckSizeBO1:   string       // constraint key (e.g. "Events/Deck_60plus")
    DeckSizeBO3:   string       // constraint key
    SideBoardBO1:  string       // constraint key (e.g. "Events/Sideboard_15minus")
    SideBoardBO3:  string       // constraint key
    Tags:          List<string> // filter tags like "NEW"
}
```

**Current golden file** (`fd-golden/play-blade-queue-config.json`):

| Id | QueueType | EventNameBO1 | EventNameBO3 |
|----|-----------|-------------|-------------|
| StandardRanked | Ranked | Ladder | Traditional_Ladder |
| StandardUnranked | Unranked | Play | Constructed_BestOf3 |
| AIBotMatch | Unranked | AIBotMatch | — |
| HistoricBrawl | Brawl | Play_Brawl_Historic | — |
| StandardBrawl | Brawl | Play_Brawl | — |

### Layer 2: ActiveEventsV2 (CmdType 624)

Provides metadata for each event. Client hydrates tabs by matching `InternalEventName` strings.

```
EventInfoV3 {
    InternalEventName:  string          // MUST match PlayBladeQueueEntry exactly
    FormatType:         string          // "Constructed", "Historic", etc.
    EventState:         string          // "Active", "Locked", "Closed"
    StartTime:          DateTime
    LockedTime:         DateTime
    ClosedTime:         DateTime
    Flags:              List<string>    // see flags table below
    EventTags:          List<string>
    EntryFees:          List<EntryFee>
    EventUXInfo: {
        PublicEventName:    string      // user-facing label
        DisplayPriority:    int         // sort order
        EventBladeBehavior: string      // "Queue"
        DeckSelectFormat:   string      // which deck filter to show
        Group:              string      // category — MUST NOT be null
        Parameters:         Dict
    }
    WinCondition:       string          // "SingleElimination", "BestOf3"
}
```

**Event Flags:**

| Flag | Effect |
|------|--------|
| `Ranked` | Affects rank |
| `UpdateQuests` | Counts for daily quests |
| `UpdateDailyWeeklyRewards` | Counts for daily/weekly wins |
| `IsArenaPlayModeEvent` | Shows in Play blade |
| `SkipDeckValidation` | **Bypasses client-side deck checks** |
| `AllowUncollectedCards` | **Lets deck use cards player doesn't "own"** |
| `IsAiBotMatch` | AI opponent |

### Layer 3: GetFormats + GetSets (CmdTypes 6 + 1521)

Card legality per format. GetFormats uses **protobuf field 2** (not JSON field 3 — common mistake). GetSets provides legal set codes, banned/restricted lists.

### Hydration Flow

```
Client sends CmdType 1910 → server returns PlayBladeQueueEntry list
Client sends CmdType 624  → server returns EventInfoV3 list
Client sends CmdType 623  → server returns courses (must not fail, or Play blade breaks)

For each PlayBladeQueueEntry:
    find EventInfoV3 where InternalEventName == EventNameBO1 (or BO3)
    if found → tab is active
    if not found → LockEmptyTabs() locks the tab
```

**Critical:** InternalEventName matching is **case-sensitive and exact**. Any mismatch = locked tab.

---

## 2. Arena Format Types (compile-time)

The client has a hardcoded `MDNEFormatType` enum. **Cannot be extended** without modifying the client binary.

Known values:
- Standard
- Alchemy
- Historic
- Explorer
- Pioneer
- Modern
- Pauper
- Artisan
- Singleton
- Brawl

Each format type maps to a `DeckFormat` with hardcoded constraints:

```
DeckFormat {
    MinMainDeckCards:          int
    MaxMainDeckCards:          int
    MinSideboardCards:         int
    MaxSideboardCards:         int
    FormatIncludesCommandZone: bool   // true for Brawl/Commander
    BannedTitleIds:            Set<uint>
    RestrictedTitleIds:        Dict<uint, Quota>
    LegalSets:                Set<string>
    RestrictToColorIdentity:  bool    // Commander/Brawl only
    RarityPerCardQuotas:      dict    // Pauper = commons only
}
```

### What We Can Customize Per Format

| Aspect | Customizable? | How |
|--------|--------------|-----|
| Legal sets | Yes | GetSets response (CmdType 1521) |
| Banned cards | Yes | GetSets response |
| Restricted cards | Yes | GetSets response |
| Deck size | Partial | PlayBladeQueueEntry constraint keys |
| Validation bypass | Yes | `SkipDeckValidation` flag |
| Collection bypass | Yes | `AllowUncollectedCards` flag |
| Format name in UI | Partial | `EventUXInfo.PublicEventName` per event |
| New format type | **No** | Compile-time enum |

---

## 3. Forge Engine Format Support

Forge has **comprehensive format support**. Two separate systems:

### DeckFormat (deck structure rules)

```java
// forge-core: forge.deck.DeckFormat
Constructed    (60–∞ main, 0–15 sideboard, 4x card limit)
Limited        (40–∞ main, no SB limit, unlimited copies)
Commander      (99 fixed, 0–10 SB, singleton, color identity)
Oathbreaker    (58 fixed, 0–10 SB, singleton)
Brawl          (59 fixed, 0–15 SB, singleton, color identity)
Pauper         (60 fixed, 0–10 SB, singleton)
TinyLeaders    (49 fixed, 0–10 SB, singleton, CMC ≤ 3)
```

Special copy rules: basic lands, Shadowborn Apostle, Relentless Rats, Rat Colony, Seven Dwarves — all exempt from copy limits.

### GameFormat (card legality)

```java
// forge-game: forge.game.GameFormat
FormatType:    SANCTIONED | CASUAL | ARCHIVED | DIGITAL | CUSTOM
FormatSubType: STANDARD | PIONEER | MODERN | LEGACY | VINTAGE | COMMANDER | PAUPER | ...
```

Format definitions live in text files at `forge-gui/res/formats/`:

```
Sanctioned/     Standard.txt, Pioneer.txt, Modern.txt, Legacy.txt, Vintage.txt, Pauper.txt, Historic.txt
Casual/         Commander.txt, Brawl.txt, Oathbreaker.txt, Premodern.txt, Conspiracy.txt
Archived/       Historical block formats, dated Arena Standard snapshots
```

Each file defines: allowed sets, banned cards, restricted cards.

**Example — Modern.txt:**
```
Sets: 8ED, MRD, ..., ECL  (all sets from 8th Edition forward)
Banned: Amped Raptor, Ancient Den, ..., Yorion, Sky Nomad (50+ cards)
```

### Commander Support (complete)

- 1 or 2 commanders (partner rules)
- Color identity enforcement (all cards match commander colors)
- Commander damage tracking (21+ from single commander = loss)
- Command zone, command tax
- Singleton enforcement (except basic lands)
- `canBeCommander` / `canBePartnerCommanders` predicates

### Mulligan Systems

Forge supports: Original, Paris, Vancouver, London, Houston. Default is London. Mulligan system is global, not per-format (same across all formats). First mulligan free in multiplayer (3+) or Brawl.

---

## 4. Current Leyline State

### Format Enum

```kotlin
// frontdoor/domain/Deck.kt
enum class Format {
    Standard, Historic, Explorer, Timeless, Alchemy, Brawl
}
```

Missing: Pioneer, Modern, Pauper, Artisan, Singleton (all in Arena's client enum).

### Deck Validation

`DeckValidator.kt` does **structural validation only**: file readable, ≥1 card, size 40–250, max 4 copies (basic lands exempt). No format-specific checks (no ban lists, no set legality, no rarity filters).

### Database Schema

`PlayerDb.kt` stores `format` as TEXT column — ready for any format string. `SeedDb.kt` hardcodes all imported decks to `"Standard"`.

### Front Door Serving

Format/event data served from `frontdoor/src/main/resources/fd-golden/`:
- `format-metadata.json` → CmdType 6 (built to protobuf by `FdProtoBuilder`)
- `set-metadata.json` → CmdType 1521 (built to protobuf by `FdProtoBuilder`)
- `play-blade-queue-config.json` → CmdType 1910 (JSON)
- `active-events.json` → CmdType 624 (JSON)

All events currently use `FormatType: "Constructed"` and `DeckSelectFormat: "Standard"`.

### FormatLegalities in Deck Summaries

`FrontDoorService.kt` builds `FormatLegalities` per deck:
- Standard/Historic/Explorer/Timeless/Alchemy → true (if ≥60 cards)
- Brawl → false (always)
- No actual legality check — purely based on card count

---

## 5. Card Pool Analysis

Arena client only renders cards it has locally (art, text, grpId in its SQLite). Forge has all 25,000+ unique cards.

| Format | Arena Card Pool Coverage | Notes |
|--------|------------------------|-------|
| Standard | 100% | Native |
| Historic | 100% | Native (curated anthologies) |
| Explorer | 100% | Native (Pioneer-subset) |
| Timeless | 100% | Native (power cards from anthologies) |
| Alchemy | ~90% | Digital-only cards need Forge support |
| Pioneer | ~95% | Small gap — most RTR-forward sets in Arena |
| Pauper | ~70% subset | Commons-only, many old commons not in Arena |
| Modern | ~60% | 8th Ed forward, large gap pre-Ixalan |
| Legacy | ~30% | All cards legal, massive gap |
| Vintage | ~30% | Same + Power Nine |
| Commander | Varies | 100-card, depends which cards used |

### The Missing Card Problem

When Arena client receives a grpId it doesn't have in its local CardDb:
- **Unknown behavior** — needs empirical testing
- Possible outcomes: crash, blank card, error popup, silent skip
- This determines whether Modern/Legacy/Commander with eternal cards is feasible

### Card Injection Approaches (speculative)

1. **Unknown grpId experiment** — simplest test: put a fake grpId in a deck, see what happens
2. **Client CardDb patching** — inject entries into Arena's local SQLite (card_data.mtga)
3. **Asset injection** — provide card images via local asset override
4. **Placeholder rendering** — if client tolerates unknown grpIds, show text-only or generic art

---

## 6. Feasibility Matrix

### Tier 1 — Immediately Landable (Arena card pool, format type exists in client)

| Format | Arena FormatType | Effort | What's Needed |
|--------|-----------------|--------|---------------|
| Standard | Standard | Trivial | Already works (golden files) |
| Historic | Historic | Low | Update event config, set legal sets |
| Explorer | Explorer | Low | Same |
| Timeless | (reuse Historic) | Low | Custom legal sets via GetSets |
| Pioneer | Pioneer | Low | Few missing cards, format type exists in client |
| Pauper | Pauper | Low | Rarity filter, format type exists |
| Singleton | Singleton | Low | Copy limit = 1, format type exists |
| Artisan | Artisan | Low | Rarity filter (common + uncommon) |

### Tier 2 — Medium Effort (needs leyline wiring)

| Format | Approach | Effort | Blockers |
|--------|----------|--------|----------|
| Brawl (Standard) | Brawl format type | Medium | Command zone wiring in leyline, singleton rules |
| Historic Brawl | Brawl + Historic sets | Medium | Same as Brawl |
| Modern (Arena pool) | Modern format type | Medium | ~40% cards missing, `SkipDeckValidation` as workaround |

### Tier 3 — Needs R&D (card pool gap or rule changes)

| Format | Approach | Effort | Blockers |
|--------|----------|--------|----------|
| Commander | Reuse Brawl slot | High | 100-card, commander damage, command zone, card pool |
| Modern (full) | Modern + card injection | High | Unknown grpId behavior, asset injection |
| Legacy | Reuse slot + injection | Very High | Massive card gap |
| Vintage | Same | Very High | Same + Power Nine |
| Custom/Cube | Any slot + SkipDeckValidation | Low–Medium | Just need `AllowUncollectedCards` |

---

## 7. Event Configuration Cookbook

### Adding a new event to the Play blade

**Step 1:** Add entry to `play-blade-queue-config.json` (CmdType 1910):

```json
{
    "Id": "PioneerRanked",
    "QueueType": "Ranked",
    "LocTitle": "PlayBlade/FindMatch/Blade_Pioneer_Ladder",
    "EventNameBO1": "Pioneer_Ladder",
    "EventNameBO3": "Traditional_Pioneer_Ladder",
    "DeckSizeBO1": "Events/Deck_60plus",
    "DeckSizeBO3": "Events/Deck_60plus",
    "SideBoardBO1": "Events/Sideboard_7minus",
    "SideBoardBO3": "Events/Sideboard_15minus"
}
```

**Step 2:** Add matching event to `active-events.json` (CmdType 624):

```json
{
    "InternalEventName": "Pioneer_Ladder",
    "EventState": "Active",
    "FormatType": "Pioneer",
    "StartTime": "2025-01-01T00:00:00Z",
    "LockedTime": "2099-01-01T00:00:00Z",
    "ClosedTime": "2099-01-01T00:00:00Z",
    "Flags": ["Ranked", "IsArenaPlayModeEvent", "AllowUncollectedCards"],
    "EventTags": [],
    "PastEntries": {},
    "EntryFees": [],
    "EventUXInfo": {
        "PublicEventName": "Pioneer Ranked",
        "DisplayPriority": 95,
        "EventBladeBehavior": "Queue",
        "DeckSelectFormat": "Pioneer",
        "Parameters": {},
        "DynamicFilterTagIds": [],
        "Group": "",
        "PrioritizeBannerIfPlayerHasToken": false,
        "FactionSealedUXInfo": [],
        "Prizes": {},
        "EventComponentData": {}
    },
    "WinCondition": "SingleElimination",
    "AllowedCountryCodes": [],
    "ExcludedCountryCodes": []
}
```

**Step 3:** Ensure GetFormats (CmdType 6) and GetSets (CmdType 1521) include Pioneer format definition with legal sets and ban list.

**Step 4:** Wire the event name in `FrontDoorHandler.kt` match dispatch (CmdType 612 or equivalent for the new event's join flow).

### Kitchen Table / No Rules Mode

Use `SkipDeckValidation` + `AllowUncollectedCards`:

```json
{
    "InternalEventName": "KitchenTable_BO1",
    "FormatType": "Constructed",
    "Flags": ["IsArenaPlayModeEvent", "SkipDeckValidation", "AllowUncollectedCards", "IsAiBotMatch"],
    "EventUXInfo": {
        "PublicEventName": "Kitchen Table",
        "DeckSelectFormat": "Standard"
    }
}
```

### Commander via Brawl Slot

```json
{
    "Id": "Commander",
    "QueueType": "Brawl",
    "LocTitle": "Events/Event_Title_Play_Commander",
    "EventNameBO1": "Play_Commander",
    "DeckSizeBO1": "Events/Deck_100commander",
    "SideBoardBO1": "MainNav/General/Empty_String"
}
```

With matching event using `FormatType: "Brawl"` (closest to Commander in client enum). Leyline would need to override deck size to 100 and wire Forge's Commander rules.

---

## 8. Gotchas & Constraints

### Hard Limits
- **Cannot add new FormatType values** — compile-time enum in client binary
- **Cannot rename format types** — localization keys are baked in
- **GetFormats must use protobuf field 2**, not JSON field 3 (common mistake, causes parse failure)
- **EventGetCoursesV2 (CmdType 623) must not fail** — failure stops the entire events coroutine, no Play blade
- **EventUXInfo.Group must not be null** — causes NRE in client

### Soft Limits
- `LocTitle` references localization keys — unknown keys may show raw key string or empty
- `DeckSelectFormat` controls which filter UI shows — unknown values may default or error
- `DeckSizeBO1/BO3` and `SideBoardBO1/BO3` are constraint keys — unknown keys need testing
- Card rendering requires local data (grpId + art + text) — missing cards show unknown behavior

### Testing Priority
1. Add a Pioneer event → does client show the tab?
2. Use `FormatType: "Modern"` → does client accept it?
3. Try unknown `LocTitle` key → what renders?
4. Try unknown grpId in deck → crash or graceful?
5. Try `DeckSelectFormat: "Pioneer"` → does filter work?

---

## 9. Community Landscape

No known community project has a working custom Arena server at leyline's level:

- **MTGate** (Python) — most complete reverse-engineering. Can authenticate + establish TLS. Cannot play cards (incomplete match protocol).
- **MtgaProto** (riQQ) — proto extraction from `Wizards.MDN.GreProtobuf.Unity.dll`. Source of truth for wire format.
- **di-wu/mtga**, **gathering-gg/parser** — log parsers only, not servers.

Leyline is the furthest any project has gotten with a functional custom Arena server.

---

## 10. Recommended Roadmap

### Phase 1: Dynamic Event Serving
Replace golden files with configurable event/format responses. Immediately unlocks all Tier 1 formats (Standard, Historic, Explorer, Pioneer, Pauper, Singleton, Artisan). Wire `Format` enum expansion + basic deck validation per format using Forge's `GameFormat` definitions.

### Phase 2: Brawl / Command Zone
Wire command zone support in leyline's state mapper and match session. Forge already handles commander rules, color identity, singleton enforcement. Standard Brawl (60-card) as stepping stone.

### Phase 3: Card Injection Spike
Quick experiment: serve a deck containing a grpId the client doesn't know. Determines feasibility of Modern (full pool), Legacy, Vintage, and Commander with eternal cards.

### Phase 4: Full Commander
After Brawl works + card injection understood. 100-card singleton, commander damage tracking, partner rules. Reuse Brawl format type in client.

### Phase 5: Eternal Formats
Modern/Legacy/Vintage with full card pools. Depends on Phase 3 results. May require client-side CardDb patching or asset injection.

---

## 11. Client Imagery & Asset System

Asset delivery model based on local bundles — covers bundle pipeline, integrity checking, injection feasibility.

**TL;DR:** Almost everything is local asset bundles (card art, cosmetics, event banners, rewards). Server sends string IDs, not URLs. Only store items and notifications use server-provided `image_url`. Bundle injection is feasible (no code signing, hash check only at download time, community tool MTGA_Swapper confirms).
