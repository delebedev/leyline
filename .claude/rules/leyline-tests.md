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

**Every test class MUST have a tag.** First line inside `FunSpec({` body (or auto-wired by `SubsystemTest`).

| Module | Tag | Notes |
|---|---|---|
| `matchdoor` | `UnitTag` / `ConformanceTag` / `IntegrationTag` | Import from `leyline.{UnitTag,ConformanceTag,IntegrationTag}` |
| `frontdoor` | `FdTag` | All tests are unit-level |
| `account` | `UnitTag` | Import from `leyline.account.UnitTag` |
| `tooling` | `UnitTag` | Import from `leyline.UnitTag` (shared with matchdoor) |

`testGate` = Unit + Conformance. `testIntegration` = Integration only.

## Setup tiers (matchdoor)

| Tier | Method | Time | Use when |
|---|---|---|---|
| Board | `startWithBoard` + `capture()` | 0.01s | **Default.** Zone transitions, annotations, action fields, state mapping |
| Puzzle | `startPuzzleAtMain1(pzl)` | 0.09s | SBA scenarios, complex board states needing proper game start |
| Bridge | `startGameAtMain1()` | 0.5s | Cast/resolve pipeline through bridge (needs engine thread) |
| Session | `connectAndKeep()` (MatchFlowHarness) | 0.7-3s | Full MatchSession — auto-pass, combat, targeting, game-over |

**Bias toward Board.** If the test doesn't call `passPriority()` or need the game loop thread, it belongs at board level.

**Board and Bridge use SubsystemTest. Session uses MatchFlowHarness.** Never mix bases in one file — different speed tiers, different base classes, separate files.

### Playing cards in tests

See `forge-seams.md` for full details. Quick reference:
- `addCard("Forest", human, ZoneType.Battlefield)` — places card during `startWithBoard` setup. No zone change. **For board setup.**
- `moveToBattlefield(card, game)` — raw zone move during test. No events, no triggers. **For moving cards as setup after startWithBoard.**
- `player.playLand(land, true, null)` — full Forge path. Fires events, consumes land drop. **For testing the land play itself.**

## Test class shape

**New tests: extend SubsystemTest** (auto-wires tags, initCardDatabase, tearDown):

```kotlin
class FooTest : SubsystemTest({

    test("some behavior") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        // action + assertions
    }
})
```

Existing tests using `val base = ConformanceTestBase()` pattern still work — migrate to SubsystemTest when touching the file.

## Style

- **No silent skips.** `if (list.isEmpty()) return@test` hides broken setups. A test that can't fail isn't a test.
- **Exact counts, not weak gates.** `shouldHaveSize(2)` not `shouldNotBeEmpty()`. You control the board — you know exactly how many actions/annotations to expect. "Exact" means **derivable from your setup** — if you can't trace the expected value back to the board you built, keep the weaker assertion and comment why.
- **Named constants.** `ActionType.Play_add3.number` not `3`, `SEAT_ID` not `1`, `ZoneIds.STACK` not magic numbers.
- **`assertSoftly` for multi-field shape checks.** Hard gates (annotation exists at all) go before the `assertSoftly` block.
- **One test per distinct board setup.** Different board = different test.
- **Category assertions mandatory** on zone transfer tests. `zt.shouldNotBeNull()` alone is lax — always check `zt.category shouldBe "..."`.
- **Bail-out loops need terminal assertions.** Always assert the condition after the loop, or use `passUntil` / `passThroughCombat` which fail on exhaustion.
- **Use helpers, not raw proto access.** Check `TestExtensions.kt` before writing inline lookups. If a pattern appears 2+ times and no helper exists, propose one.
- **Tests should read like specs.** Extract helpers that name the intent.
- **No `when` with `else -> {}`** — silently ignores unknown variants. Filter by type explicitly.
- **No tautological assertions.** `uint >= 0` is always true. Use `shouldBeGreaterThan 0` if value must be positive.
- **No fully qualified Forge/proto types inline** — import them.
- **Wrap Forge actions that take boilerplate params.** `destroy(card, game)` not `game.action.destroy(card, null, false, AbilityKey.newMap())`. SubsystemTest provides `destroy()`, `exile()`, `moveToBattlefield()`. If you need a new one, add it there.
- **`(a < b).shouldBeTrue()` gives bad failure messages** ("expected true but was false"). Prefer `shouldBe listOf(...)` for type ordering. For non-consecutive ordering, `(a < b)` is acceptable but add a comment.

