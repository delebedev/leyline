---
name: forge-upstream-sync
description: Sync the forge engine submodule with upstream Card-Forge/forge. Merge upstream master, resolve conflicts preserving fork-local patches, validate with leyline test suite, create PRs for both repos.
---

## What I do

Merge upstream `Card-Forge/forge` master into `delebedev/forge` fork, validate against leyline's test suite, and land via PRs to both repos.

## Prerequisites

- `mvn` in PATH (`brew install maven`)
- Forge submodule initialized (`git submodule update --init`)

## Critical: Worktree Base

**Always branch from `origin/master` of `delebedev/forge`, NOT from the submodule's detached HEAD.** The submodule pin can lag behind the fork's master by many commits. Branching from the stale pin creates a divergent merge that conflicts with the fork's own master.

## Flow

### 1. Start from leyline root

```bash
cd ~/src/leyline
git status --short --branch
git submodule status
```

### 2. Fetch upstream (use --depth for speed)

Full fetch of Card-Forge/forge times out over HTTPS. Shallow fetch works:

```bash
git -C forge fetch https://github.com/Card-Forge/forge.git master --depth=100
```

Divergence check:

```bash
git -C forge rev-list --left-right --count HEAD...FETCH_HEAD
```

Also check preserve surface for upstream changes:

```bash
git -C forge log --oneline FETCH_HEAD --not HEAD -- \
  forge-gui/src/main/java/forge/player/PlayerControllerHuman.java \
  forge-game/src/main/java/forge/game/player/PlaySpellAbility.java \
  forge-game/src/main/java/forge/game/event/ \
  forge-game/src/main/java/forge/game/GameAction.java \
  forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java
```

If empty: no preserve surface conflicts expected. If not: careful merge ahead.

### 3. Create sync worktree from origin/master

```bash
git -C forge fetch origin master
git -C forge worktree add ../forge--sync -b sync/upstream-$(date +%Y-%m-%d) origin/master
```

Then merge upstream:

```bash
cd ~/src/leyline/forge--sync
git merge --no-commit --no-ff <upstream-sha>
```

### 4. Resolve conflicts

Priority order:

1. **CI workflows** — keep fork deletions (`git rm`)
2. **IGameEventVisitor** — keep ours (fork adds visit methods for custom events)
3. **Controller seam** (`PlayerControllerHuman`, `PlaySpellAbility`) — take upstream, preserve fork seam methods
4. **Event classes** — keep fork enrichments
5. **Adventure/mobile/desktop content** — take upstream (`git checkout --theirs`)
6. **Everything else** — take upstream

For bulk upstream-takes with files that have spaces:

```bash
git diff --diff-filter=U --name-only | grep -v "preserve-pattern" | while IFS= read -r f; do
  git checkout --theirs -- "$f" && git add "$f" || git rm "$f"
done
```

### 5. Install merged forge

`just install-forge` hardcodes `forge/` dir. Run maven directly from the sync worktree:

```bash
cd ~/src/leyline/forge--sync
mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install \
  -pl forge-core,forge-game,forge-ai,forge-gui -am \
  -DskipTests -Dcheckstyle.skip=true -q
```

Then copy jars to where leyline expects them:

```bash
rsync -a ~/src/leyline/forge--sync/.m2-local/ ~/src/leyline/forge/.m2-local/
```

### 6. Build + test

```bash
cd ~/src/leyline
just build
just test-gate        # must pass 100%
just test-integration # 2 known pre-existing failures ok
```

### 7. If leyline breaks, patch in the right place

- Engine API change: patch in `forge--sync`, commit, re-install, re-test
- Leyline adapter code: patch in leyline

Common compile fixes:
- **Constructor signature changes** (e.g. `HumanCostDecision` gained a `prompt` param) — update fork seam calls
- **Duplicate methods from auto-merge** (e.g. `StaticAbilityMustAttack`) — git can silently duplicate identical methods added at different positions
- **Missing fork-local event fire sites** — single-line `game.fireEvent(...)` calls in large files auto-merge away without conflict. Always verify fire sites after merge (see below).

### 8. Land it

1. Commit merge in `forge--sync`
2. Push branch to `delebedev/forge`
3. Create PR there (list preserved fork deltas in body)
4. Merge PR
5. In leyline: `git -C forge fetch origin master && git -C forge checkout origin/master && git add forge`
6. Create leyline branch + PR with submodule pointer update

## Fork-Local Engine Deltas

These are the patches we carry. After every merge, verify ALL exist — especially fire sites.

### Event classes (in `forge-game/.../event/`)

