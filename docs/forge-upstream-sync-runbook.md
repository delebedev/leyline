# Forge Upstream Sync Runbook

Weekly update flow for the `forge` submodule from upstream `Card-Forge/forge` while keeping Leyline operational.

## Goal

Use `leyline` as the working context and validation harness.

- Merge/update happens in the `forge` submodule.
- Integration validation happens from `leyline` root.
- Any engine fixes discovered during integration get pushed back to `delebedev/forge`.
- `leyline` only advances the submodule pointer after the fork branch is in good shape.

This keeps the engine fork small, explicit, and reproducible.

## Mental Model

`leyline` is the product.

- `forge/` is a vendored engine fork, consumed as local Maven artifacts from `forge/.m2-local`.
- `matchdoor/` is the adapter layer; structural Forge coupling belongs there.
- Weekly sync risk is mostly integration risk, not just merge conflict risk.

So the safe home base is `~/src/leyline`, not the bare fork alone.

## Preserve Surface

These are the pieces most likely to matter during upstream updates.

### 1. Build / install contract

Leyline expects Forge jars to be installed locally.

- [build.gradle.kts](/Users/denislebedev/src/leyline/build.gradle.kts)
- [matchdoor/build.gradle.kts](/Users/denislebedev/src/leyline/matchdoor/build.gradle.kts)
- [justfile](/Users/denislebedev/src/leyline/justfile)
- [forge/.mvn/maven.config](/Users/denislebedev/src/leyline/forge/.mvn/maven.config)

Key invariant:

- `just install-forge` must populate `forge/.m2-local`
- Leyline Gradle builds must resolve `forge:*` artifacts from that local repo

### 2. Bridge/controller seam

Leyline's headless bridge depends on `WebPlayerController` staying able to subclass and override desktop behavior cleanly.

- [matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt)
- [forge/forge-gui/src/main/java/forge/player/PlayerControllerHuman.java](/Users/denislebedev/src/leyline/forge/forge-gui/src/main/java/forge/player/PlayerControllerHuman.java)
- [forge/forge-gui/src/main/java/forge/player/HumanPlay.java](/Users/denislebedev/src/leyline/forge/forge-gui/src/main/java/forge/player/HumanPlay.java)
- [forge/forge-gui/src/main/java/forge/player/HumanPlaySpellAbility.java](/Users/denislebedev/src/leyline/forge/forge-gui/src/main/java/forge/player/HumanPlaySpellAbility.java)
- [forge/forge-gui/src/main/java/forge/player/TargetSelection.java](/Users/denislebedev/src/leyline/forge/forge-gui/src/main/java/forge/player/TargetSelection.java)
- [forge/forge-gui/src/main/java/forge/player/TargetSelectionResult.java](/Users/denislebedev/src/leyline/forge/forge-gui/src/main/java/forge/player/TargetSelectionResult.java)

What must remain true:

- web controller can route prompts through `InteractivePromptBridge`
- cost decisions can be customized for web/headless flow
- target selection can return enough structure for Leyline session handlers

Upstream may replace the exact seam shape. That is fine if the capability still exists.

### 3. Event payloads used by annotation/state mapping

Leyline depends on richer event info than stock upstream in a few places.

- [forge/forge-game/src/main/java/forge/game/event/GameEventCardSurveiled.java](/Users/denislebedev/src/leyline/forge/forge-game/src/main/java/forge/game/event/GameEventCardSurveiled.java)
- [forge/forge-game/src/main/java/forge/game/event/GameEventTokenCreated.java](/Users/denislebedev/src/leyline/forge/forge-game/src/main/java/forge/game/event/GameEventTokenCreated.java)
- [forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java](/Users/denislebedev/src/leyline/forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java)
- [forge/forge-game/src/main/java/forge/game/card/CardZoneTable.java](/Users/denislebedev/src/leyline/forge/forge-game/src/main/java/forge/game/card/CardZoneTable.java)

Leyline consumers:

