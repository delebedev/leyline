---
paths:
  - "matchdoor/src/test/**"
  - "frontdoor/src/test/**"
  - "account/src/test/**"
  - "tooling/src/test/**"
  - "just/test.just"
---

# Tests

One test per behavior, at the fastest level that covers it.

All tests use **Kotest FunSpec** (JUnit Platform). Kotest tags control what runs in matchdoor (the only module with tiered tests).

## Running tests

**Scope tests to the modules you changed.** Don't run all modules when you touched one.

| Changed | Command |
|---|---|
| `matchdoor/` (safe change) | `./gradlew :matchdoor:testGate` |
| `matchdoor/` (risky: StateMapper, bridges, combat, annotations) | `./gradlew :matchdoor:testGate :matchdoor:testIntegration` |
| `frontdoor/` | `./gradlew :frontdoor:test` |
| `account/` | `./gradlew :account:test` |
| `tooling/` | `./gradlew :tooling:testGate` |
| Single class | `just test-one ClassName` |
| Pre-commit (all modules + fmt) | `just test-gate` |

**Test-only changes** (amended assertion, new test case — no prod code touched): `just test-one Foo` is sufficient.

## Matchdoor test tiers

Only matchdoor has tiered tests. Other modules run all tests with plain `test`.

| Tag | Scope | Speed |
|---|---|---|
| `UnitTag` | Pure logic, no engine | ~8s |
| `ConformanceTag` | Needs forge-game, not game loop | ~60s |
| `IntegrationTag` | Full engine boot + game loop thread | ~100s |

`testGate` = Unit + Conformance. `testIntegration` = Integration only.

## Setup tiers (matchdoor, fastest → slowest)

| Setup | Time | Use when |
|---|---|---|
| `startWithBoard{}` | 0.01s | Most tests — zone transitions, events, annotations, state mapping |
| `startPuzzleAtMain1(pzl)` | 0.09s | SBA scenarios, board states needing proper game start |
| `startGameAtMain1()` | 0.5s | Play/cast/resolve pipeline, AI turn shape |

## Tags by module

| Module | Tag | Notes |
|---|---|---|
| `matchdoor` | `UnitTag` / `ConformanceTag` / `IntegrationTag` | Import from `leyline.{UnitTag,ConformanceTag,IntegrationTag}` |
| `frontdoor` | `FdTag` | All tests are unit-level |
| `account` | `UnitTag` | Import from `leyline.account.UnitTag` |
| `tooling` | `UnitTag` | Import from `leyline.UnitTag` (shared with matchdoor) |

**Every test class MUST have a tag.** First line inside `FunSpec({` body.

## Test class shape

```kotlin
import leyline.ConformanceTag  // or UnitTag / IntegrationTag / FdTag

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

- **Test fixture fields** with `afterEach` cleanup: use `var x: T? = null` with `?.` cleanup, since `lateinit var` doesn't work inside FunSpec lambdas.
- **Assert-then-use**: use `checkNotNull(x) { "msg" }` or `val x = expr ?: fail("msg")`. Never `assertNotNull(x); x!!.foo()`.
- **Bare `!!`** on method returns is OK when crash-on-null is the intent.

All `just test-*` targets print a `=== FAILED TESTS ===` summary on failure. No need to grep logs.
