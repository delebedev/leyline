# Test Consolidation Guidance

How to consolidate scattered test files into subsystem test files. Written from two successful consolidations (LandManaTest, StackCastResolveTest) and their agent reflections.

## Goal

Organize tests by **subsystem** (what they test) not by **cross-cutting concern** (annotation ordering, zone transitions, instanceId lifecycle). When a bug appears in a subsystem, all tests for it are in one file.

## File structure

Extend `SubsystemTest` — no `val base =` boilerplate:

```kotlin
class FooTest : SubsystemTest({

    test("behavior under test") {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Card Name", human, ZoneType.Hand)
        }
        // action + assertions
    }
})
```

SubsystemTest auto-wires `initCardDatabase`/`tearDown`, provides `capture()`, `humanPlayer()`, `moveToBattlefield()`, `playLandFromHand()`, `startWithBoard()`, `startGameAtMain1()`, `addCard()`, etc.

## Harness tier selection

Use the lightest tier that covers the behavior:

| Tier | Method | Time | Use when |
|---|---|---|---|
| Board | `startWithBoard` + `capture()` | 0.01s | Zone transitions, annotations, action fields, state mapping — anything that doesn't need the game loop thread |
| Puzzle | `startPuzzleAtMain1(pzl)` | 0.09s | SBA scenarios, complex board states needing proper game start |
| Bridge | `startGameAtMain1()` | 0.5s | Cast/resolve pipeline through bridge, needs engine thread |
| Session | MatchFlowHarness | 0.7-3s | Full MatchSession — auto-pass, combat, targeting prompts, game-over |

**Board and Bridge tiers use SubsystemTest.** Session tier uses MatchFlowHarness (different base — separate file per our "no mixed bases" rule).

### Playing cards in tests

- `addCard("Forest", human, ZoneType.Battlefield)` — places card during `startWithBoard` setup. No zone change. **For board setup.**
- `moveToBattlefield(card, game)` — raw zone move during test. No events, no triggers. **For moving cards as setup after startWithBoard.**
- `player.playLand(land, true, null)` — full Forge land-play path with events. **For testing the land play itself.**

## Assertion quality

- **Exact counts** (`shouldHaveSize(2)`) not weak gates (`shouldNotBeEmpty()`). You control the board — you know exactly how many actions/annotations to expect.
- **Named constants**: `ActionType.Play_add3.number` not `3`, `SEAT_ID` not `1`, `ZoneIds.STACK` not magic numbers.
- **No silent skips**: no `if (list.isEmpty()) return@test`. If the board should produce annotations, assert they exist.
- **`assertSoftly`** for multi-field shape checks within one annotation. Hard gates (annotation exists at all) go BEFORE assertSoftly.
- **No `when` with `else -> {}`** — silently ignores unknown variants. Filter by type explicitly.
- **Stricter assertions can shift failure surfaces.** When upgrading `isNotEmpty()` to `shouldHaveSize(N)`, the test may now fail at a different point than before. This is usually an improvement but note it in the commit.

## Using TestExtensions helpers

**Always check TestExtensions.kt first.** These exist:
- `gsm.annotation(type)` / `gsm.annotationOrNull(type)` — transient annotation lookup
- `gsm.persistentAnnotation(type)` / `gsm.persistentAnnotationOrNull(type)` — persistent annotation lookup
- `ann.detailInt(key)`, `ann.detailUint(key)`, `ann.detailString(key)` — single-value detail
- `ann.detailIntList(key)` — multi-value int detail (e.g. colors=[3, 5])
- `actions.ofType(type)` — filter actions by ActionType
- `gsm.findZoneTransfer(instanceId)` — ZoneTransferInfo with category/zones
- `assertLimboContains(gsm, instanceId)` — Limbo zone assertion

**If a pattern appears 3+ times across test files, add it to TestExtensions.** Already added:
- `gsm.annotations(type)` (plural) — all annotations of a type, not just first

Still candidates:
- `hasEnteredZoneThisTurn(gsm, instanceId)` — persistent annotation check

