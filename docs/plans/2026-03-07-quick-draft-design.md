# Quick Draft Implementation Design

**Date:** 2026-03-07
**Recording:** `recordings/2026-03-07_17-57-22/`
**Event:** `QuickDraft_ECL_20260223` (Lorwyn Eclipsed)

## Summary

Add Quick Draft (Bot Draft) support. Player drafts 3 packs × ~14 picks against AI bots, builds a 40-card deck, plays Bo1 matches (7 wins / 3 losses). Reuses existing Course infrastructure from sealed; new domain: draft session with pick-by-pick state.

## Protocol Reference

### Sequence table

| Step | Seq | CmdType (ID) | Dir | Key Data |
|------|-----|-------------|-----|----------|
| Join | 143→144 | Event_Join (600) | C2S→S2C | Pay 750 gems, Course module=BotDraft, CardPool=[] |
| Start draft | 145→146 | BotDraft_StartDraft (1800) | C2S→S2C | First pack (13 cards), draftStatus=PickNext |
| Status check | 149→150 | BotDraft_DraftStatus (1802) | C2S→S2C | Resume point — returns current pack state |
| Pick card | 165→166 | BotDraft_DraftPick (1801) | C2S→S2C | Pick grpId, get next pack (12→11→...→1 cards) |
| ... | | BotDraft_DraftPick × 39 | | 3 packs × 13 picks = 39 total |
| Last pick | | BotDraft_DraftPick | | draftStatus=Completed, pickedCards=[39] |
| Courses | 303 | Event_GetCoursesV2 (623) | | module=DeckSelect, CardPool=[39 grpIds] |
| Set deck | | Event_SetDeckV2 (622) | | 40-card deck (39 drafted + basics) |
| Courses | 329 | Event_GetCoursesV2 (623) | | module=CreateMatch, CourseDeck populated |
| Queue | | Event_EnterPairing (603) | | Same as sealed from here |

### Wire shapes

**BotDraft_StartDraft (1800)**
- Request: `{"EventName": "QuickDraft_ECL_20260223"}`
- Response: Course-wrapped — `{"CurrentModule":"BotDraft","Payload":"{...}"}`
- Payload: `{Result, EventName, DraftStatus, PackNumber, PickNumber, NumCardsToPick, DraftPack:[grpIds], PackStyles:[], PickedCards:[], PickedStyles:[]}`

**BotDraft_DraftPick (1801)**
- Request: `{"EventName":"...","PickInfo":{"EventName":"...","CardIds":["98353"],"PackNumber":0,"PickNumber":0}}`
- Response: Same Course-wrapped shape + `DTO_InventoryInfo`
- Payload: Same as StartDraft — DraftPack shrinks by 1, PickedCards grows by 1
- Last pick: `DraftStatus:"Completed"`, `DraftPack:[]`

**BotDraft_DraftStatus (1802)**
- Request: `{"EventName":"..."}`
- Response: Same Course-wrapped shape — returns current draft state (for resume)

**Event_Join for draft**
- Response: Course with `CurrentModule:"BotDraft"`, `CardPool:[]` (pool built during draft, not upfront like sealed)

**Event_GetCoursesV2 after draft complete**
- Course: `CurrentModule:"DeckSelect"`, `CardPool:[39 grpIds]` (the picked cards)

### Key differences from sealed

| | Sealed | Quick Draft |
|---|---|---|
| Join → pool | Immediate (6 packs opened) | Empty pool → draft picks build it |
| CourseModule after join | DeckSelect | BotDraft |
| New CmdTypes | None | 1800, 1801, 1802 |
| Card pool source | Pack generation (server-side) | Pick-by-pick (client-driven) |
| Pool size | 84 cards (6×14) | 39 cards (3×13) |
| Draft state | None | Pack/pick counters, current pack contents, picked cards |
| Deck size | 40 | 40 |
| Match flow | Identical | Identical |

### Enums

```
DraftStatus: None=0, PickNext=1, Completed=2
BotDraftCmdResult: Success=0, Error_DraftNotFound=1, ...AlreadyExists=3, Error_BadPick=4, ...
```

