# Library Search Implementation Retrospective

**Date:** 2026-03-22
**Branch:** `feat/library-search`
**Issue:** #169

## What we built

SearchReq/SearchResp handshake for library search. When Forge resolves a search spell (Sylvan Ranger ETB), we:

1. Classify the prompt as `PromptSemantic.Search`
2. Send a GSM with library card objects revealed (Private visibility)
3. Send `SearchReq` (GRE type 44, empty proto)
4. Receive `SearchResp` from client
5. Auto-resolve the Forge prompt with default selection
6. Transfer category uses `Put` instead of `Draw` for search-to-hand

## What works

- **Protocol handshake**: SearchReq sent, client opens search picker UI, SearchResp received
- **Library reveal**: GSM before SearchReq includes full GameObjectInfo for library cards
- **Transfer category**: Library→Hand via search correctly uses `Put` (not `Draw`)
- **Puzzle infra**: Fixed ExposedCardRepository→InMemoryCardRepository swap for puzzle mode
- **Puzzle infra**: Fixed seat 2 (FamiliarSession) rejection in puzzle mode

## What doesn't work yet

**Client shows "No Card to Choose"** — the search picker opens but displays no selectable cards. The client receives our library card objects but its internal filter doesn't match them as "basic land."

Root cause not fully diagnosed. Candidates:
- SearchReq needs populated fields (`itemsToSearch`, `zonesToSearch`) to tell the client what to filter for
- Card object proto fields may be missing or wrong (supertypes, subtypes not matching client expectations)
- The client may need a specific `searchFilter` or `itemsSought` in SearchReq to know what card types are valid

## Lessons

### 1. Recording is the spec (again)

The wire spec from Bushwhack's search (`docs/plans/2026-03-21-library-search-wire-spec.md`) showed SearchReq with **all fields empty** for basic-land search. We implemented that literally. But "empty" in the recording might mean "fields present but decoded as default values" — the proto encoding doesn't distinguish between "field not set" and "field set to 0/empty." We may need to set `itemsSought` or `zonesToSearch` explicitly.

### 2. Puzzle card types are correct but insufficient

Forge's puzzle-created Mountains have `isBasicLand=true`, `type=[Land]`, `supertypes=[Basic]` — verified via debug logging. The Forge engine recognizes them as valid search targets. The problem is between our ObjectMapper proto output and the client's card filter — a conformance gap in the card object, not the search protocol.

### 3. Library reveal GSM is a new pattern

No other mechanic reveals hidden zone contents to the client mid-game. The `revealLibraryForSeat` flag approach works but is ad-hoc. If we need this for explore, scry library view, or other mechanics, it should be a first-class concept in StateMapper.

### 4. The confirm prompt intercept masks timing

Forge sends "Use this ability?" as a confirm prompt. Our `checkPendingPrompt` auto-resolves it (defaultIndex=0 = "yes"). Only then does the engine proceed to search and produce the `choose_cards` prompt tagged as `Search`. This two-step flow works but is fragile — if the auto-resolve picks "no" (index 1), the search is skipped and no SearchReq is sent.

## Next steps

1. **Populate SearchReq fields** — check if `itemsSought`, `zonesToSearch`, or `searchFilter` are needed for the client to populate the picker
2. **Compare card object proto** against recording — field-by-field diff of our Mountain GameObjectInfo vs the real server's
3. **Test with `just serve`** (normal bot match) instead of puzzle — use a deck with Sylvan Ranger to bypass puzzle card issues
4. **Check decompilation request** — `mtga-internals/inbox/` has a request for SearchReq client-side handling
