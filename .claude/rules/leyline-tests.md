---
paths:
  - "engine/src/test/**"
  - "native/src/test/**"
  - "web/src/test/**"
  - "just/test.just"
---

# Tests

All tests use **Kotest FunSpec** (JUnit Platform). One test per behavior, at the fastest setup tier that covers it.

## Running tests

Scope to the modules you changed. Don't run all modules when you touched one.

| Changed | Command |
|---|---|
| `engine/` (safe change) | `./gradlew :engine:testGate` |
| `engine/` (risky: StateMapper, bridges, combat, annotations) | `./gradlew :engine:testGate :engine:testIntegration` |
| `native/` | `./gradlew :native:test` |
| `web/` | `./gradlew :web:test` |
| Single class | `just test-one ClassName` (defaults to `engine`) |
| Single class in another module | `just test-one ClassName web` |
| Several classes, one run | `just test-many "ClassA ClassB"` (space-separated, one string; defaults to `engine`) |
| Single class + stdout | `just test-debug ClassName` (defaults to `engine`, accepts the same optional module arg) |
| Pre-commit (all modules + fmt) | `just test-gate` |

**Test-only changes** (amended assertion, new test case — no prod code touched): `just test-one Foo` is sufficient for engine. Use `just test-one Foo native` or `just test-one Foo web` for other modules.

**Debugging test output:** `just test-debug` enables `-Pverbose` which passes `showStandardStreams` to Gradle. `println` from test code becomes visible. Logback root stays WARN — no engine noise.

