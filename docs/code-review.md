# Code Review Checklist

## Tests

- **No duplicate helpers.** Private helper appearing in 2+ test files → extract to `ConformanceTestBase` or `TestExtensions.kt`.
- **Use existing extensions.** Check `TestExtensions.kt` before writing inline annotation/zone/object lookups (e.g. `annotationOrNull`, `findZoneTransfer`, `annotationAffecting`).
- **`checkNotNull` over `assertNotNull + !!`.** `assertNotNull` returns void, doesn't smart-cast. Use `checkNotNull(x) { "msg" }` or `?: fail("msg")`.
- **Prefer `assertNull` over `assertTrue(x == null)`.** Same for `assertFalse(x == null)` → `assertNotNull`.
- **Minimize board setup boilerplate.** Use base class helpers: `stateOnlyDiff()`, `captureAfterAction()`, `Player.firstCreature()`, `Player.firstCardIn()`.
- **No dead variables.** Unused `val origId = ...` etc. Delete or use.
- **Test at the fastest tier.** Pure proto/builder logic → `unit`. Needs Game but not game loop → `conformance` + `startWithBoard{}`. Needs cast/resolve/AI → `integration`.
- **Every test class has a group.** Ungrouped tests are invisible to all named targets.

## Golden field coverage

See `docs/golden-tests.md` for full details.

- **Builder changes → check golden tests.** Any PR touching `RequestBuilder`, `BundleBuilder`, `GsmBuilder`, `HandshakeMessages`, `StateMapper`, `AnnotationBuilder`, or `ActionMapper` should run `just test-one GoldenFieldCoverageTest`. If a field is added/removed, the golden test will fail with `NEW EXTRAS` or `REMOVED EXTRAS` — the PR must update `expectedMissing`/`expectedExtra` sets with an explaining comment.
- **Don't silently add fields.** If a builder change adds new fields to a proto message, golden tests catch it. This is intentional — extras may confuse the client or waste bandwidth. Triage: is the field needed? Document in `expectedExtra` if it's a deliberate UX addition (e.g. `manaPaymentOptions`).
- **Don't silently fix gaps.** If a builder change fills in a previously-missing field, golden tests catch it via `FIXED GAPS`. Remove the field from `expectedMissing` — this is a good thing, and the test validates the fix.
- **New message type → add golden test.** When implementing a new GRE message builder (e.g. `GroupReq` for mulligan), add a golden test if a recording exists. Extract the payload: `cp recordings/<session>/capture/payloads/<file>.bin src/test/resources/golden/<name>.bin`.
- **Comments on expectedMissing/expectedExtra are mandatory.** Each entry must explain *why* the gap or extra exists. Bare sets with no comments are not acceptable — they defeat the purpose of living documentation.
