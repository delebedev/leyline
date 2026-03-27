---
name: forge-upstream-sync
description: Sync the forge engine submodule with upstream Card-Forge/forge. Merge upstream master, resolve conflicts preserving fork-local patches, validate with leyline test suite, create PRs for both repos.
---

## What I do

Merge upstream `Card-Forge/forge` master into `delebedev/forge` fork, validate against leyline's test suite, and land via PRs to both repos.

**Reference:** `fork-deltas.md` in this folder has the full fork-local delta inventory, preserve surface, and validation history. Read it before starting.

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

Check preserve surface for upstream changes (see `fork-deltas.md` for full file list):

```bash
git -C forge log --oneline FETCH_HEAD --not HEAD -- \
  forge-gui/src/main/java/forge/player/PlayerControllerHuman.java \
  forge-game/src/main/java/forge/game/player/PlaySpellAbility.java \
  forge-game/src/main/java/forge/game/event/ \
  forge-game/src/main/java/forge/game/GameAction.java \
  forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java
```

### 3. Create sync worktree from origin/master

```bash
git -C forge fetch origin master
git -C forge worktree add ../forge--sync -b sync/upstream-$(date +%Y-%m-%d) origin/master
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

Bulk upstream-take for non-leyline files:

```bash
git diff --diff-filter=U --name-only | grep -v "preserve-pattern" | while IFS= read -r f; do
  git checkout --theirs -- "$f" && git add "$f" || git rm "$f"
done
```

### 5. Install merged forge

`just install-forge` hardcodes `forge/` dir. Run maven directly:

```bash
cd ~/src/leyline/forge--sync
mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install \
  -pl forge-core,forge-game,forge-ai,forge-gui -am \
  -DskipTests -Dcheckstyle.skip=true -q
rsync -a ~/src/leyline/forge--sync/.m2-local/ ~/src/leyline/forge/.m2-local/
```

### 6. Build + test

```bash
cd ~/src/leyline
just build
just test-gate        # must pass 100%
just test-integration # known pre-existing failures ok
```

### 7. If leyline breaks, patch in the right place

- Engine API change: patch in `forge--sync`, commit, re-install, re-test
- Leyline adapter code: patch in leyline

Common fixes:
- **Constructor signature changes** — update fork seam calls
- **Duplicate methods from auto-merge** — git silently duplicates identical methods added at different positions
- **Missing fork-local event fire sites** — single-line `game.fireEvent(...)` calls auto-merge away. Always verify fire sites (see `fork-deltas.md`)

### 8. Land it

1. Commit merge in `forge--sync`
2. Push branch to `delebedev/forge`, create PR (list preserved fork deltas)
3. Merge forge PR
4. In leyline: `git -C forge fetch origin master && git -C forge checkout origin/master && git add forge`
5. Create leyline branch + PR with submodule pointer update

## Troubleshooting

### Maven install fails with checkstyle errors
Add `-Dcheckstyle.skip=true`.

### Upstream fetch times out
Use `--depth=100`.

### Compile error: duplicate method
Auto-merge artifact. Search for the method name — delete the duplicate.

### Compile error: constructor signature mismatch
Read the new constructor, update the fork seam call.

### Tests fail for "missing annotation"
First check: (1) fork-local fire sites still present? (2) engine thread crashing earlier? (3) upstream changed Java call paths bypassing Kotlin overrides?

### Integration test timing regression
Check if pre-existing by testing against old forge jars.
