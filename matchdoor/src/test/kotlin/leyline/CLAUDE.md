# matchdoor tests

Conventions for writing tests under `matchdoor/src/test/kotlin/leyline/`. Read `matchdoor/CLAUDE.md` for the production-side architecture; this file covers the test-side decisions.

## Test tier — pick the right base class

Three tiers, each with a base class. **Never mix in one file.** A detekt rule (`TierPlacementCheck`) flags Session-tier tests that don't drive the game loop.

| Tier | Base | When | Cost |
|---|---|---|---|
| Board | `BoardTest` (in `testkit/`) | Test the bridge or annotation pipeline directly. `bundleBuilder(b).buildActions()`, `StateMapper.buildFromGame()`, `AnnotationBuilder` calls. | <0.1s/test |
| Session | `SessionTest` (in `testkit/`) | Test that requires driving the priority loop — `passUntil`, `selectTargets`, `declareAttackers`, `respondToOptionalCost`. The real `MatchSession` + Forge engine. | 0.7–3s/test |
| Pure unit | bare `FunSpec` | Pure-data logic. No engine, no harness. | <10ms/test |

If a Session-tier test never calls a driver (`passPriority`, `passUntil`, `advanceTo*`, `onPerformAction`, `respondTo*`), move it to Board tier — same signal, much cheaper. Suppress `TierPlacementCheck` only with a comment explaining why the loop is essential to the assertion (e.g. `DrawUpdateTypeShapeTest` needs a real turn-boundary draw event from the engine's EventBus).

Every Spec subclass must call `tags(UnitTag | BoardTag | IntegrationTag)`. `FunSpecMissingTags` enforces it. `SessionTest` and `BoardTest` auto-tag — only standalone `FunSpec` classes need the explicit call.

## Layout — where test files live

```
board/<domain>/       Board-tier tests: bridge, mapper, bundle, annotations.
session/<domain>/     Session-tier tests: MatchSession + engine loop behavior.
mechanics/<keyword>/  Keyword/mechanic suites split action-vs-lifecycle.
game/                 Pure game pipeline and mapper tests near production package.
testkit/              Shared bases, harnesses, matchers, proto DSL, fixtures.
conformance/          Temporary review bucket for mixed, card-specific, or still-ambiguous files.
```

Pick the lane first, then the domain. Do not put Board and Session tests in
the same file. Card-specific tests stay out of generic mechanic/domain folders
until we can explain the card-specific invariant they cover.

## Helpers — where things live

```
testkit/
├── ZoneMatchers.kt           kotest Matcher<String> for zone membership ("X" should beInHandOf(p))
├── ActionMatchers.kt         kotest matchers for Action / ActionsAvailableReq (alt-cost offers, etc.)
├── TestExtensions.kt         non-matcher extensions: AnnotationInfo.detail*(), GSM.annotation(type), ActionsAvailableReq.ofType()
├── MessageWalk.kt            List<GREToClientMessage> walkers: allAnnotations(), firstGameObjectByIid(), etc.
├── ProtoDsl.kt               builder DSL for client→GRE messages (performAction { ... })
├── BoardTestBase.kt          Board-tier setup (initCardDatabase, addCard, startWithBoard)
├── BoardTest.kt              base class — wires BoardTestBase
├── SessionTest.kt            base class — wires MatchFlowHarness, exposes selectTargets/passUntil/instanceIdOf
├── MatchFlowHarness.kt       Session-tier harness — owns the game thread, message stream, scripted AI
├── ScriptedPlayerController.kt
├── ClientAccumulator.kt      replays GSMs against a parallel game-state model — invariant checker
└── ValidatingMessageSink.kt  per-message GSM/GRE structural invariants — enable with validating=true
```

**Picking a layer when you reach for a helper:**

- Need to *find a card iid* in a Session-tier test? Use `instanceIdOf(name, player, zone)` from `SessionTest`. Don't roll `getZone(...).cards.first { it.name == name }.let { bridge.getOrAllocInstanceId(...) }` inline.
- Need to *assert a card is in a zone*? Use `ZoneMatchers` (`"X" should beInHandOf(player)` or `... should beInZoneOf(zone, player, count = N)`). The matcher's failure message names card+player+zone; an inline `.cards.any { it.name == ... } shouldBe true` doesn't.
- Need to *walk the message log*? Use `MessageWalk.kt` extensions on `List<GREToClientMessage>`. Don't add private file-scoped walkers — promote them.
- Need to *read an annotation detail*? Use `TestExtensions.detail*()`. Don't inline `detailsList.firstOrNull { it.key == ... }`.
- Need to *build a client→GRE message*? Use `ProtoDsl.kt`.

If the helper you need doesn't exist and the predicate has appeared 3+ times across distinct files, add it. See "Adoption guardrails" below.

## Validating sink

`MatchFlowHarness(validating = true)` wraps the message sink in `ValidatingMessageSink`, which fails the test on per-message invariant breaks (unresolved iids on annotations, GSMs with mismatched gameObjects, etc.) — long before a soft assertion would notice. Default to `validating = true` for any new structural-shape test. Use `validating = false` only when:

1. You're driving past a known-broken invariant whose fix is tracked separately (`StockUpTest` resolution path), or
2. The puzzle-injected starting state lacks fields the validator requires (`MobilizeKeywordTest` due to synthetic ability ids), or
3. You're observing a Forge engine quirk (e.g. id reallocation on transform) that the validator legitimately rejects.

In every case, the `validating = false` line gets a comment naming the specific invariant being skipped and the tracking issue.

## Test-card setup

Per-card YAML fixtures (`matchdoor/src/test/resources/test-cards/<card>.yaml`) drive the slim `TestCardRegistry` — the post-#47 default. Add a card by writing the fixture, not by editing a Kotlin registrar. Reach for handwritten registration only when the card needs runtime ability-id stamping that the YAML schema doesn't cover (see `MobilizeKeywordTest`'s `beforeSpec` for the escape hatch — it has to inject `(keywordRow, cleanupRow)` triples that aren't in the client card-DB shape).

For ability-injection on an already-registered card (e.g. inject a planeswalker onto the battlefield), use `TestCardInjector.inject(b, seat, name, zone)` — it returns `Injected(card, instanceId, cardData)` with the right wiring.

## Assertion shape

Detekt rules to know — they shape what idioms are allowed:

- **`MissingAssertSoftly`** — runs of consecutive `should*`/`assert*` should be wrapped in `assertSoftly { ... }` so all failures report at once. The matchers below help you collapse a multi-field assertion into a single one, which removes the soft-assert pressure entirely.
- **`BooleanAssertion`** — `.shouldBeTrue()` / `.shouldBeFalse()` on a comparison or `.contains` collapses the expression to bare bool before kotest sees it; the failure message becomes "expected true, got false". Rewrite with a direct matcher. **This is the most common reason to add a domain matcher** — it's the rule that protects "expected 'X' to be in human's Battlefield (count=2), found 1" over "expected true, got false".
- **`NoTimingAssertsInTests`** — no wall-clock assertions. Performance gates belong in benchmarks, not FunSpec.
- **`NoThreadSleepInTests`** — `Thread.sleep` is forbidden. Use the harness's pass/await primitives.
- **`EmptyAssertion`** — at least one assertion per test.
- **`FunSpecMissingTags`** — every Spec must call `tags(...)`.

`@Suppress("WeakAssertionOnly")` is the right escape hatch when you're asserting structural absence (`hasOffer.shouldBeFalse()` on the result of `actionsList.any { ... } || inactiveActionsList.any { ... }`) — boolean predicates over a list ARE the native idiom for that shape, no equality body to assert. Once an `offerAltCost` matcher exists, prefer `actions shouldNot offerAltCost(altGrpId)` over the suppressed boolean — it's both shorter and self-describing.

## Matcher reasoning — when to add one

A matcher earns its keep when **both** are true:

1. **The predicate appears 3+ times across distinct files**, or 2+ times if the failure-message gain is meaningful (multi-field shape checks usually qualify — the alt-cost-stamp shape clears the bar at 2 sites because it's four fields).
2. **The matcher's failure message names the domain entity + the field that diverged**, not just `expected true was false`. If the only win is brevity, write a function (extension/private helper), not a matcher. If the win is a self-describing failure, write a matcher.

The bar is *predicate sites*, not *file count*. Five different fields on the same gameObject across two files is one site per call — you don't get to stack the count by calling the matcher more times in the same body. A matcher that ends up used in only one or two places gets deleted on review and the call sites get inlined back.

Don't:

- Wrap a single property access. `actionType shouldBe Cast` is fine inline; `beCast()` adds nothing. The "but the failure message would name the iid" argument doesn't carry — `obj.instanceId shouldBe X` (or just including the iid in the surrounding `shouldBe` chain) gets the same locator without a new symbol.
- Pre-build matchers for shapes that have appeared once. Wait for the second site so the abstraction matches the actual variation.
- Add a matcher whose failure message is just the predicate restated. The whole point is naming the domain entity ("'Stock Up' should be in human's Graveyard, found in Hand") not the boolean (`expected true, got false`).
- Widen a predicate when extracting it. If the call site asserted `actions.actionsList.any { ... }.shouldBeFalse()` (active list only), don't replace it with a matcher that searches active *and* inactive — that's a stricter assertion that may pass today but constrains future behavior in a direction the original test never claimed. Either keep the predicate scope or split the matcher (`offerActiveAltCost` vs `offerAltCost`).
- Add a `count = N` knob without thinking about the negation. `shouldNot beInZoneOf(zone, p, count = N)` does NOT mean "must not be in zone"; it means "must not have *exactly* N copies". If both meanings are useful, name them separately.

When you do add one:

- **Subject** = the noun the assertion reads naturally on. `"Card Name" should beInHandOf(player)` — subject is `String`. `offer should beAltCostOffer(altGrpId)` — subject is `Action?`. Pick what gives the most fluent reading.
- **Failure message** spells out the actual values. `"'$cardName' should be in ${player.name}'s $zone (count=$expected, found=$actual)"`. Kotest's `MatcherResult` takes positive + negative messages — fill both.
- **Tests for the matcher itself** go in `<MatcherFile>Test.kt` alongside. Test passing case, failing case (assert the failure message), and `shouldNot` inversion. Keep these as bare `FunSpec` tagged `UnitTag` — no engine.

## Cross-cutting reminders

- **Detekt rules are part of the gate.** `:matchdoor:detekt` runs before tests in CI and as a pre-commit hook. A test that compiles but trips a rule will block the merge.
- **Targeted tests during iteration:** `./gradlew :matchdoor:test --tests "leyline.mechanics.foretell.ForetellActionTest"` for one class, `--tests "leyline.mechanics.*.*Test"` for mechanics. `:matchdoor:testGate` (unit + board, excludes IntegrationTag) is the focused mid-iteration gate. `:matchdoor:test` is the full run including IntegrationTag — minutes, save for PR boundaries.
- **Test names** are sentences, not snake_case method names. `test("Foretell offer disappears when the {2} action cost is unpayable")` reads in the failure log as the assertion intent. Avoid `test("test foretell unpayable")` and `test("foretell_unpayable")`.
- **One puzzle, one test class** is the wrong split. One *behavior surface* per test class — tests within can share setup. `ForetellActionTest` covers the foretell hand-cast rail; it has 5 tests for 5 different conditions, all on Demon Bolt. That's correct.
- **Comments name invariants, not the test.** `// Pre-fix: zero SelectTargetsReq emitted, cast silently drops` documents the regression the test guards against. `// This test casts Foretell` does not — the test name and body already say that.