## Implementation Scope

### What we reuse (zero new code)

- **Course entity + persistence** — draft course = sealed course with module=BotDraft then DeckSelect then CreateMatch
- **CourseService** — join(), setDeck(), enterPairing(), recordMatchResult(), drop() — all work for draft
- **EventWireBuilder** — buildCourseJson(), buildJoinResponse(), buildSetDeckResponse()
- **FrontDoorHandler** — Event_Join, Event_SetDeckV2, Event_EnterPairing, Event_GetCoursesV2 handlers
- **EventRegistry** — add QuickDraft EventDef (trivial)
- **Match lifecycle** — identical to sealed post-deck-submit

### What's new

1. **Draft session domain** — stateful draft with packs, picks, AI bot picks
   - `DraftSession` entity (draftId, eventName, packs, currentPack, currentPick, pickedCards, draftStatus)
   - Persistence: SQLite `draft_sessions` table (or JSON blob in courses)
   - Pack generation: Forge `BoosterDraft` (8-player pod, AI picks, pack passing)

2. **BotDraft CmdType handlers** — 3 new handlers in FrontDoorHandler
   - `BotDraft_StartDraft (1800)` — create draft session, generate packs, run AI picks for pack 1, return first human pack
   - `BotDraft_DraftPick (1801)` — validate pick, advance pack/pick, run AI picks, return next state
   - `BotDraft_DraftStatus (1802)` — return current draft state (for resume/reconnect)

3. **Draft wire builder** — serialize BotDraft responses
   - Course-wrapped format: `{"CurrentModule":"BotDraft","Payload":"{...}"}`
   - Payload is JSON-string-in-JSON (double-encoded)

4. **CmdType registry** — register 1800, 1801, 1802 in CmdType enum

5. **CourseModule.BotDraft** — new enum variant (already exists in the enum, just unused)

### What we defer

- `Draft_CompleteDraft (1908)` — not observed in recording (client doesn't send it for Quick Draft)
- Pack styles / card styles — empty arrays, cosmetic
- Draft timer / AFK detection — Quick Draft has no timers
- `BotDraft_Debug_UnloadCache (1907)` — debug only
- `Event_PlayerDraftReserveCard (625)` / `ClearReservedCard (626)` — not used in bot draft

## Architecture

```
frontdoor/domain/
  DraftSession.kt          — draft state entity
  DraftSessionRepository.kt — persistence interface

frontdoor/service/
  DraftService.kt          — draft business logic (create, pick, status)
  CourseService.kt          — minor: joinDraft() creates course + draft session

frontdoor/wire/
  CmdType.kt               — add 1800, 1801, 1802
  DraftWireBuilder.kt      — serialize BotDraft responses

frontdoor/FrontDoorHandler.kt — add 3 handlers

matchdoor/ (via lambda)
  — pack generation: Forge BoosterDraft for ECL set
```

### Cross-module wiring

Same pattern as sealed: `LeylineServer` wires a `generateDraftPacks: (setCode) -> List<List<Int>>` lambda that closes over Forge. DraftService calls it; frontdoor stays Forge-free.

## Phases

### Phase 1: Domain + EventDef
- DraftSession entity, repository, SQLite table
- DraftService with unit tests (create, pick, status, completion)
- QuickDraft EventDef in EventRegistry
- CmdType additions
- **Gate:** unit tests pass

### Phase 2: Handlers + wire
- BotDraft_StartDraft, DraftPick, DraftStatus handlers
- DraftWireBuilder (Course-wrapped double-encoded JSON)
- Wire CourseService.join() to handle draft events (module=BotDraft, empty pool)
- **Gate:** `just serve` → client joins Quick Draft → draft UI loads → picks work → deck builder opens

### Phase 3: Forge integration
- Wire pack generation lambda (Forge BoosterDraft with AI)
- Real grpId mapping for drafted cards
- **Gate:** drafted cards are real ECL cards, deck builder shows them correctly

### Phase 4: Polish + conformance
- Compare wire output against recording
- Match lifecycle (play, W/L, completion)
- **Gate:** full Quick Draft lifecycle end-to-end