**If a pattern appears 2+ times within one file, extract a local helper.** Name it after the intent.

## Style

- No `base.` prefix — SubsystemTest provides everything directly
- No Forge internals in test code — `moveToBattlefield(card, game)` not `game.action.moveToPlay(card, null, AbilityKey.newMap())`
- No raw proto traversal — `detailInt("foo")` not `.detailsList.first { it.key == "foo" }.getValueInt32(0)`
- No fully qualified Forge/proto types inline — import them
- Tests read like specs — if noisy, extract a helper

## Test merging — when NOT to merge

When consolidating, check if tests are truly independent or redundant:

- **Merge** if one test is a strict subset of another (e.g., "TUP before ManaPaid" is a subset of "mana bracket ordering AIC < TUP < ManaPaid < AID")
- **Merge** if tests share the exact same setup and assert different facets of the same GSM — use `assertSoftly` for the combined assertions
- **Don't merge** if the tests need different setup (e.g., `resolveAndCapture()` vs manual `startGameAtMain1` flow) — different snapshot timing can cause failures. Each `postAction`/`snapshotFromGame` call advances bridge state; tests that pass individually can break when merged if they depend on specific counter/baseline state.
- **Don't merge** if one test checks annotation existence and another checks instanceId values — these can fail independently and the failure message matters
- **Don't assume consecutive annotation indices.** `aicIdx shouldBe (tupIdx - 1)` fails if other annotations interleave. Use `<` comparison for ordering.
- **`(a < b).shouldBeTrue()`** gives terrible failure messages ("expected true but was false"). When possible, assert the type list directly with `shouldBe listOf(...)`. When ordering between non-consecutive items, `(a < b).shouldBeTrue()` is acceptable but add a comment explaining what's being checked.
- **`uint >= 0` is always true** — don't assert tautologies. Use `shouldBeGreaterThan 0` or `shouldNotBe 0` if the value must be positive.
- **`persistentAnnotation(type)` may return a different card's annotation.** When multiple cards have the same persistent annotation type (e.g., EnteredZoneThisTurn for both a land and a creature), filter by `affectedIdsList` to find the right one.
- **`annotationOrNull` + `shouldNotBeNull()` is an anti-pattern** when you expect the annotation to exist. Use `annotation(type)` directly — it fails with a clear message ("No annotation of type X") vs the opaque "expected non-null but was null". Reserve `annotationOrNull` for genuinely optional annotations (e.g., "if present, check shape").

## What moves, what stays — decision rules

The consolidation author (human) decides what moves. The implementing agent gets an explicit list. Rules of thumb:

- **Subsystem tests move** — if a test exercises the subsystem's pipeline (e.g., cast→stack zone transfer), it belongs in the subsystem file
- **Cross-cutting tests stay** — if a test exercises multiple subsystems in one test (e.g., "annotation IDs sequential across PlayLand + CastSpell + Resolve"), it stays in the cross-cutting file
- **Pre-existing failures migrate with their tests** — note them with TODO comments. The failure now lives in the new file, not the old one.
- **Duplicate tests at wrong tier get replaced** — if a MatchFlowHarness test duplicates what a board-level test already covers, delete the heavier one.
- **Redundant tests surface during consolidation.** When tests from different files land next to each other, duplicates become obvious (e.g., two files testing "Destroy" category with different assertion depth). Consolidation is a natural dedup pass — take the stronger version, delete the weaker.

## After implementation

1. Run the new test file — all pass
2. Run each trimmed source file — remaining tests pass
3. Leave comment breadcrumbs in trimmed files ("moved to FooTest")
4. `trash` fully absorbed files
5. Add any new helpers to TestExtensions (with the change, not in a follow-up)
6. Run `just fmt` before committing

## Agent reflection (required)

After finishing, report:
1. What was unclear or missing from this guidance?
2. Which TestExtensions helpers were used vs missing? Add missing ones.
3. Any tests that didn't fit the subsystem model cleanly?
4. Suggested improvements to SubsystemTest or TestExtensions.
5. Tests that should NOT have been moved? Why?