- [matchdoor/src/main/kotlin/leyline/game/GameEvent.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/GameEvent.kt)
- [matchdoor/src/main/kotlin/leyline/game/StateMapper.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/StateMapper.kt)
- [matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt)

If upstream changes event structure, re-check annotation fidelity first.

### 4. Testability / determinism helpers

- [forge/forge-core/src/main/java/forge/util/MyRandom.java](/Users/denislebedev/src/leyline/forge/forge-core/src/main/java/forge/util/MyRandom.java)
- [forge/forge-core/src/main/java/forge/card/CardDb.java](/Users/denislebedev/src/leyline/forge/forge-core/src/main/java/forge/card/CardDb.java)

Why they matter:

- deterministic tests / seeded game setup
- less noisy card-db init during test runs

## Current Local-Only Engine Deltas

As of `forge` at submodule SHA `3bd62b8b27`:

- `7d297ea18a` `feat(forge-gui): implement ADR-010 seam extraction for WebPlayerController`
- `cae382da8e` `fix: route bare Collections.shuffle calls through MyRandom`
- `593b4ff8bd` `chore: suppress noisy card-init warnings in tests`
- `df3e07a1f2` `feat(forge-game): enrich GameEventTokenCreated with card refs`
- `3bd62b8b27` `feat: GameEventCardSurveiled carries causeCard for affectorId`

Treat these as the first regression checklist when an upstream merge lands.

## Weekly Flow

### 1. Start from Leyline root

```bash
cd ~/src/leyline
git status --short --branch
git submodule status
```

Do not start in the bare fork unless the task is purely engine-local.

### 2. Fetch upstream in the submodule

```bash
git -C forge fetch https://github.com/Card-Forge/forge.git master
```

Optional divergence check:

```bash
git -C forge rev-list --left-right --count HEAD...FETCH_HEAD
```

### 3. Create a throwaway sync worktree

Never test the merge directly on the pinned submodule checkout.

```bash
git -C forge worktree add ../forge--sync -b sync/upstream-master master
cd ~/src/leyline/forge--sync
git merge --no-commit --no-ff <upstream-commit-sha>
```

If you only fetched one branch, `<upstream-commit-sha>` can be `FETCH_HEAD` from the original submodule checkout, or the resolved SHA printed by `git rev-parse`.

### 4. Resolve conflicts with Leyline context in mind

Use this order:

1. build/install context
2. controller seam / prompt flow
3. event payloads
4. testability helpers
5. everything else

When a Forge file conflicts, immediately map it to Leyline consumers before resolving.

Examples:

- `PlayerControllerHuman` / `HumanPlay*` conflict:
  check [matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt)
- event conflict:
  check [matchdoor/src/main/kotlin/leyline/game/GameEvent.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/GameEvent.kt)
- Maven/build conflict:
  check [build.gradle.kts](/Users/denislebedev/src/leyline/build.gradle.kts), [matchdoor/build.gradle.kts](/Users/denislebedev/src/leyline/matchdoor/build.gradle.kts), [justfile](/Users/denislebedev/src/leyline/justfile)

### 5. Install the merged engine into Leyline's local repo

From `~/src/leyline`:

```bash
just install-forge
```

This is the first real proof that the engine state is consumable by Leyline.

### 6. Run the minimum sync gate

```bash
just build
just test-gate
just test-integration
```

Interpretation:

- `just build`
  proves proto sync + Kotlin compile + local Forge artifact resolution
- `just test-gate`
  proves formatting + unit + conformance tiers
- `just test-integration`
  proves real engine boot / bridge flow under test

### 7. If Leyline breaks, patch in the right place

- engine problem or seam mismatch:
  patch in `forge--sync`, then push to `delebedev/forge`
- Leyline adapter mismatch:
  patch in `leyline`

Do not leave important engine fixes only in local submodule state.

### 8. Land it cleanly

After the Forge branch is ready:

1. push branch to `delebedev/forge`
2. open PR there
3. merge there
4. update `leyline/forge` submodule to merged SHA
5. commit submodule pointer in `leyline`

## Conflict Heuristics

## TODO

