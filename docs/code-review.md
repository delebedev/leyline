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
