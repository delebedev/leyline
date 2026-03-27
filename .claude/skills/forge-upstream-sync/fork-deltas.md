# Fork-Local Engine Deltas

Reference for the `forge-upstream-sync` skill. Lists every patch we carry on top of upstream `Card-Forge/forge`, where it lives, and what breaks if it's lost.

## Event Classes

Custom events in `forge-game/src/main/java/forge/game/event/`:

| Class | Fields | Purpose |
|---|---|---|
| `GameEventManaAbilityActivated` | `CardView source, String produced` | Mana globe annotations |
| `GameEventSpellMovedToStack` | `CardView card` | Cast annotation sequencing |
| `GameEventControllerChanged` | `Card card, Player oldController, Player newController` | Steal/threaten annotations |
| `GameEventCardDestroyed` | `Card card, Card activator` | Destroy source attribution (upstream has empty record) |
| `GameEventSpellAbilityCast` | `List<ManaPaymentInfo> manaPayments` (fork-enriched record) | Mana payment tracking |
| `GameEventTokenCreated` | enriched with card refs | Token source tracking |
| `GameEventCardSurveiled` | `causeCard` field | Surveil affector tracking |

All custom events need `visit()` methods in `IGameEventVisitor` interface + `Base` class.

## Event Fire Sites

**These are the most fragile patches.** Single-line `game.fireEvent(...)` calls in large files that auto-merge away without any conflict marker. After every upstream merge, grep each file for the event class name.

| Event | File | Location |
|---|---|---|
| `GameEventManaAbilityActivated` | `AbilityManaPart.produceMana()` | After `manaPool.add(this.lastManaProduced)` |
| `GameEventSpellMovedToStack` | `PlaySpellAbility.playAbility()` | After `moveToStack()`, before `changeText()` |
| `GameEventControllerChanged` | `GameAction.controllerChangeZoneCorrection()` | After `handleChangedControllerSprocketReset()` |
| `GameEventCardDestroyed` | `GameAction.destroy()` | `new GameEventCardDestroyed(c, sa != null ? sa.getHostCard() : null)` |

Quick verification:

```bash
cd ~/src/leyline/forge--sync  # or forge/
grep -l "GameEventManaAbilityActivated" forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java
grep -l "GameEventSpellMovedToStack" forge-game/src/main/java/forge/game/player/PlaySpellAbility.java
grep -l "GameEventControllerChanged" forge-game/src/main/java/forge/game/GameAction.java
grep -l "GameEventCardDestroyed" forge-game/src/main/java/forge/game/GameAction.java
```

All 4 must return a match. Missing = re-add the fire line.

## Other Patches

| Patch | File | Purpose |
|---|---|---|
| ADR-010 seam methods | `PlayerControllerHuman.java` | `createCostDecision`, `selectTargetsInteractively`, etc. for `WebPlayerController` override |
| `MyRandom` shuffle routing | `MyRandom.java` | Deterministic tests |
| Suppressed card-init warnings | `CardDb.java` | Quieter test output |
| Removed `System.out.println` | `GameAction.controllerChangeZoneCorrection()` | Stale debug line from upstream |

## Preserve Surface

Files most likely to conflict. When they do, map to leyline consumers before resolving.

### Controller seam

| Forge file | Leyline consumer |
|---|---|
| `forge-game/.../player/PlaySpellAbility.java` | `WebPlayerController.kt` |
| `forge-gui/.../player/PlayerControllerHuman.java` | `WebPlayerController.kt` |
| `forge-gui/.../player/TargetSelection.java` | `WebPlayerController.kt` |
| `forge-gui/.../player/TargetSelectionResult.java` | `WebPlayerController.kt` |

Note: `HumanPlaySpellAbility.java` was **deleted upstream** (merged into `PlaySpellAbility`). If it reappears in conflicts, delete it.

### Events

| Forge file | Leyline consumers |
|---|---|
| `IGameEventVisitor.java` | `GameEventCollector.kt` |
| All fork-local event classes above | `GameEventCollector.kt`, `GameEvent.kt`, `AnnotationBuilder.kt` |

### Build contract

| Forge file | Leyline consumers |
|---|---|
| `pom.xml`, `.mvn/maven.config` | `build.gradle.kts`, `justfile` |

## Validation History

### 2026-03-27

- Upstream: `28e1847a5bc` (466 commits)
- Fork tip: `dba25420f6b`
- Conflicts: CI workflows, IGameEventVisitor, CounterKeywordType/CounterType, ~280 adventure/content files
- Gate: 646/646 test-gate, integration 2 pre-existing failures
- Lesson: worktree must branch from `origin/master`, not submodule detached HEAD

### 2026-03-17

- Upstream: `02a1da0cac`
- Fork tip: `865759fec1`
- Conflicts: CI workflows, DraftEffect, PlaySpellAbility, HumanPlaySpellAbility, PlayerControllerHuman
- Gate: 586/586 test-gate, 138/138 integration
- Lesson: engine-thread crash in WebPlayerController from Java-nullable prompt/matrix — check Kotlin override nullability after upstream controller refactoring