- Investigate root cause of the post-upstream cast-payment regression, not just the Leyline-side fallback.
  Context: after merging upstream `master` into `forge`, `AnnotationOrderingTest` started failing because cast GSMs lost `TappedUntappedPermanent` annotations. Debugging showed this was not simple flake: the engine thread was crashing during cast payment inside `WebPlayerController` because upstream now routes through `PlayerController.payManaCost/applyManaToCost/chooseCardsForCost` and can pass nullable `prompt` / `matrix` values from Java. Our Kotlin overrides were too strict, so the game loop threw before the mana-payment path completed cleanly. The visible protocol symptom was "6 cast annotations only"; the deeper symptom was an engine-thread crash with partial progress. We added compatibility overrides and a Leyline-side tap-diff fallback in `StateMapper`, but still need to trace whether the fallback should remain once the nullability/controller-path issue is fully resolved. Preferred outcome: confirm the exact upstream call path, prove whether `GameEventCardTapped` now arrives reliably again, and remove any workaround that only papers over the crash.

### Workflow / CI files in `forge`

Usually fork-local. Prefer preserving fork intent unless Leyline depends on upstream CI behavior.

### `HumanPlay` / `PlayerControllerHuman` / `PlaySpellAbility`

High-risk conflict area.

Questions to ask:

- can `WebPlayerController` still override the needed hook?
- did upstream move the logic into `forge-game` with controller abstractions?
- if yes, can Leyline move from its custom seam to the new abstraction instead of carrying a fork patch?

### Event classes

If upstream changed the same event, check whether it now carries the fields Leyline needed.

- if yes: drop the fork patch
- if no: preserve or re-derive the payload

### RNG / quiet init

Low conceptual risk. Keep if tests still need them.

## High-Value Leyline Tests For Syncs

Bridge / engine boot:

- [matchdoor/src/test/kotlin/leyline/game/GameBridgeTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/game/GameBridgeTest.kt)
- [matchdoor/src/test/kotlin/leyline/protocol/HandshakeMessagesTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/protocol/HandshakeMessagesTest.kt)
- [matchdoor/src/test/kotlin/leyline/bridge/GameActionBridgeTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/bridge/GameActionBridgeTest.kt)

Event / annotation fidelity:

- [matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/game/GameEventCollectorTest.kt)
- [matchdoor/src/test/kotlin/leyline/game/CategoryFromEventsTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/game/CategoryFromEventsTest.kt)
- [matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt)

End-to-end behavior:

- [matchdoor/src/test/kotlin/leyline/conformance/CombatFlowTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/conformance/CombatFlowTest.kt)
- [matchdoor/src/test/kotlin/leyline/conformance/TargetingFlowTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/conformance/TargetingFlowTest.kt)
- [matchdoor/src/test/kotlin/leyline/conformance/SurveilFlowTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/conformance/SurveilFlowTest.kt)
- [matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt)
- [matchdoor/src/test/kotlin/leyline/conformance/PvpBridgeEndToEndTest.kt](/Users/denislebedev/src/leyline/matchdoor/src/test/kotlin/leyline/conformance/PvpBridgeEndToEndTest.kt)

If a sync is risky in one of the preserve areas, run these explicitly even if the broad gate already passed.

## Compact Troubleshooting

Observed break patterns from the March 17, 2026 upstream sync:

- Compile/API drift
  - Symptoms: Kotlin compile failures in `matchdoor`, event constructor mismatches, missing methods on Forge interfaces.
  - Typical cause: upstream moved GUI-side logic into `forge-game`, changed event payloads to `*View` types, or renamed `IGuiGame` methods.
  - First check: [WebPlayerController.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt), [WebGuiGame.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/bridge/WebGuiGame.kt), [GameEventCollector.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt)

- Engine-thread runtime crash disguised as protocol regression
  - Symptoms: annotation/order tests fail for "missing" protocol data, but root cause is earlier engine failure.
  - Concrete example: cast flow lost `TappedUntappedPermanent`, but the real issue was `WebPlayerController` crashing on Java-nullable `prompt` / `matrix` in upstream `payManaCost/applyManaToCost`.
  - First check: test XML `system-out/system-err`, `GameLoopController` logs, nullability on Kotlin overrides of Java methods.

