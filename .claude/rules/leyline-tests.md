---
paths:
  - "matchdoor/src/test/**"
  - "frontdoor/src/test/**"
  - "account/src/test/**"
  - "tooling/src/test/**"
  - "just/test.just"
---

# Tests

All tests use **Kotest FunSpec** (JUnit Platform). One test per behavior, at the fastest setup tier that covers it.

## Running tests

Scope to the modules you changed. Don't run all modules when you touched one.

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

## Tags

**Every test class MUST have a tag.** First line inside `FunSpec({` body.

| Module | Tag | Notes |
|---|---|---|
| `matchdoor` | `UnitTag` / `ConformanceTag` / `IntegrationTag` | Import from `leyline.{UnitTag,ConformanceTag,IntegrationTag}` |
| `frontdoor` | `FdTag` | All tests are unit-level |
| `account` | `UnitTag` | Import from `leyline.account.UnitTag` |
| `tooling` | `UnitTag` | Import from `leyline.UnitTag` (shared with matchdoor) |

`testGate` = Unit + Conformance. `testIntegration` = Integration only.

## Setup tiers (matchdoor)

| Setup | Time | Use when |
|---|---|---|
| `startWithBoard{}` | 0.01s | **Default.** Zone transitions, events, annotations, state mapping, action fields |
| `startPuzzleAtMain1(pzl)` | 0.09s | SBA scenarios, board states needing proper game start |
| `startGameAtMain1()` | 0.5s | Play/cast pipeline through bridge (needs engine thread) |
| `connectAndKeep()` | 0.7-3s | Full MatchSession — auto-pass, combat, targeting, game-over flows |

**Bias toward `startWithBoard`.** If the test doesn't call `passPriority()` or need the game loop thread, it probably belongs at board level with `captureAfterAction` + synchronous `game.action.*`.

## Test class shape

```kotlin
class FooTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("some behavior") {
        val (b, game, counter) = base.startWithBoard { _, human, _ ->
            base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        // action + assertions
    }
})
```

## Style

- **No silent skips.** `if (list.isEmpty()) return@test` hides broken setups. If the board should produce cast actions, assert `castActions.shouldNotBeEmpty()`. A test that can't fail isn't a test.
- **`assertSoftly` for multi-field shape checks.** When one setup produces one GSM and you check N facets, wrap in `assertSoftly {}` — reports all failures, not just the first. Hard gates (e.g. "annotations exist at all") go before the `assertSoftly` block.
- **One test per distinct board setup.** Don't mix Activate checks (needs Gingerbrute) into a Cast test (needs creature + mana). Different board = different test.
- **Category assertions mandatory** on zone transfer tests. `zt.shouldNotBeNull()` alone is lax — always check `zt.category shouldBe "..."`.

## Assertions & helpers

Prefer concise helpers from `TestExtensions.kt` over verbose manual patterns.

### Annotation lookup

```kotlin
// Good: throws with clear message if missing
val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)

// Avoid inside assertSoftly — the "soft" null check is illusory
// since downstream lines depend on the value being non-null
val zt = gsm.annotationOrNull(AnnotationType.ZoneTransfer_af5a).shouldNotBeNull()

// annotationOrNull is for genuinely optional annotations (e.g. "if present, check shape")
```

### Detail extraction

```kotlin
// Good: one line, fails clearly if key missing
zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
zt.detailString("category") shouldBe "PlayLand"

// Avoid: verbose, redundant type check
val zoneSrc = zt.detail("zone_src").shouldNotBeNull()
zoneSrc.type shouldBe KeyValuePairValueType.Int32
zoneSrc.getValueInt32(0) shouldBe ZoneIds.P1_HAND
```

Available: `detailInt()`, `detailUint()`, `detailString()`, `detail()` (raw nullable).

### Zone transfer

```kotlin
val zt = checkNotNull(gsm.findZoneTransfer(instanceId)) { "Should have ZoneTransfer" }
zt.category shouldBe "PlayLand"
```

### Nullability

- **Assert-then-use**: `val x = expr.shouldNotBeNull()` (returns non-null — no `!!` needed).
- **Hard fail**: `checkNotNull(x) { "msg" }` or `val x = expr ?: error("msg")`.
- Never `assertNotNull(x); x!!.foo()` — the `!!` is redundant noise.

## Harnesses

### ConformanceTestBase

Board-level and bridge-level tests. Key methods:
- `startWithBoard { game, human, ai -> }` — synchronous, no threads
- `startGameAtMain1()` — full game boot, returns `(bridge, game, counter)`
- `addCard(name, player, zone)` — place card in zone
- `captureAfterAction(b, game, counter) { action() }` — snapshot → action → diff GSM

### MatchFlowHarness

Full MatchSession integration. Zero reimplemented logic — exercises production code paths.
- `connectAndKeep()` / `connectAndKeepPuzzleText(pzl)` — full game + mulligan
- `passPriority()` — through MatchSession (triggers AutoPassEngine)
- `passThroughCombat(startTurn)` — pass until turn advances or game ends
- `advanceToPhase(phase, turn?)` / `advanceToCombat()` / `advanceToMain1()` — bridge-level, one pass at a time, no overshoot

### Phase advancement

Two approaches, pick the right one:

| Method | Goes through | Use when |
|---|---|---|
| `harness.passPriority()` | MatchSession → AutoPassEngine | Testing production auto-pass, message generation |
| `harness.advanceToPhase("MAIN1")` | Bridge directly | Deterministic setup, no overshoot needed |

`advanceTo*` helpers bypass AutoPassEngine — one PassPriority at a time via the bridge. Use for reliable phase targeting in setup. Use `passPriority()` when you need the production message pipeline.

All `just test-*` targets print a `=== FAILED TESTS ===` summary on failure.