## Assertions & helpers

Prefer concise helpers from `TestExtensions.kt` over verbose manual patterns.

### Annotation lookup

```kotlin
// Good: throws with clear message ("No annotation of type ZoneTransfer")
val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)

// Bad: annotationOrNull + shouldNotBeNull gives opaque "expected non-null but was null"
val zt = gsm.annotationOrNull(AnnotationType.ZoneTransfer_af5a).shouldNotBeNull()

// Same applies to persistent annotations:
val cp = gsm.persistentAnnotation(AnnotationType.ColorProduction) // Good
val cp = gsm.persistentAnnotationOrNull(AnnotationType.ColorProduction).shouldNotBeNull() // Bad

// annotationOrNull is ONLY for genuinely optional annotations (e.g. "if present, check shape")

// Plural: all annotations of a type
val tups = gsm.annotations(AnnotationType.TappedUntappedPermanent)
tups.shouldHaveSize(1)
```

### Detail extraction

```kotlin
// Good: one line, fails clearly if key missing
zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
zt.detailString("category") shouldBe "PlayLand"
zt.detailIntList("colors") shouldBe listOf(5) // multi-value

// Avoid: verbose, redundant type check
val zoneSrc = zt.detail("zone_src").shouldNotBeNull()
zoneSrc.type shouldBe KeyValuePairValueType.Int32
zoneSrc.getValueInt32(0) shouldBe ZoneIds.P1_HAND
```

Available: `detailInt()`, `detailUint()`, `detailString()`, `detailIntList()`, `detail()` (raw nullable).

### Action filtering

```kotlin
val cast = actions.ofType(ActionType.Cast)
cast.shouldHaveSize(1)
```

### Zone transfer

```kotlin
val zt = checkNotNull(gsm.findZoneTransfer(instanceId)) { "Should have ZoneTransfer" }
zt.category shouldBe "PlayLand"
gsm.hasEnteredZoneThisTurn(instanceId).shouldBeTrue()
```

### InstanceId resolution

```kotlin
// Good: absorbs ForgeCardId wrapping + .value unwrapping
val newId = b.instanceId(card.id)

// Avoid: noisy three-step
val newId = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
```

### Zone transfer helper (SubsystemTest)

```kotlin
// transferCard: finds card by name, performs action, returns (gsm, newInstanceId)
val (gsm, newId) = transferCard(b, game, counter, "Grizzly Bears") { card, g ->
    destroy(card, g)
}
checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Destroy"
```

### Nullability

- **Assert-then-use**: `val x = expr.shouldNotBeNull()` (returns non-null — no `!!` needed).
- **Hard fail**: `checkNotNull(x) { "msg" }` or `val x = expr ?: error("msg")`.
- Never `assertNotNull(x); x!!.foo()` — the `!!` is redundant noise.

## Harnesses

### SubsystemTest (preferred)

Board-level and bridge-level tests. Extends FunSpec, auto-wires tags/setup/teardown. Key methods:
- `startWithBoard { game, human, ai -> }` — synchronous, no threads
- `startGameAtMain1()` — full game boot, returns `(bridge, game, counter)`
- `addCard(name, player, zone)` — place card in zone
- `capture(b, game, counter) { action() }` — snapshot → action → diff GSM
- `moveToBattlefield(card, game)` — raw zone move (no events)
- `playLandFromHand(b, game, counter)` — full land play, returns GSM
- `humanPlayer(b)` — human player shortcut

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