- Controller seam drift
  - Symptoms: web prompts stop appearing, mana payment or cost decisions silently route through desktop code, targeting path stops using bridge requests.
  - Typical cause: upstream starts calling different `PlayerController` methods than the old seam covered.
  - First check: compare upstream call sites in `forge-game/.../PlaySpellAbility.java` against overrides in [WebPlayerController.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/bridge/WebPlayerController.kt)

- Event adapter drift
  - Symptoms: missing attachment/token/surveil annotations even though game state looks roughly right.
  - Typical cause: upstream changed event payload classes or view/entity shapes.
  - Concrete example: `GameEventCardAttachment` now carries `GameEntityView`; old collector logic expected concrete `Card`.
  - First check: [GameEventCollector.kt](/Users/denislebedev/src/leyline/matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt), corresponding Forge `GameEvent*.java`

- Test fragility from sequencing or deck assumptions
  - Symptoms: integration tests fail after update even though isolated engine behavior is valid.
  - Typical cause: test depended on seeded draw order, exact pass-count timing, or old stack-resolution cadence.
  - Concrete example: `TargetingFlowTest` assumed default-deck `Giant Growth` draw and one-pass resolution; fixed by using puzzle setup and waiting for actual buffed state.
  - First check: convert random/deck-driven setup to puzzle setup; replace fixed pass counts with state-based waits.

Practical triage order:

1. `just install-forge`
2. `just build`
3. isolate first failing spec
4. inspect test report `system-out/system-err` for engine-thread crashes
5. check upstream call-path drift before patching protocol layer
6. only then add fallback logic or test hardening

Extra heuristics from this sync:

- if a failure looks like “missing annotation”, “missing prompt”, or “missing target request”, first assume upstream may have broken an earlier engine-thread/controller path rather than the protocol layer itself
- when upstream shifts Java call paths, re-check Kotlin override nullability on controller methods even if the old path never passed null
- prefer puzzle-based integration setup for sync-sensitive flows; seeded deck progression and fixed pass counts are weaker guards against upstream behavior drift

## Failure Modes

### `just install-forge` fails

Usually:

- Forge merge is incomplete
- Maven revision/local-repo settings drifted
- upstream changed module graph / artifact names

### `just build` fails

Usually:

- local Forge jars missing or stale
- binary-incompatible engine API changes
- proto sync or Kotlin compile break from adapter assumptions

### `just test-gate` fails but build passes

Usually:

- event/annotation drift
- changed target/prompt flow
- state mapping assumptions broken

### `just test-integration` fails

Usually:

- engine boot path changed
- `WebPlayerController` no longer intercepts a prompt path
- game loop blocks in a desktop-only callback

## Current Validation Status

Validated on `2026-03-17` against the current checkout where:

- Forge upstream sync PR was merged to `delebedev/forge`
- `leyline/forge` now points at merged fork tip `865759fec1`
- fetched upstream comparison tip during the sync was `02a1da0cac`

Observed conflict set in the simulated upstream merge:

- Forge CI workflow deletions
- `DraftEffect`
- `PlaySpellAbility`
- `HumanPlaySpellAbility`
- `PlayerControllerHuman`

That conflict shape matches the preserve surface above, which is a good sign: the runbook is focusing on the right files.

Operational gate results from this run:

- `just install-forge` — PASS
- `just build` — PASS
- `just test-gate` — PASS (`586/586`, `144 skipped`)
- `just test-integration` — PASS (`138/138`, `572 skipped`)

Known non-blocking observations from this validation:

- `just build` emits Kotlin warnings in `WebCostDecision.kt` and `WebGuiGame.kt`
- Gradle prints deprecation warnings about future Gradle 9 compatibility
- the highest-signal runtime regression during this sync was an engine-thread crash in `WebPlayerController` caused by Java-nullable `prompt` / `matrix` values entering Kotlin overrides after upstream controller refactoring
