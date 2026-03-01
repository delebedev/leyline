---
paths:
  - "src/test/**"
---

# Leyline Tests

One test per behavior, at the fastest level that covers it.

All tests use **Kotest FunSpec** (JUnit Platform). Kotest tags control what runs. `just test-gate` = unit + conformance.

| Target | Tag | Scope | Speed |
|---|---|---|---|
| `just test-unit` | `UnitTag` | Pure logic, no engine | ~8s |
| `just test-conformance` | `ConformanceTag` | Needs forge-game, not game loop | ~60s |
| `just test-integration` | `IntegrationTag` | Full engine boot + game loop thread | ~100s |
| `just test-gate` | `UnitTag \| ConformanceTag` | Pre-commit gate | ~60s |

**Before committing:** `just test-gate`. After risky changes (StateMapper, GameBridge, annotation pipeline, zone transitions, combat): `just test-integration`.

**Test-only changes** (amended assertion, new test case, reworded message — no prod code touched): `just test-one Foo` is sufficient. Skip `test-gate`.

**Every test class MUST have a tag.** `tags(UnitTag)` / `tags(ConformanceTag)` / `tags(IntegrationTag)` as the first line inside the `FunSpec({` body. Add `import leyline.UnitTag` (or corresponding tag). Untagged tests run in `just test` but are invisible to filtered targets.

## Setup tiers (fastest → slowest)

| Setup | Time | What it does | Use when |
|---|---|---|---|
| `startWithBoard{}` | 0.01s | `GameBootstrap.createGame()` + `GameBridge.wrapGame()`, cards via `addCard()` | Most tests — zone transitions, events, annotations, state mapping |
| `startPuzzleAtMain1(pzl)` | 0.09s | Inline `.pzl` text, skips mulligan | SBA scenarios, board states needing proper game start |
| `startGameAtMain1()` | 0.5s | Full engine boot, mulligan, priority | Play/cast/resolve pipeline, AI turn shape |

New test class → pick tier: pure parser/builder = `unit` with `UnitTag`, needs cards/bridge but not game loop = `conformance` with `ConformanceTag` + `startWithBoard{}`, needs cast/resolve/AI = `integration` with `IntegrationTag` + `startGameAtMain1()`.

**Never `?: return` in test methods** — use `?: fail("reason")`. Silent pass on null = hidden bug.

## Test class shape

```kotlin
import leyline.ConformanceTag  // or UnitTag / IntegrationTag

class FooTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("some behavior") {
        val (bridge, game) = base.startWithBoard { ... }
        // assertions
    }
})
```

## Nullability in tests

- **Test fixture fields** (`harness`, `bridge`) with `afterEach` cleanup: use `var x: T? = null` with `?.` cleanup in `afterEach`, since `lateinit var` doesn't work inside FunSpec lambdas.
- **Assert-then-use**: never `assertNotNull(x); x!!.foo()` — `assertNotNull` returns `void`, doesn't smart-cast. Use `checkNotNull(x) { "msg" }` (stdlib, returns `T`, throws `IllegalStateException`). Or `val x = expr ?: fail("msg")` with a `fun fail(msg): Nothing` helper.
- **Bare `!!`** on method returns (`b.getGame()!!`, `b.getPlayer(1)!!`) is OK when crash-on-null is the intent and there's no preceding assertNotNull.

All `just test-*` targets print a `=== FAILED TESTS ===` summary with class, method, and assertion message on failure. No need to re-run tests or grep logs for failure details — the summary has everything.
