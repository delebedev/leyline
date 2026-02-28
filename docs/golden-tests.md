# Golden Field Coverage Tests

Compare our proto builder output against real Arena server recordings at the field-presence level.

## How it works

1. **Golden `.bin`** files in `src/test/resources/golden/` — extracted from proxy recordings (`recordings/<session>/capture/payloads/`).
2. **`FieldPathExtractor`** walks a protobuf message recursively, producing `Set<String>` of dot-separated field paths (`[]` for repeated). Only non-default (actually set) fields are included.
3. **`GoldenFieldCoverageTest`** builds our proto via real builders (`RequestBuilder`, `BundleBuilder`, `HandshakeMessages`, `GsmBuilder`) with a minimal `startWithBoard{}` game, then diffs field sets.
4. Two documented sets per test: **`expectedMissing`** (fields golden has that we don't) and **`expectedExtra`** (fields we produce that golden doesn't). Test fails on ANY change to either set.

## The sets ARE the spec

`expectedMissing` and `expectedExtra` are the living documentation. No separate field-gap spreadsheet needed.

- **New gap discovered** → test fails → triage: add to `expectedMissing` with a comment explaining why, or fix the builder.
- **Gap fixed** → test fails → remove from `expectedMissing`. This validates the fix.
- **New extra** → test fails → triage: add to `expectedExtra` with rationale (UX addition? bug?), or remove from builder.
- **Extra removed** → test fails → remove from `expectedExtra`.

## Current coverage

| Message | Gaps | Extras | Notes |
|---|---|---|---|
| ConnectResp | 5 | 0 | deckCards (test artifact), changelists, skins (cosmetic) |
| DieRollResultsResp | 0 | 0 | Full coverage |
| Initial Full GSM | 7 | 0 | Timeout config, pendingMsg, timers, library IDs, diffDeleted quirk |
| MulliganReq | 0 | 0 | Full coverage |
| SetSettingsResp | 0 | 0 | Full coverage (round-trips golden settings) |
| IntermissionReq | 0 | 0 | Full coverage |
| SelectTargetsReq | 6 | 0 | Sub-prompt, abilityGrpId, targetingAbilityGrpId |
| DeclareAttackersReq | 0 | 0 | Full coverage |
| DeclareBlockersReq | — | — | Skipped (combat setup incomplete with `startWithBoard`) |
| ActionsAvailableReq | 8 | 12 | manaCost color[], inactiveActions / manaPaymentOptions, manaSelections, isBatchable |

## Adding a new golden test

1. Find the recording payload: `recordings/<session>/md-frames.jsonl` maps payload files to message types.
2. Copy: `cp recordings/<session>/capture/payloads/<file>.bin src/test/resources/golden/<name>.bin`
3. Add test in `GoldenFieldCoverageTest`:
   - `loadGoldenGRE("<name>.bin", GREMessageType.X)` to parse
   - Build ours via the real builder
   - `FieldPathExtractor.extract()` both sides
   - `assertFieldCoverage(label, golden, ours, expectedMissing, expectedExtra)`
4. Run, triage gaps into `expectedMissing`/`expectedExtra` with comments.

## Updating a golden

When the real server changes (new Arena patch), re-capture via proxy and replace the `.bin`:

```
cp recordings/<new-session>/capture/payloads/<file>.bin src/test/resources/golden/<name>.bin
```

Tests will fail with `NEW GAPS` or `REMOVED EXTRAS` — triage each change.

## Not yet covered

- **GroupReq** (London mulligan card selection) — builder not implemented yet. See `docs/mulligan-plan.md`.
- **DeclareBlockersReq** — needs puzzle-based combat setup; `startWithBoard` + `devModeSet` doesn't fully initialize blocker state.
- **Diff GSM with annotations** — would cover the stateful diff pipeline (`StateMapper.buildDiffFromGame`). Needs a board that produces zone transfers.
- **PromptReq** (starting player notification) — simple message, low priority.

## Key files

| File | Role |
|---|---|
| `src/test/kotlin/.../conformance/GoldenFieldCoverageTest.kt` | Test class |
| `src/test/kotlin/.../conformance/FieldPathExtractor.kt` | Proto field walker + diff formatter |
| `src/test/resources/golden/*.bin` | Real Arena server payloads |
| `recordings/2026-02-28_09-33-05/` | Primary proxy recording source |
| `recordings/2026-02-28_09-33-05/md-frames.jsonl` | Payload-to-message-type index |
