# Quick Draft Implementation — Reflections

## Went great

- **Golden-first approach** — starting with recorded payloads as stubs, then graduating to real logic incrementally. Every intermediate state was functional — the client never saw broken responses.
- **Recording as source of truth** — the proxy recording from 2026-03-07 was invaluable. Every conformance question (PickNumber on completion, DTO_InventoryInfo shape, course visibility) was answered by querying the recording, not guessing.
- **DraftService design** — injecting pack generation as a lambda kept Forge coupling at zero in frontdoor. Variable pack sizes were a real issue (Forge generates 14-card ECL boosters) and the dynamic `packs.sumOf { it.size }` approach handled it cleanly.
- **arena detect for draft picking** — once we had bounding box detection, the 39-card draft loop was trivial and reliable.

## Less great

- **sendMatchCreated positional arg bug** — cost significant debugging time. Named parameters should be the rule for any function with >3 string args. This was a "one wrong positional arg breaks everything silently" bug.
- **Too many scripts during draft automation** — kept writing temp Python scripts when composable `arena` commands were wanted. Direct `detect | filter | click` is cleaner and more debuggable.
- **Clicking Deck Details sidebar** — happened twice because `arena detect` returns cards in the sidebar too. Should have established the `cx<700` filter immediately after the first failure instead of trying different fixed coords.
- **Confirmation dialogs** — both "Confirm Purchase" and "Resign?" dialogs caught me off guard. OCR couldn't find "OK" reliably (small text, dark background). Should screenshot first on any modal interaction, not retry blindly.
- **Context exhaustion** — the session ran so long it compacted. Splitting into smaller focused sessions (domain model -> wire -> integration -> playtest) would have preserved more context.

## Process improvements

1. **Always named params** for wire builders / envelope constructors — positional strings are landmines
2. **Filter detect results immediately** — establish bounding box regions (draft grid, hand, sidebar) as constants, not ad-hoc per click
3. **Screenshot on unexpected state** — if OCR fails to find expected text, screenshot before retrying. Costs ~800 tokens but saves 3-4 blind retries
4. **Conformance check earlier** — ran conformance comparison after the feature was "done". Running it after the first working pick would have caught PickNumber/DTO_InventoryInfo sooner
5. **Commit more frequently** — the initial mega-commit (sendMatchCreated fix + resign + variable packs) bundled too many concerns. Each was independently valuable and testable
