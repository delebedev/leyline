---
paths:
  - "forge-nexus/src/test/**"
---

# Nexus Tests

One test per behavior, at the fastest level that covers it.

TestNG `groups` control what runs. `just test-gate` = unit + conformance + integration.

| Target | Group | Scope | Speed |
|---|---|---|---|
| `just test-unit` | `unit` | Pure logic, no engine | ~1s |
| `just test-conformance` | `conformance` | Needs forge-game, not game loop | ~60s |
| `just test-integration` | `integration` | Full engine boot + game loop thread | ~30s |
| `just test-gate` | unit+conformance | Pre-commit gate | ~60s |
~20s of every run is Maven startup.

**Before committing:** `just test-gate`. After risky changes (StateMapper, GameBridge, annotation pipeline, zone transitions, combat): `just test-integration`.

**Test-only changes** (amended assertion, new test case, reworded message — no prod code touched): `just test-one <ClassName>` is sufficient. Skip `test-gate`.

**Every test class MUST have a group.** `@Test(groups = ["unit"])` / `["conformance"]` / `["integration"]`. Ungrouped tests are invisible to all named targets.

## Setup tiers (fastest → slowest)

| Setup | Time | What it does | Use when |
|---|---|---|---|
| `startWithBoard{}` | 0.01s | `GameBootstrap.createGame()` + `GameBridge.wrapGame()`, cards via `addCard()` | Most tests — zone transitions, events, annotations, state mapping |
| `startPuzzleAtMain1(pzl)` | 0.09s | Inline `.pzl` text, skips mulligan | SBA scenarios, board states needing proper game start |
| `startGameAtMain1()` | 0.5s | Full engine boot, mulligan, priority | Play/cast/resolve pipeline, AI turn shape |

New test class → pick tier: pure parser/builder = `unit`, needs cards/bridge but not game loop = `conformance` with `startWithBoard{}`, needs cast/resolve/AI = `integration` with `startGameAtMain1()`.

**Never `?: return` in test methods** — use `?: fail("reason")`. Silent pass on null = hidden bug.

## Nullability in tests

- **Test fixture fields** (`harness`, `bridge`) with `@AfterMethod` cleanup: use `private lateinit var x: T`, not `private var x: T? = null`. Teardown: `if (::x.isInitialized) x.shutdown()`. Eliminates `!!` everywhere.
- **Assert-then-use**: never `assertNotNull(x); x!!.foo()` — `assertNotNull` returns `void`, doesn't smart-cast. Use `checkNotNull(x) { "msg" }` (stdlib, returns `T`, throws `IllegalStateException`). Or `val x = expr ?: fail("msg")` with a `fun fail(msg): Nothing` helper.
- **Bare `!!`** on method returns (`b.getGame()!!`, `b.getPlayer(1)!!`) is OK when crash-on-null is the intent and there's no preceding assertNotNull.

All `just test-*` targets print a `=== FAILED TESTS ===` summary with class, method, and assertion message on failure. No need to re-run tests or grep logs for failure details — the summary has everything.
