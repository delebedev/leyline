# Last Session

**Date:** 2026-03-18
**Branch:** `fd-golden-bin-to-json`

## What happened
- Replaced captured binary golden files (`get-formats-response.bin` 292KB, `get-sets-response.bin` 46KB) with code-generated protobuf from hand-written JSON
- `FdProtoBuilder` uses `protobuf-java` `UnknownFieldSet.mergeField()` to build proto at startup — `addField` replaces, `mergeField` appends for repeated fields
- Key discovery: `GetFormatsResponse` field 2 (`formatGroups`) required — `EvergreenFormats`, `ConstructedSortOrder`, `BannedFormats`. Missing = client hang
- Key discovery: `SetMetadataResponse` field 2 (`SetGroups`) — `AllFilters` group required for collection filter UI
- Filed decompilation request + got results in `~/src/mtga-internals/docs/format-init-analysis.md`
- Scrub checklist updated in `~/src/mtga-internals/docs/legal/leyline-scrub.md`

## Changed
- `frontdoor/build.gradle.kts` — added `protobuf-java` dep
- `FdProtoBuilder.kt` — new: JSON→proto builder (formats + sets + groups)
- `format-metadata.json` — 21 formats, 5 shared set pools, 3 format groups
- `set-metadata.json` — 109 sets, 1 set group (AllFilters)
- `GoldenData.kt` — calls builder instead of loading .bin

## Open threads
- Branch not merged to main yet — needs PR
- `StoreSetGroups` (47 entries) deferred — store UI cosmetic
- `SetMetadataResponse` field 4 (`SetCollectionGroup`) not investigated
