# Last Session

**Date:** 2026-03-22
**Branch:** `feat/library-search` (off `fix/recording-tooling-and-wire-specs`)

## What happened
- Played 5 proxy recording sessions, documented 3 with notes.md
- Fixed SessionAnalyzer seat filter (PvP seat 2 broke decoding) + CaptureSink session rotation (multi-game recordings merged)
- Split multi-game recordings into separate dirs with per-game bins
- Dispatched 20+ conformance agents mining recordings → 22 wire specs in `docs/plans/`
- Created issues #167-#178 (scry bugs, surveil bug, library search, SBA categories, ChoiceResult, explore, adventure, raid, discard, mill, AbilityWordActive, ward)
- Updated 7 existing issues (#37, #42, #59, #76, #93, #119, #160, #172) with wire data
- PR #179 merged (proxy config guard), PR #180 open (tooling + wire specs)
- Implemented library search (#169): PromptSemantic.Search → SearchReq/SearchResp handshake → Put transfer category. 5/7 plan tasks done, tests passing.

## Open threads
- **Library search playtest blocked**: puzzle crashes — "puzzle card registration requires InMemory" means puzzle infra uses InMemoryCardRepo but search needs real card DB. Fix the card repo or test via `just serve` with a search deck instead. Tasks 6-7 remain.
- **handlePostCastPrompt**: verify Search arm actually gets hit (not just checkPendingPrompt)
- **Surveil 2 still unrecorded**: Inspiration from Beyond is mill+return, not surveil. Need Discovery // Dispersal or Notion Rain deck
- **Counterspell unrecorded**: Essence Scatter never cast. Ward-counter confirmed "Countered" category but no spell-based counter data yet
- **Decompilation request** left in `~/src/mtga-internals/inbox/` for promptId button label mapping