| Class | Fields | Purpose |
|---|---|---|
| `GameEventManaAbilityActivated` | `CardView source, String produced` | Mana globe annotations |
| `GameEventSpellMovedToStack` | `CardView card` | Cast annotation sequencing |
| `GameEventControllerChanged` | `Card card, Player oldController, Player newController` | Steal/threaten annotations |
| `GameEventCardDestroyed` | `Card card, Card activator` | Destroy source attribution (upstream has empty record) |
| `GameEventSpellAbilityCast` | `List<ManaPaymentInfo> manaPayments` | Mana payment tracking (fork-enriched) |
| `GameEventTokenCreated` | enriched with card refs | Token source tracking |
| `GameEventCardSurveiled` | `causeCard` field | Surveil affector tracking |

All custom events need corresponding `visit()` methods in `IGameEventVisitor` + `Base` class.

### Event fire sites (CRITICAL — silently lost in merges)

| Event | Fire site | Location |
|---|---|---|
| `GameEventManaAbilityActivated` | `AbilityManaPart.produceMana()` | After `manaPool.add(this.lastManaProduced)` |
| `GameEventSpellMovedToStack` | `PlaySpellAbility.playAbility()` | After `moveToStack()`, before `changeText()` |
| `GameEventControllerChanged` | `GameAction.controllerChangeZoneCorrection()` | After `handleChangedControllerSprocketReset()` |
| `GameEventCardDestroyed` | `GameAction.destroy()` | Uses `(c, sa != null ? sa.getHostCard() : null)` |

**Post-merge check:** grep each fire site file for the event class name. If missing, re-add.

### Other patches

| Patch | File | Purpose |
|---|---|---|
| ADR-010 seam methods | `PlayerControllerHuman.java` | `createCostDecision`, `selectTargetsInteractively`, etc. |
| MyRandom shuffle routing | `MyRandom.java` | Deterministic tests |
| Suppressed card-init warnings | `CardDb.java` | Quieter test output |
| Removed `System.out.println` | `GameAction.controllerChangeZoneCorrection()` | Stale debug line |

## Preserve Surface

Files most likely to conflict. When they do, map to leyline consumers before resolving.

### Controller seam

- `forge-game/.../player/PlaySpellAbility.java` (moved from `forge-gui` as of upstream March 2026)
- `forge-gui/.../player/PlayerControllerHuman.java`
- `forge-gui/.../player/TargetSelection.java`
- `forge-gui/.../player/TargetSelectionResult.java`

Leyline consumer: `matchdoor/.../bridge/WebPlayerController.kt`

Note: `HumanPlaySpellAbility.java` was **deleted upstream** (merged into `PlaySpellAbility`). If it reappears in conflicts, delete it.

### Events

- `forge-game/.../event/IGameEventVisitor.java`
- All fork-local event classes listed above

Leyline consumers: `GameEventCollector.kt`, `GameEvent.kt`, `AnnotationBuilder.kt`

### Build contract

- `pom.xml`, `.mvn/maven.config`

Leyline consumers: `build.gradle.kts`, `justfile`

## Troubleshooting

### Maven install fails with checkstyle errors

Add `-Dcheckstyle.skip=true`. The fork doesn't maintain upstream's checkstyle config.

### Upstream fetch times out

Use `--depth=100`. The Card-Forge/forge repo is huge; full fetch over HTTPS is unreliable.

### Compile error: duplicate method

Auto-merge can silently duplicate identical methods when both sides added the same code. Search for the method name — if it appears twice, delete one.

### Compile error: constructor signature mismatch

Upstream changed a constructor that fork seam code calls. Read the new constructor, update the call. Common: `HumanCostDecision` gaining/losing parameters.

### Tests fail for "missing annotation" after merge

Don't assume the annotation pipeline broke. First check:
1. Are fork-local event fire sites still present? (grep the fire site files)
2. Is the engine thread crashing earlier? (check test XML `system-out/system-err`)
3. Did upstream change Java call paths that bypass Kotlin overrides? (nullability on controller methods)

### Integration test timing regression

If `AiCombatAutoPassTest` or similar timing tests regress, check if it's pre-existing by testing against the old forge jars.

## Validation History

### 2026-03-27

- Upstream: `28e1847a5bc` (466 commits)
- Fork tip: `dba25420f6b`
- Conflicts: CI workflows, IGameEventVisitor, CounterKeywordType/CounterType, ~280 adventure/content files
- Gate: 646/646 test-gate, integration 2 pre-existing failures
- Notable: worktree initially branched from stale submodule pin — had to redo from `origin/master`

### 2026-03-17

- Upstream: `02a1da0cac`
- Fork tip: `865759fec1`
- Conflicts: CI workflows, DraftEffect, PlaySpellAbility, HumanPlaySpellAbility, PlayerControllerHuman
- Gate: 586/586 test-gate, 138/138 integration
- Notable: engine-thread crash in WebPlayerController from Java-nullable prompt/matrix after upstream controller refactoring
