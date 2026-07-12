# engine tests

Conventions for writing tests under `engine/src/test/kotlin/leyline/`. Read `engine/AGENTS.md` for the production-side architecture; this file covers the test-side decisions.

## Test tier — pick the right base class

Three tiers, each with a base class. **Never mix in one file.** A detekt rule (`TierPlacementCheck`) flags Session-tier tests that don't drive the game loop.

| Tier | Base | When | Cost |
|---|---|---|---|
| Board | `BoardTest` (in `testkit/`) | Test the bridge or annotation pipeline directly. `bundleBuilder(b).buildActions()`, `StateMapper.buildFromGame()`, `AnnotationBuilder` calls. | <0.1s/test |
| Session | `SessionTest` (in `testkit/`) | Test that requires driving the priority loop — `passUntil`, `selectTargets`, `declareAttackers`, `respondToOptionalCost`. The real `MatchSession` + Forge engine. | 0.7–3s/test |
| Pure unit | bare `FunSpec` | Pure-data logic. No engine, no harness. | <10ms/test |

If a Session-tier test never calls a driver (`passPriority`, `passUntil`, `advanceTo*`, `onPerformAction`, `respondTo*`), move it to Board tier — same signal, much cheaper. Suppress `TierPlacementCheck` only with a comment explaining why the loop is essential to the assertion (e.g. `DrawUpdateTypeShapeTest` needs a real turn-boundary draw event from the engine's EventBus).

Every direct Spec subclass/file must declare exactly one lane tag: `UnitTag`,
`BoardTag`, `IntegrationTag`, or `SimClientTag`. Semantic tags are additive, but
never add a second lane tag. `FunSpecMissingTags` enforces this. `SessionTest`
and `BoardTest` auto-tag — only standalone `FunSpec` classes need the explicit
call.

## Layout — where test files live

```
<production packages>/  SUT-shaped tests mirror `src/main/kotlin/leyline/*`.
board/<domain>/       Board-tier tests: bridge, mapper, bundle, annotations.
session/<domain>/     Session-tier tests: MatchSession + engine loop behavior.
mechanics/<keyword>/  Keyword/mechanic suites split action-vs-lifecycle.
behavior/             Behavior/protocol thesis tests with no single production SUT.
testkit/              Shared bases, harnesses, matchers, proto DSL, fixtures.
```

If a test has a clear production SUT, put it in the package matching that SUT
(`game.bundle`, `game.mapping`, `bridge.handoff`, `match`, etc.) even when it
uses the Board harness. Use `board/` and `session/` only for behavior-shaped
tests where no single production package owns the assertion. Do not put Board
and Session tests in the same file.

Use `behavior/<category>/<concept>/` for strict protocol-thesis tests, for
example `behavior/annotations/tokencreated/` or
`behavior/actions/castadventure/`. Use `behavior/cards/` for card-specific
flows where the card text is the surface under test, and `behavior/puzzles/`
for puzzle harness plumbing.

## Helpers — where things live

```
testkit/
├── ZoneMatchers.kt           kotest Matcher<String> for zone membership ("X" should beInHandOf(p))
├── ActionMatchers.kt         kotest matchers for Action / ActionsAvailableReq (alt-cost offers, etc.)
├── TestExtensions.kt         non-matcher extensions: AnnotationInfo.detail*(), GSM.annotation(type), ActionsAvailableReq.ofType()
├── MessageWalk.kt            List<GREToClientMessage> walkers: allAnnotations(), firstGameObjectByIid(), etc.
├── ProtoDsl.kt               builder DSL for client→GRE messages (main-source; performAction { ... })
├── Board.kt                  board-tier context (bridge, game, counter) returned by BoardTest's start* methods;
│                             destructures as (bridge, game, counter) for legacy call sites; single implementation
│                             of snapshotDiff/postAction/gameStart/transferCard — the (b, game, counter)-parameter
│                             helpers on BoardTest/BoardTestBase delegate here
├── BoardTestBase.kt          board-tier engine behind BoardTest; construct directly only for an isolated
│                             instance outside BoardTest's shared one (see PureDiffReplayTest)
├── BoardTest.kt              preferred board-tier base; wires lifecycle + BoardTag, hosts the board-tier
│                             probe DSL (Player.battlefield/hand/….iid(name) via the current board's bridge)
├── SessionTest.kt            base class — wires MatchFlowHarness, exposes selectTargets/passUntil/instanceIdOf, after { } slice builder, Player.{battlefield,hand,…}.iid(name) probe DSL
├── MessageSlice.kt           bounded slice of GREToClientMessage from after { } — typed expectOne*/expectNo* prompt assertions + block-form prompt-shape sub-DSL
├── PlayerZone.kt             (player, zone) probe handle + shared iidVia(bridge, name) resolver behind both bases' probe DSLs
├── MatchFlowHarness.kt       Session-tier harness (main-source) — owns the game thread, message stream, scripted AI
├── ScriptedPlayerController.kt (main-source)
├── ClientAccumulator.kt      main-source GSM accumulator — invariant checker
└── ValidatingMessageSink.kt  main-source per-message GSM/GRE validation — enable with validating=true
```

**Helper-layering rule:** extensions on wire types (`GameStateMessage`, `List<GREToClientMessage>`, `Action`) when the helper only reads messages already produced; methods on `Board` / `MatchFlowHarness` when the helper needs live game/bridge state; spec base classes (`BoardTest`, `SessionTest`) carry lifecycle wiring and naming sugar only, not new logic.

**Picking a layer when you reach for a helper:**

- Need to *find a card iid* in a Session- or Board-tier test? Prefer the probe DSL: `human.battlefield.iid("Walking Corpse")`, `ai.exile.iid("Forum's Favor")`. Falls back to `instanceIdOf(name, player, zone)` when the zone is computed at runtime. Don't roll `getZone(...).cards.first { it.name == name }.let { bridge.getOrAllocInstanceId(...) }` inline.
- Need to *assert prompt shape* in a window of messages? `after { castSpellByName("X") }.expectOneCastingTimeOptionsReq()` (or `.expectNo*` / block-form `.expectCastingTimeOptionsReq { option(...); done(...) }`). Raw `messageSnapshot()` / `messagesSince()` still work as an escape hatch when the assertion is positional. Note: `expectOne*` means *exactly one* — if a flow legitimately re-prompts, fall back to the raw walker rather than silently tightening the contract.
- Need to *assert a card is in a zone*? Use `ZoneMatchers` (`"X" should beInHandOf(player)` or `... should beInZoneOf(zone, player, count = N)`). The matcher's failure message names card+player+zone; an inline `.cards.any { it.name == ... } shouldBe true` doesn't.
- Need to *walk the message log*? Use `MessageWalk.kt` extensions on `List<GREToClientMessage>` — they compose with `MessageSlice.messages`. Don't add private file-scoped walkers — promote them.
- Need to *read an annotation detail*? Use `TestExtensions.detail*()`. Don't inline `detailsList.firstOrNull { it.key == ... }`.
- Need to *build a client→GRE message*? Use `ProtoDsl.kt`.

If the helper you need doesn't exist and the predicate has appeared 3+ times across distinct files, add it. See "Adoption guardrails" below.

## Validating sink

`MatchFlowHarness(validating = true)` wraps the message sink in `ValidatingMessageSink`, which fails the test on hard client-compatible sequencing breaks. Default validation covers the stable facts: monotonic/unique gsIds, no self-referential gsIds, and AIC/AID affector consistency. Use `InvariantSelection.diagnostics()` or `InvariantSelection.only(...)` for narrower structural-shape tests such as annotation references, zone/object consistency, or annotation ordering.

For puzzle-start state limits and the direct-state vs setup-action decision, read `../../../../../docs/puzzle-harness.md`.

Use `InvariantSelection.protocolFactsExcept(...)` only when:

1. You're driving past a known-broken invariant whose fix is tracked separately (`StockUpTest` resolution path), or
2. The puzzle-injected starting state lacks fields the validator requires (`MobilizeKeywordTest` due to synthetic ability ids), or
3. You're observing a Forge engine quirk (e.g. id reallocation on transform) that the validator legitimately rejects.

In every case, the selection reason names the specific hard check being skipped and the tracking issue.

## Test-card setup

Per-card YAML fixtures (`engine/src/test/resources/test-cards/<card>.yaml`) drive the slim `TestCardRegistry` — the post-#47 default. Add a card by writing the fixture, not by editing a Kotlin registrar. Reach for handwritten registration only when the card needs runtime ability-id stamping that the YAML schema doesn't cover (see `MobilizeKeywordTest`'s `beforeSpec` for the escape hatch — it has to inject `(keywordRow, cleanupRow)` triples that aren't in the client card-DB shape).

For ability-injection on an already-registered card (e.g. inject a planeswalker onto the battlefield), use `TestCardInjector.inject(b, seat, name, zone)` — it returns `Injected(card, instanceId, cardData)` with the right wiring.

## Assertion shape

Detekt rules to know — they shape what idioms are allowed:

- **`MissingAssertSoftly`** — runs of consecutive `should*`/`assert*` should be wrapped in `assertSoftly { ... }` so all failures report at once. The matchers below help you collapse a multi-field assertion into a single one, which removes the soft-assert pressure entirely.
- **`BooleanAssertion`** — `.shouldBeTrue()` / `.shouldBeFalse()` on a comparison or `.contains` collapses the expression to bare bool before kotest sees it; the failure message becomes "expected true, got false". Rewrite with a direct matcher. **This is the most common reason to add a domain matcher** — it's the rule that protects "expected 'X' to be in human's Battlefield (count=2), found 1" over "expected true, got false".
- **`NoTimingAssertsInTests`** — no wall-clock assertions. Performance gates belong in benchmarks, not FunSpec.
- **`NoThreadSleepInTests`** — `Thread.sleep` is forbidden. Use the harness's pass/await primitives.
- **`EmptyAssertion`** — at least one assertion per test.
- **`FunSpecMissingTags`** — every direct Spec class/file must declare exactly one lane tag.
- **`TestLayoutCheck`** — `board/*`, `session/*`, and `mechanics/*` packages must match the lane. It rejects mixed `BoardTag` + `IntegrationTag` in domain files and direct `leyline.mechanics` packages.

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

- **Detekt rules are part of the gate.** `:engine:detekt` runs before tests in CI and as a pre-commit hook. A test that compiles but trips a rule will block the merge.
- **Targeted tests during iteration:** `just test-one ForetellActionTest` for one engine class, `./gradlew :engine:test --tests "leyline.mechanics.*.*Test"` for mechanics. `:engine:testGate` (unit + board, excludes IntegrationTag) is the focused mid-iteration gate. `:engine:test` is the full run including IntegrationTag — minutes, save for PR boundaries.
- **Test names** are sentences, not snake_case method names. `test("Foretell offer disappears when the {2} action cost is unpayable")` reads in the failure log as the assertion intent. Avoid `test("test foretell unpayable")` and `test("foretell_unpayable")`.
- **One puzzle, one test class** is the wrong split. One *behavior surface* per test class — tests within can share setup. `ForetellActionTest` covers the foretell hand-cast rail; it has 5 tests for 5 different conditions, all on Demon Bolt. That's correct.
- **Comments name current invariants, not history or the test.** `// Cast must emit SelectTargetsReq before resolution` documents the guarded contract. `// Pre-fix: ...` and `// This test casts Foretell` do not—Git has history, while the test name/body already state the action.