**Mechanism, and a warning about the failure mode this replaced:** `just test-one`/`test-debug`/`test-many` resolve the target class's fully-qualified name from its source file (`gradle/scripts/resolve-test-fqcn.sh`), then pass it to `--tests` with **no wildcard** (`--tests "leyline.architecture.PackageLayeringTest"`, not `--tests "*PackageLayeringTest"`). This project's Gradle 8.12 + Kotest 5.9.1 combo silently drops `--tests "*ClassName"` results for kotest specs: Kotest actually runs and passes the class's tests internally (confirmed with `KOTEST_DEBUG=TRUE`), but Gradle's own JUnit Platform bookkeeping never picks them up, so the build reported `PASS 0/0` / `No tests found for given includes` for every class, including long-existing ones. A fully-qualified `--tests` pattern is the one shape proven to work, and it still fails loud (non-zero exit) when nothing matches — verified both for a nonexistent class and for a real class whose tests are all excluded by `kotest.tags`. `test-many` gets OR semantics for free by passing `--tests` multiple times in one invocation (Gradle's native behavior), each with a resolved fully-qualified name.

**Do not use `kotest.filter.specs` for more than one class.** The single-pattern form (`-Pkotest.filter.specs='*ClassName'`, leading wildcard required) works and is a valid manual escape hatch, but Kotest 5.9.1's `SystemPropertySpecFilterInterceptor` ANDs comma- or semicolon-separated patterns instead of ORing them, so a real multi-class list matches zero specs — and unlike `--tests`, it does this **silently**: `BUILD SUCCESSFUL`, `PASS 0/0`, exit code 0. This is a Kotest-version limitation, not something fixable from this repo; `just test-many` sidesteps it entirely by using repeated `--tests` flags instead.

## Stress-testing flakes

Use this loop when a recent PR or `main` merge has intermittent failures:

1. Review recent CI failures and reruns first. Pick likely suspects from repeated class names, failed task shape, and whether the failure was gate or integration.
2. Refresh the worktree to latest `main`, update submodules, and run `just install-forge` if the Forge gitlink changed. Do not count setup repair as a stress pass.
3. Local stress iterations use only `./gradlew --rerun-tasks :engine:testGate :engine:testIntegration`. Run 3-5 forced passes max, and stop on the first failure.
4. After the first repro, switch to targeted repeats: `./gradlew --rerun-tasks :engine:testIntegration --tests "fully.qualified.ClassName"` (fully qualified, no wildcard — see "Running tests" above for why). If the targeted class passes but the full graph flakes, suspect task/fork/shared-state interaction before changing game code.
5. Fix the smallest concrete cause, then scan sibling helpers and tests for the same smell before opening the PR.

When the fix touches test helpers, prefer tightening the helper contract over adding another wrapper. PR #136 is the pattern: a helper returned a generic nullable action, callers re-selected mutable zone state, and identity drift hid in the call site. Returning the concrete action type made the submitted identity explicit. After that kind of fix, look for sibling helpers with overly broad return types or optionality that lets callers reconstruct state instead of using the value already produced.

## Tags

**Every test class MUST have a tag.** First line inside `FunSpec({` body (or auto-wired by `BoardTest`).

| Module | Tag | Notes |
|---|---|---|
| `engine` | `UnitTag` / `BoardTag` / `IntegrationTag` | Import from `leyline.{UnitTag,BoardTag,IntegrationTag}` |
| `native` | `NativeTag` | Import from `leyline.native.NativeTag` |
| `web` | route/auth tests use module-local tags as needed | Unit-level |

`testGate` = Unit + Board. `testIntegration` = Integration only.

## Setup tiers (engine)

| Tier | Method | Time | Use when |
|---|---|---|---|
| Board | `startWithBoard` + `capture()` | 0.01s | **Default.** Zone transitions, annotations, action fields, state mapping |
| Puzzle | `startPuzzleAtMain1(pzl)` | 0.09s | SBA scenarios, complex board states needing proper game start |
| Bridge | `startGameAtMain1()` | 0.5s | Cast/resolve pipeline through bridge (needs engine thread) |
| Session | `connectAndKeep()` (MatchFlowHarness) | 0.7-3s | Full MatchSession — auto-pass, combat, targeting, game-over |

**Bias toward Board.** If the test doesn't call `passPriority()` or need the game loop thread, it belongs at board level.

**Board and Bridge use BoardTest. Session uses MatchFlowHarness.** Never mix bases in one file — different speed tiers, different base classes, separate files.

### Playing cards in tests

See `forge-seams.md` for full details. Quick reference:
- `addCard("Forest", human, ZoneType.Battlefield)` — places card during `startWithBoard` setup. No zone change. **For board setup.**
- `moveToBattlefield(card, game)` — raw zone move during test. No events, no triggers. **For moving cards as setup after startWithBoard.**
- `player.playLand(land, true, null)` — full Forge path. Fires events, consumes land drop. **For testing the land play itself.**

## Test class shape

**New tests: extend BoardTest** (auto-wires tags, initCardDatabase, tearDown):

```kotlin
class FooTest : BoardTest({

    test("some behavior") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Grizzly Bears", human, ZoneType.Battlefield)
        }
        // action + assertions
    }
})
```

Existing tests using `val base = BoardTestBase()` pattern still work, but `BoardTestBase` is a legacy implementation helper. Do not start new files with it. Migrate touched board-tier tests to `BoardTest` unless a specific legacy setup seam blocks the conversion.

## Style

- **MUST: Assert what the test name claims.** "Keep on top" must check the card is on top — not just "not in graveyard." A test that can't fail for the right reason isn't testing anything. If the test name says X, the assertion must prove X directly.
- **MUST: Assert the specific outcome, not its side effects.** Check the card by name and position, not `shouldNotBeEmpty()` or `.any {}`. You control the board — you know exactly what should be where. `gy.none { it.name == "Foo" }` is a side-effect check; `libTop.name shouldBe "Foo"` is a direct outcome check.
- **No silent skips.** `if (list.isEmpty()) return@test` hides broken setups. A test that can't fail isn't a test.
- **Exact counts, not weak gates.** `shouldHaveSize(2)` not `shouldNotBeEmpty()`. You control the board — you know exactly how many actions/annotations to expect. "Exact" means **derivable from your setup** — if you can't trace the expected value back to the board you built, keep the weaker assertion and comment why.
- **Named constants.** `ActionType.Play_add3.number` not `3`, `SEAT_ID` not `1`, `ZoneIds.STACK` not magic numbers.
- **`assertSoftly` for multi-field shape checks.** Hard gates (annotation exists at all) go before the `assertSoftly` block.
- **One test per distinct board setup.** Different board = different test.
- **One test file per class under test.** During staged migrations (per-field dual-write + cut-over pairs, per-phase lint fixes) it's tempting to spin up `FooLegendTest`, `FooSearchTest`, `FooRevealTest` — one per step. Don't. If the class under test is the same, it's one file. Extend the existing test rather than fragmenting. Consolidate before the PR lands; reviewers should flag this pattern.
- **Don't write pin tests that duplicate a downstream helper's public API.** A test that seeds `bridge.journal.record(...)` and asserts `bridge.journal.consumeX(...) shouldBe true` is testing the helper's contract, not the consumer that was supposed to drain it. Those tests read like coverage theatre. If you can't drive the real consumer (e.g., needs a full `GameBridge` + engine), say so — rely on board/session coverage, and don't invent a fake assertion at the seam.
- **Category assertions mandatory** on zone transfer tests. `zt.shouldNotBeNull()` alone is lax — always check `zt.category shouldBe "..."`.
- **Bail-out loops need terminal assertions.** Always assert the condition after the loop, or use `passUntil` / `passThroughCombat` which fail on exhaustion.
- **Use helpers, not raw proto access.** Check `TestExtensions.kt` (assertions) and `ProtoDsl.kt` (proto builders — actions, mana, GRE messages, stops) before writing inline builders. If a pattern appears 2+ times and no helper exists, add one to the appropriate file.
- **Prefer domain matchers over structural assertions** for end-state checks. `"Grizzly Bears" should beInHandOf(human)` reads like MTG and fails with a self-describing message. Matcher-only tests are valid when the matcher message names the domain object and observed state; add matcher tests for positive, negative, and failure-message cases. Matchers live alongside `TestExtensions.kt` (e.g. `ZoneMatchers.kt`).
- **Tests should read like specs.** Extract helpers that name the intent.
- **No `when` with `else -> {}`** — silently ignores unknown variants. Filter by type explicitly.
- **No tautological assertions.** `uint >= 0` is always true. Use `shouldBeGreaterThan 0` if value must be positive.
- **No fully qualified Forge/proto types inline** — import them.
- **Wrap Forge actions that take boilerplate params.** `destroy(card, game)` not `game.action.destroy(card, null, false, AbilityKey.newMap())`. BoardTest provides `destroy()`, `exile()`, `moveToBattlefield()`. If you need a new one, add it there.
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

### Zone transfer helper (BoardTest)

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

### BoardTest (preferred)

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
