# SearchReq: populate inner fields

**Branch:** `feat/library-search`
**Issue:** #169
**Root cause of:** "No Card to Choose" in search picker

## Problem

`BundleBuilder.buildSearchReq` sends `SearchReq.getDefaultInstance()` — all inner fields empty. The client uses `itemsSought` to populate the search picker. Without it: nothing to select.

## What the real server sends

Source: raw protoc decode of `recordings/2026-03-21_22-05-00`, frame 112 (bundled with GSM gsId 50).

```protobuf
searchReq {
  maxFind: 1
  zonesToSearch: 36          # library zone ID (seat-relative)
  itemsToSearch: 227         # ALL library card instanceIds (52 entries)
  itemsToSearch: 228
  ...
  itemsSought: 257           # FILTERED subset — basic lands only (21 entries)
  itemsSought: 254
  ...
  sourceId: 290              # casting spell's instanceId (Bushwhack)
  allowFailToFind: Any       # "up to" — can decline
}
```

Prompt wrapper also has parameters:
```protobuf
prompt {
  promptId: 1065
  parameters {
    parameterName: "CardId"
    type: Number
    numberValue: 290          # source spell instanceId
  }
  parameters {
    parameterName: "CardId"
    type: Number
    numberValue: 1            # unknown — maybe seat?
  }
}
```

## What to change

### 1. `BundleBuilder.buildSearchReq` — populate SearchReq fields

Signature needs the search context from the Forge prompt:

```kotlin
fun buildSearchReq(
    msgId: Int,
    gsId: Int,
    seatId: Int,
    sourceInstanceId: Int,         // casting spell
    libraryZoneId: Int,            // seat-relative library zone
    allLibraryIds: List<Int>,      // all card instanceIds in library
    validTargetIds: List<Int>,     // cards matching search criteria
    maxFind: Int,                  // from Forge prompt (usually 1)
    allowFailToFind: Boolean,      // "up to" = true
): GREToClientMessage
```

Map to proto:
- `searchReq.maxFind` = maxFind
- `searchReq.zonesToSearch` = [libraryZoneId]
- `searchReq.itemsToSearch` = allLibraryIds
- `searchReq.itemsSought` = validTargetIds
- `searchReq.sourceId` = sourceInstanceId
- `searchReq.allowFailToFind` = if allowFailToFind then `AllowFailToFind.Any` else default

### 2. `TargetingHandler.sendSearchReq` — extract search params from Forge

The Forge `choose_cards` prompt that triggers `PromptSemantic.Search` has:
- `pendingPrompt.candidates` — the valid card choices (= `itemsSought`)
- Library zone accessible via `bridge.getPlayer(seatId).getZone(Library)`
- Source card ID from `pendingPrompt.sourceForgeCardId` or the spell on stack

Wire it:
```kotlin
val player = bridge.getPlayer(SeatId(ops.seatId))
val library = player.getZone(ForgeZoneType.Library)
val allLibIds = library.cards.map { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }
val validIds = pendingPrompt.candidates.map { bridge.getOrAllocInstanceId(ForgeCardId(it.forgeCardId)).value }
val sourceId = bridge.getOrAllocInstanceId(ForgeCardId(pendingPrompt.sourceForgeCardId)).value
```

### 3. Prompt parameters

Add two `CardId` parameters to the prompt:
- `numberValue = sourceInstanceId`
- `numberValue = 1` (observed; possibly seat ID or count — needs verification)

### 4. Update `SearchReqTest`

Current test asserts `msg.hasSearchReq() == true` and checks GRE wrapper fields. Add assertions for inner SearchReq fields: `maxFind`, `zonesToSearch`, `itemsToSearch`, `itemsSought`, `sourceId`, `allowFailToFind`.

## Verification

1. Run `just test-one SearchReqTest` — inner field assertions pass
2. Run `just test-one LibrarySearchConformanceTest` — shape assertions pass
3. Run `just serve-puzzle puzzles/library-search-lethal.pzl` + Arena — search picker shows Mountains
4. If possible: proxy session with Sylvan Ranger deck, compare SearchReq via `protoc --decode` against our output

## Discovery note

The recording decoder (`RecordingDecoder.kt`) does not extract SearchReq inner fields — it only captures the GRE wrapper (`greType`, `msgId`, `gsId`, `promptId`, `systemSeatIds`). The wire spec incorrectly stated "SearchReq with all fields empty" based on the decoder output. The root cause was found by decoding the raw `.bin` with `protoc --decode`.
