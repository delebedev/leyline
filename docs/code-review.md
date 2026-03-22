# Code Review Checklist

What matters in this project, in order.

## Real bugs first

Logic errors, double-processing, null paths, leaked state, resources not cleaned up. Specific line, what breaks, suggested fix. Everything else is secondary.

## Test quality — right abstraction level

The most common miss. Ask: **is the test checking the right thing?**

- Checking 15 specific field values = fragile. Checking "valid zone transfer with right category" = durable.
- Vacuous passes (assertion never reached, empty collection iterated) are worse than no test.
- Missing negative tests: invalid input, retry, already-complete state.
- Wrong tier: pure builder logic via full game loop = slow. Use `startWithBoard{}` for board-level, unit for pure functions.

### Test patterns

- **`checkNotNull` over `assertNotNull + !!`.** `assertNotNull` returns void, doesn't smart-cast. Use `checkNotNull(x) { "msg" }` or `?: fail("msg")`.
- **`assertNull` over `assertTrue(x == null)`.** Same for `assertFalse(x == null)` → `assertNotNull`.
- **`assertSoftly`** for multi-field assertions — see all failures, not just the first.
- **One test per board setup.** Don't reuse a board for unrelated assertions.
- **Every test class has a group.** Ungrouped tests are invisible to named targets.
- **No duplicate helpers.** Private helper in 2+ files → extract to `ConformanceTestBase` or `TestExtensions.kt`.
- **Use existing extensions.** Check `TestExtensions.kt` before writing inline lookups.
- **No dead variables.** Unused `val origId = ...` — delete or use.

## Separation of concerns

- `matchdoor/bridge/` — Forge coupling lives here, nowhere else
- `matchdoor/game/` — protocol translation (annotations, state mapping, builders)
- `matchdoor/match/` — orchestration (combat, targeting, mulligan)
- `frontdoor/` — zero game engine coupling

Flag protocol details leaking into game logic, bridge concerns outside bridge/, frontdoor importing matchdoor.

## Golden field coverage

- **Builder changes → run golden tests.** Any PR touching `RequestBuilder`, `BundleBuilder`, `GsmBuilder`, `HandshakeMessages`, `StateMapper`, `AnnotationBuilder`, or `ActionMapper` should run `just test-one GoldenFieldCoverageTest`.
- **`NEW EXTRAS` / `REMOVED EXTRAS`** — update `expectedMissing`/`expectedExtra` sets.
- **Comments on expected sets are mandatory.** Each entry explains *why*. Bare sets are not acceptable.
- **New message type → add golden test.** Hand-written fixture, never copied from recordings.

## Public repo rules

- No server responses, recording data, or card database content in tracked files
- No private infra (IPs, hostnames, absolute paths)
- No credentials in any form
