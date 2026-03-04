# Kotest Migration

Migrate all tests from TestNG to Kotest FunSpec + kotest-assertions-core.

## Status — COMPLETE

- [x] Deps added: `kotest-runner-junit5`, `kotest-assertions-core`, `kotest-framework-datatest` (5.9.1)
- [x] All unit-group tests migrated (21 files)
- [x] All conformance-group tests migrated (25 files)
- [x] All integration-group tests migrated (14 files)
- [x] TestNG dependency removed
- [x] `useTestNG()` → `useJUnitPlatform()` in `configureTestDefaults()`
- [x] Standalone `testKotest` task removed — `test` task handles everything
- [x] Kotest tags (`UnitTag`, `ConformanceTag`, `IntegrationTag`) defined and applied to all specs
- [x] `testUnit`/`testConformance`/`testIntegration`/`testGate` use `kotest.tags` system property
- [x] `PackageArchitectureTest` fixed — scans `build/classes` instead of `target/classes`
- [x] CLAUDE.md and `.claude/rules/nexus-tests.md` updated

## Approach

Migrated file-by-file in three waves (unit → conformance → integration). Post-migration cleanup removed TestNG and consolidated all tasks on JUnit Platform with Kotest tag filtering.

## Test shape mapping

### Class structure

```kotlin
// TestNG
@Test(groups = ["unit"])
class FooTest {
    @Test fun bar() { ... }
}

// Kotest
class FooTest : FunSpec({
    test("bar") { ... }
})
```

### Lifecycle

```kotlin
// TestNG
private lateinit var harness: MatchFlowHarness
@BeforeClass fun setup() { CardDb.init() }
@AfterMethod(alwaysRun = true) fun tearDown() { if (::harness.isInitialized) harness.shutdown() }

// Kotest
lateinit var harness: MatchFlowHarness
beforeSpec { CardDb.init() }
afterEach { if (::harness.isInitialized) harness.shutdown() }
```

### DataProvider → withData

```kotlin
// TestNG
@DataProvider fun cases(): Array<Array<Any>> = arrayOf(arrayOf(StopType.Upkeep, PhaseType.UPKEEP))
@Test(dataProvider = "cases") fun map(s: StopType, p: PhaseType) { assertEquals(...) }

// Kotest
withData(
    StopType.Upkeep to PhaseType.UPKEEP,
) { (stop, phase) -> StopTypeMapping.toPhaseType(stop) shouldBe phase }
```

### ConformanceTestBase

Can't inherit in `FunSpec({})`. Extract to a helper object and call methods directly:

```kotlin
class ZoneTransitionTest : FunSpec({
    val base = ConformanceHelper()
    beforeSpec { base.initCardDb() }
    test("play land") {
        val (b, game, _) = base.startWithBoard { ... }
    }
})
```

This is the biggest refactor — touch `ConformanceTestBase` last, after simpler files build confidence.

## Assertion cheatsheet

| TestNG | Kotest |
|--------|--------|
| `assertEquals(actual, expected)` | `actual shouldBe expected` |
| `assertTrue(cond)` | `cond.shouldBeTrue()` or `cond shouldBe true` |
| `assertFalse(cond)` | `cond.shouldBeFalse()` |
| `assertNull(x)` | `x.shouldBeNull()` |
| `assertNotNull(x)` | `x.shouldNotBeNull()` |
| `assertNotEquals(a, b)` | `a shouldNotBe b` |
| `assertEquals(list.size, N)` | `list shouldHaveSize N` |
| `assertTrue(list.isNotEmpty())` | `list.shouldNotBeEmpty()` |
| `assertTrue(x in collection)` | `collection shouldContain x` |
| `assertTrue(list.any { pred })` | `list.shouldExist { pred }` |
| `assertTrue(list.all { pred })` | `list.shouldForAll { pred }` |
| `assertTrue(list.none { pred })` | `list.shouldForNone { pred }` |
| `assertTrue(x > y)` | `x shouldBeGreaterThan y` |
| `assertTrue(x >= y)` | `x shouldBeGreaterThanOrEqual y` |
| `assertTrue(x in 1..20)` | `x shouldBeInRange 1..20` |
| `assertTrue("foo" in str)` | `str shouldContain "foo"` |
| `try { x; fail() } catch (e) { assertTrue("msg" in e.message!!) }` | `shouldThrow<E> { x }.message shouldContain "msg"` |

### Keep as-is

- `checkNotNull(expr) { "msg" }` — idiomatic Kotlin, not a TestNG assertion
- `?: error("msg")` — same, keep
- `?: throw AssertionError("msg")` — consider `shouldNotBeNull()` if message isn't critical

### Custom helpers (GameAssertions.kt, TestExtensions.kt)

Rewrite internals to kotest assertions, keep the same public API. Do this early — all conformance/integration tests depend on them.

## Groups → Tags

TestNG groups become Kotest tags. Define tag objects:

```kotlin
object Unit : io.kotest.core.Tag()
object Conformance : io.kotest.core.Tag()
object Integration : io.kotest.core.Tag()
```

Apply per-spec: `tags(Unit)` in the FunSpec body. Gradle filters via system property or Kotest config.

## Known failing tests

Some combat/blocker integration tests are currently failing (pre-existing, not migration-related). During migration:

1. Attempt a clean rewrite to kotest
2. Run the test — if it fails consistently with the same error as under TestNG, `xtest` it and add a `// TODO: fix <description of failure>` comment
3. Do not spend time debugging game mechanics issues — the goal is migration, not fixing combat logic

These tests are in the `integration` group — expect them in `CombatFlowTest`, `BlockerDeclarationTest`, and similar harness-based files.

## Migration order

1. **Custom assertion helpers** — `TestExtensions.kt`, `GameAssertions.kt` (swap internals to kotest assertions)
2. **Small unit tests** — `GamePlaybackTest`, `InferCategoryTest`, `MatchRegistryTest`, etc.
3. **DataProvider tests** — `StopTypeMappingTest` and similar table-driven tests
4. **Conformance tests** — refactor `ConformanceTestBase` into helper, then migrate subclasses
5. **Integration tests** — `MatchFlowHarness`-based tests, Ktor server tests
6. **ArchUnit** — already done

## Post-migration cleanup

Once every test file is converted:

1. Remove `testng` from `libs.versions.toml` (version + library)
2. Remove `testImplementation(libs.testng)` from `build.gradle.kts`
3. Change `configureTestDefaults()` from `useTestNG()` to `useJUnitPlatform()`
4. Remove group-based `useTestNG { includeGroups(...) }` — replace with kotest tag filters
5. Delete the standalone `testKotest` task — main `test` task handles everything
6. Update `CLAUDE.md` and `.claude/rules/nexus-tests.md` to reference kotest conventions

## Imports to add

```kotlin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.*
import io.kotest.matchers.comparables.*
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.datatest.withData  // only for data-driven tests
```
